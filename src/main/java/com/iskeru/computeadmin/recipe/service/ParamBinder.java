package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;
import com.iskeru.computeadmin.recipe.model.ParamKind;
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
     * The fixed, anchored charset for an {@code APP_PORT_LIST} item's {@code appName}
     * (spec-022). It is a <strong>constant for this kind</strong>, not a caller-supplied
     * regex — the {@code REGEX} escape hatch is not reused here — so an app name can
     * never widen the S4 surface. Anchored by {@code String#matches}.
     */
    public static final String APP_NAME_PATTERN = "[A-Za-z0-9._-]{1,64}";

    /** Inclusive port bounds for an {@code APP_PORT_LIST} item (spec-022). */
    public static final int PORT_MIN = 1;
    public static final int PORT_MAX = 65535;

    /** The two scalar component names a fan-out template binds per item (spec-022). */
    public static final String APP_NAME_COMPONENT = "app-name";
    public static final String PORT_COMPONENT = "port";

    /**
     * Validates {@code params} against the action's schema and returns the bound
     * argv (sudo-prefixed when required).
     *
     * <p>For a scalar action this is unchanged from spec-004. For an action that
     * declares an {@code APP_PORT_LIST} param (spec-022) this binds <strong>one
     * item's</strong> scalar values: the caller (the fan-out in {@code RunService})
     * passes a per-item map whose {@link #APP_NAME_COMPONENT}/{@link #PORT_COMPONENT}
     * entries are the item's {@code appName}/{@code port}, and the fixed single-app
     * template's {@code PARAM} tokens reference those component names. Each component
     * is validated against the fixed item schema ({@link #APP_NAME_PATTERN} / the
     * {@code [PORT_MIN, PORT_MAX]} range). The repeatable-ness lives entirely
     * <em>above</em> this method; the binder still emits discrete, validated argv for
     * exactly one invocation (S4 preserved per item).
     *
     * @throws ParamValidationException if any value is missing or fails its rule.
     */
    public List<String> bind(Action action, Map<String, String> params) {
        Map<String, String> supplied = params == null ? Map.of() : params;
        Map<String, ParamDef> defsByName = indexByName(action);
        boolean hasAppPortList = hasAppPortList(action);

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
            if (!supplied.containsKey(paramName)) {
                throw new ParamValidationException("Missing value for param '" + paramName + "'");
            }
            String value = supplied.get(paramName);

            ParamDef def = defsByName.get(paramName);
            if (def != null && def.getKind() != ParamKind.APP_PORT_LIST) {
                validate(def, value);
            } else if (hasAppPortList && isAppPortComponent(paramName)) {
                // A fan-out template binds the item's app-name/port components against
                // the fixed item schema — never a caller-supplied rule.
                validateAppPortComponent(paramName, value);
            } else {
                // A PARAM token with no matching def is a malformed action, not bad input.
                throw new ParamValidationException("No param definition for '" + paramName + "'");
            }
            argv.add(value);
        }
        return argv;
    }

    private static boolean hasAppPortList(Action action) {
        for (ParamDef def : action.getParamDefs()) {
            if (def.getKind() == ParamKind.APP_PORT_LIST) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code name} is one of the two fan-out item component names (spec-022). */
    public static boolean isAppPortComponent(String name) {
        return APP_NAME_COMPONENT.equals(name) || PORT_COMPONENT.equals(name);
    }

    /**
     * Validates a single {@code APP_PORT_LIST} item component against its fixed schema
     * (spec-022): {@code app-name} against {@link #APP_NAME_PATTERN}, {@code port}
     * against {@code [PORT_MIN, PORT_MAX]}. Public so the fan-out can validate every
     * item up front (invalid ⇒ nothing dispatched).
     */
    public void validateAppPortComponent(String component, String value) {
        if (value == null) {
            throw new ParamValidationException("Missing value for '" + component + "'");
        }
        if (APP_NAME_COMPONENT.equals(component)) {
            if (!Pattern.matches(APP_NAME_PATTERN, value)) {
                throw new ParamValidationException(
                        "app-name '" + value + "' is not a valid app identifier");
            }
        } else if (PORT_COMPONENT.equals(component)) {
            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new ParamValidationException("port '" + value + "' is not an integer");
            }
            if (parsed < PORT_MIN || parsed > PORT_MAX) {
                throw new ParamValidationException(
                        "port '" + value + "' is out of range [" + PORT_MIN + ", " + PORT_MAX + "]");
            }
        } else {
            throw new ParamValidationException("Unknown app-port component '" + component + "'");
        }
    }

    private void validate(ParamDef def, String value) {
        if (value == null) {
            throw new ParamValidationException("Missing value for param '" + def.getName() + "'");
        }
        switch (def.getKind()) {
            case ALLOWED_SET -> validateAllowedSet(def, value);
            case REGEX -> validateRegex(def, value);
            case INT_RANGE -> validateIntRange(def, value);
            // A composite is never bound as a single scalar token; reaching here means a
            // template referenced the composite by name — a malformed action (spec-022).
            case APP_PORT_LIST -> throw new ParamValidationException(
                    "Param '" + def.getName() + "' is an app-port list and cannot bind as a scalar");
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
