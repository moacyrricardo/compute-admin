package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Binds supplied parameter values into an action's argv — the runtime half of the
 * parameter gate (S4). Each supplied value is validated against its {@link
 * ParamDef} (member of the allowed set / matches the anchored regex / integer in
 * range); only then are the ordered {@link ArgToken}s walked, substituting {@code
 * PARAM} tokens with their validated values, and {@code ["sudo", "-n"]} prepended
 * when the action requires sudo.
 *
 * <p>Values are emitted as <strong>discrete argv elements</strong> — never a shell
 * line (S4). Reused verbatim by the run path (spec 005). Any invalid or missing
 * value raises {@link ParamValidationException} and nothing is bound.
 *
 * <p>spec-004.
 */
@Component
public class ParamBinder {

    /**
     * Validates {@code params} against the action's schema and returns the bound
     * argv (sudo-prefixed when required).
     *
     * @throws ParamValidationException if any value is missing or fails its rule.
     */
    public List<String> bind(Action action, Map<String, String> params) {
        Map<String, String> supplied = params == null ? Map.of() : params;
        Map<String, ParamDef> defsByName = indexByName(action);

        List<String> argv = new ArrayList<>();
        if (action.isSudo()) {
            argv.add("sudo");
            argv.add("-n");
        }

        List<ArgToken> tokens = new ArrayList<>(action.getArgTokens());
        tokens.sort(Comparator.comparingInt(ArgToken::getPosition));
        for (ArgToken token : tokens) {
            if (token.getKind() == TokenKind.LITERAL) {
                argv.add(token.getValue());
                continue;
            }
            String paramName = token.getValue();
            ParamDef def = defsByName.get(paramName);
            if (def == null) {
                // A PARAM token with no matching def is a malformed action, not bad input.
                throw new ParamValidationException("No param definition for '" + paramName + "'");
            }
            if (!supplied.containsKey(paramName)) {
                throw new ParamValidationException("Missing value for param '" + paramName + "'");
            }
            String value = supplied.get(paramName);
            validate(def, value);
            argv.add(value);
        }
        return argv;
    }

    private void validate(ParamDef def, String value) {
        if (value == null) {
            throw new ParamValidationException("Missing value for param '" + def.getName() + "'");
        }
        switch (def.getKind()) {
            case ALLOWED_SET -> validateAllowedSet(def, value);
            case REGEX -> validateRegex(def, value);
            case INT_RANGE -> validateIntRange(def, value);
        }
    }

    private void validateAllowedSet(ParamDef def, String value) {
        for (ParamAllowedValue allowed : def.getAllowedValues()) {
            if (allowed.getValue().equals(value)) {
                return;
            }
        }
        throw new ParamValidationException(
                "Value '" + value + "' is not allowed for param '" + def.getName() + "'");
    }

    private void validateRegex(ParamDef def, String value) {
        String pattern = def.getPattern();
        if (pattern == null) {
            throw new ParamValidationException("Param '" + def.getName() + "' has no regex pattern");
        }
        boolean matches;
        try {
            // String#matches anchors the whole input — a full match is required.
            matches = Pattern.matches(pattern, value);
        } catch (PatternSyntaxException e) {
            throw new ParamValidationException(
                    "Param '" + def.getName() + "' has an invalid regex: " + e.getMessage());
        }
        if (!matches) {
            throw new ParamValidationException(
                    "Value '" + value + "' does not match the pattern for param '" + def.getName() + "'");
        }
    }

    private void validateIntRange(ParamDef def, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ParamValidationException(
                    "Value '" + value + "' is not an integer for param '" + def.getName() + "'");
        }
        Integer min = def.getIntMin();
        Integer max = def.getIntMax();
        if ((min != null && parsed < min) || (max != null && parsed > max)) {
            throw new ParamValidationException(
                    "Value '" + value + "' is out of range [" + min + ", " + max
                            + "] for param '" + def.getName() + "'");
        }
    }

    private Map<String, ParamDef> indexByName(Action action) {
        Map<String, ParamDef> byName = new LinkedHashMap<>();
        for (ParamDef def : action.getParamDefs()) {
            byName.put(def.getName(), def);
        }
        return byName;
    }
}
