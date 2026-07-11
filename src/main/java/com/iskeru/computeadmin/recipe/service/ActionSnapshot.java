package com.iskeru.computeadmin.recipe.service;

import com.iskeru.computeadmin.recipe.model.Action;
import com.iskeru.computeadmin.recipe.model.ArgToken;
import com.iskeru.computeadmin.recipe.model.ParamAllowedValue;
import com.iskeru.computeadmin.recipe.model.ParamDef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Computes the deterministic content hash an approval binds to. {@link
 * #hash(Action)} is a SHA-256 over a canonical serialization of the parts that
 * define <em>what the action does</em> — the ordered {@code argTokens}, the
 * {@code paramDefs} sorted by name with their rules, and the {@code sudo} flag.
 *
 * <p>The hash is independent of database ids and timestamps, so re-reading the
 * same action yields the same hash, while any structural change (a token, a param
 * rule, or {@code sudo}) changes it. That is what lets the gate detect an
 * approve-then-mutate: on run, the current hash is compared against the one stored
 * at approval (spec 005).
 *
 * <p>spec-004.
 */
public final class ActionSnapshot {

    private ActionSnapshot() {
    }

    /** SHA-256 hex over the canonical (argTokens, paramDefs, sudo) serialization. */
    public static String hash(Action action) {
        String canonical = canonical(action);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String canonical(Action action) {
        StringBuilder sb = new StringBuilder();
        sb.append("sudo=").append(action.isSudo()).append('\n');

        sb.append("tokens:\n");
        List<ArgToken> tokens = new ArrayList<>(action.getArgTokens());
        tokens.sort(Comparator.comparingInt(ArgToken::getPosition));
        for (ArgToken token : tokens) {
            sb.append(token.getPosition()).append('|')
                    .append(token.getKind()).append('|')
                    .append(token.getValue()).append('\n');
        }

        sb.append("params:\n");
        List<ParamDef> defs = new ArrayList<>(action.getParamDefs());
        defs.sort(Comparator.comparing(ParamDef::getName));
        for (ParamDef def : defs) {
            sb.append(def.getName()).append('|')
                    .append(def.getKind()).append('|')
                    .append("pattern=").append(def.getPattern()).append('|')
                    .append("min=").append(def.getIntMin()).append('|')
                    .append("max=").append(def.getIntMax()).append('|')
                    .append("allowed=").append(sortedAllowedValues(def)).append('\n');
        }
        return sb.toString();
    }

    private static List<String> sortedAllowedValues(ParamDef def) {
        List<String> values = new ArrayList<>();
        for (ParamAllowedValue allowed : def.getAllowedValues()) {
            values.add(allowed.getValue());
        }
        values.sort(Comparator.naturalOrder());
        return values;
    }
}
