package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.recipe.service.ActionService.ArgTokenInput;
import com.iskeru.computeadmin.recipe.service.ActionService.ParamDefInput;

import java.util.List;

/**
 * One action a {@link RecipeDiscoverer} proposes, reusing the 004 authoring shapes:
 * a structured argv ({@link ArgTokenInput}s) plus a typed param schema
 * ({@link ParamDefInput}s) plus the {@code sudo} flag. {@code description} is free
 * display text the human reads at approval time — the spec-006 Known Gap requires a
 * proposal to be reviewable, since discovered {@code ALLOWED_SET} values are
 * attacker-influenced input (S3).
 *
 * <p>A discoverer proposes mutating actions too (restart, enable-site, …); they are
 * still gated — {@link DiscoveryService} persists every proposed action in
 * {@code PENDING_APPROVAL} and never approves.
 *
 * <p>spec-006.
 */
public record ProposedAction(String name, String description, boolean sudo,
                             List<ArgTokenInput> argTokens, List<ParamDefInput> paramDefs) {
}
