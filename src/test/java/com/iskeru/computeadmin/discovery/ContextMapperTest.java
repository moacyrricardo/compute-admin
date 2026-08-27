package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.discovery.service.ContextMapper;
import com.iskeru.computeadmin.discovery.service.ContextMapper.Context;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextMapper} — the pure resolution seam (spec-055). Exercises the D2
 * wrapper-directory rule (single hop; marker-gated, capped second hop; boundary clamp) and
 * the D1 identity key (dedup on the resolved physical path; promotion to the logical path
 * for atomic-symlink releases).
 *
 * <p>spec-055.
 */
class ContextMapperTest {

    /** No candidate directory carries a marker file (single-hop world). */
    private static final Predicate<String> NO_MARKERS = dir -> false;

    @Test
    void resolveContext_ScriptNotUnderWrapper_ContextIsTheScriptFolder() {
        Context context = ContextMapper.resolveContext("/opt/lab/app1", "/opt/lab/app1", NO_MARKERS);

        assertThat(context.scriptFolder()).isEqualTo("/opt/lab/app1");
        assertThat(context.display()).isEqualTo("/opt/lab/app1");
        assertThat(context.key()).isEqualTo("/opt/lab/app1");
    }

    @Test
    void resolveContext_ScriptDirectlyUnderWrapper_HopsToParent() {
        // /opt/lab/app1/scripts (wrapper "scripts") → context /opt/lab/app1.
        Context context = ContextMapper.resolveContext(
                "/opt/lab/app1/scripts", "/opt/lab/app1/scripts", NO_MARKERS);

        assertThat(context.display()).isEqualTo("/opt/lab/app1");
        assertThat(context.scriptFolder()).isEqualTo("/opt/lab/app1/scripts");
    }

    @Test
    void resolveContext_TwoScriptsOfSameApp_CollapseToTheSameContext() {
        // The spec's worked example: a wrapped script and a bare sibling → one context.
        Context wrapped = ContextMapper.resolveContext(
                "/opt/lab/app1/scripts", "/opt/lab/app1/scripts", NO_MARKERS);
        Context bare = ContextMapper.resolveContext("/opt/lab/app1", "/opt/lab/app1", NO_MARKERS);

        assertThat(wrapped.key()).isEqualTo(bare.key()).isEqualTo("/opt/lab/app1");
    }

    @Test
    void resolveContext_DoubleWrapperWithMarker_TakesTheSecondHop() {
        // /opt/lab/app1/backend/scripts, backend also a wrapper, app1 carries .git → hop twice.
        Predicate<String> markerOnApp1 = dir -> dir.equals("/opt/lab/app1");
        Context context = ContextMapper.resolveContext(
                "/opt/lab/app1/backend/scripts", "/opt/lab/app1/backend/scripts", markerOnApp1);

        assertThat(context.display()).isEqualTo("/opt/lab/app1");
    }

    @Test
    void resolveContext_DoubleWrapperWithoutMarker_StopsAtSingleHop() {
        Context context = ContextMapper.resolveContext(
                "/opt/lab/app1/backend/scripts", "/opt/lab/app1/backend/scripts", NO_MARKERS);

        // No marker on the grandparent → only the single default hop fires.
        assertThat(context.display()).isEqualTo("/opt/lab/app1/backend");
    }

    @Test
    void resolveContext_TripleWrapperWithMarkers_IsCappedAtTwoHops() {
        // src/backend/scripts, every level a wrapper, markers everywhere → still only 2 hops.
        Predicate<String> allMarkers = dir -> true;
        Context context = ContextMapper.resolveContext(
                "/opt/lab/app1/src/backend/scripts", "/opt/lab/app1/src/backend/scripts", allMarkers);

        assertThat(context.display()).isEqualTo("/opt/lab/app1/src");
    }

    @Test
    void resolveContext_HopWouldLandOnSystemRoot_IsClampedToTheChild() {
        // /srv/app: "app" is a wrapper, but its parent /srv is a boundary root → stay put.
        Context context = ContextMapper.resolveContext("/srv/app", "/srv/app", NO_MARKERS);

        assertThat(context.display()).isEqualTo("/srv/app");
    }

    @Test
    void resolveContext_ReleaseDeployPath_PromotesKeyToTheLogicalPath() {
        // current → releases/<ts>: physical is a release dir, so the key is the logical path
        // (redeploy-stable), while display stays logical too.
        Context context = ContextMapper.resolveContext(
                "/opt/app/current", "/opt/app/releases/20260101T00", NO_MARKERS);

        assertThat(context.display()).isEqualTo("/opt/app/current");
        assertThat(context.key()).isEqualTo("/opt/app/current");
    }

    @Test
    void resolveContext_VersionsDeployPath_PromotesKeyToTheLogicalPath() {
        Context context = ContextMapper.resolveContext(
                "/opt/app/current", "/opt/app/versions/7", NO_MARKERS);

        assertThat(context.key()).isEqualTo("/opt/app/current");
    }

    @Test
    void resolveContext_NonReleasePhysicalPath_KeysOnThePhysicalPath() {
        // Two logical paths that symlink to the same physical dir dedup on the physical key
        // (non-wrapper basenames → no hop, so the physical path is the context itself).
        Context viaLogicalA = ContextMapper.resolveContext(
                "/opt/app/current", "/data/deployed/svc", NO_MARKERS);
        Context viaLogicalB = ContextMapper.resolveContext(
                "/home/deploy/live", "/data/deployed/svc", NO_MARKERS);

        assertThat(viaLogicalA.key()).isEqualTo("/data/deployed/svc");
        assertThat(viaLogicalA.key()).isEqualTo(viaLogicalB.key());
        // …but each still displays its own logical path.
        assertThat(viaLogicalA.display()).isEqualTo("/opt/app/current");
        assertThat(viaLogicalB.display()).isEqualTo("/home/deploy/live");
    }

    @Test
    void resolveContext_BlankScriptPath_ReturnsNull() {
        assertThat(ContextMapper.resolveContext(null, null, NO_MARKERS)).isNull();
        assertThat(ContextMapper.resolveContext("  ", "  ", NO_MARKERS)).isNull();
    }

    @Test
    void resolveContext_NullPhysicalPath_KeysOnTheLogicalContext() {
        Context context = ContextMapper.resolveContext("/opt/lab/app1/scripts", null, NO_MARKERS);

        assertThat(context.key()).isEqualTo("/opt/lab/app1");
        assertThat(context.display()).isEqualTo("/opt/lab/app1");
    }

    @Test
    void constantSets_AreTheFixedDecisionVocabulary() {
        assertThat(ContextMapper.wrapperSet())
                .contains("scripts", "bin", "sbin", "libexec", "frontend", "backend",
                        "cmd", "dist", "build", "src", "app");
        assertThat(ContextMapper.markerFiles())
                .isEqualTo(Set.of(".git", "compose.yaml", "compose.yml",
                        "docker-compose.yml", "package.json"));
        assertThat(ContextMapper.boundaryRoots())
                .isEqualTo(Set.of("/opt", "/srv", "/home", "/usr", "/var", "/"));
    }
}
