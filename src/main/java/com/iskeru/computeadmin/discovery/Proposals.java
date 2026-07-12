package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.recipe.model.ParamKind;
import com.iskeru.computeadmin.recipe.model.TokenKind;
import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;

import java.util.List;

/**
 * Tiny factory helpers for building the 004 argv/param shapes a
 * {@link RecipeDiscoverer} proposes, so each discoverer reads as its command
 * catalog rather than record boilerplate. Pure helper (no suffix, no state).
 *
 * <p>spec-006.
 */
public final class Proposals {

    private Proposals() {
    }

    /** A literal argv element. */
    public static ArgTokenInput literal(String value) {
        return new ArgTokenInput(TokenKind.LITERAL, value);
    }

    /** A param-reference argv element (the referenced param name in {@code value}). */
    public static ArgTokenInput param(String name) {
        return new ArgTokenInput(TokenKind.PARAM, name);
    }

    /** A closed-set param whose values come from discovered names/paths. */
    public static ParamDefInput allowedSet(String name, List<String> values) {
        return new ParamDefInput(name, ParamKind.ALLOWED_SET, null, null, null, List.copyOf(values));
    }

    /** An integer-range param, inclusive {@code [min, max]}. */
    public static ParamDefInput intRange(String name, int min, int max) {
        return new ParamDefInput(name, ParamKind.INT_RANGE, null, min, max, null);
    }

    /**
     * The repeatable {@code (app-name, port)} composite an app-monitor probe action
     * fans out over (spec-022/025). No per-instance config: the app-name charset and
     * port range are fixed constants on {@code ParamBinder}, so the kind alone carries
     * the meaning. A probe template references it by its {@code app-name}/{@code port}
     * components, never by this name.
     */
    public static ParamDefInput appPortList(String name) {
        return new ParamDefInput(name, ParamKind.APP_PORT_LIST, null, null, null, null);
    }
}
