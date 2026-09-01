package com.iskeru.computeadmin.discovery.service;

import com.iskeru.computeadmin.discovery.AppPortItem;
import com.iskeru.computeadmin.discovery.ProposedRecipe;
import com.iskeru.computeadmin.discovery.service.DiscoveryService.CrossFamilyReconciled;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link DiscoveryService#reconcileCrossFamily} in isolation (spec-075 B2): the pure post-pass
 * transform that folds a fingerprinted well-known service's ports off the generic app-monitor and
 * onto its typed family recipe (NGINX/DATABASE) when one is present in the same pass, so a service
 * is represented once. Lives in {@code discovery.service} to reach the package-private transform.
 *
 * <p>spec-075.
 */
class CrossFamilyReconcileTest {

    private static final List<com.iskeru.computeadmin.discovery.ProposedAction> NO_ACTIONS = List.of();

    /** A native app-monitor item with a resolved context (the shape the A1 path emits). */
    private static AppPortItem app(String name, int port, String contextKey) {
        return new AppPortItem(name, port, "process", null, contextKey, contextKey,
                List.of(), "note", "low");
    }

    private static ProposedRecipe nginxFamily() {
        return new ProposedRecipe(RecipeType.NGINX, "nginx",
                "Discovered nginx service operations.", NO_ACTIONS);
    }

    private static ProposedRecipe genericMonitor(List<AppPortItem> apps) {
        return new ProposedRecipe(RecipeType.MONITOR, "generic app monitor",
                "generic app monitor", NO_ACTIONS, apps);
    }

    @Test
    void reconcile_NginxRecipePresent_RelocatesNginxPortsOntoItAndDropsThemFromGeneric() {
        AppPortItem nginx80 = app("nginx", 80, "/var/www");
        AppPortItem nginx443 = app("nginx", 443, "/var/www");
        AppPortItem orders = app("orders", 8080, "/opt/orders");
        ProposedRecipe generic = genericMonitor(List.of(nginx80, nginx443, orders));

        CrossFamilyReconciled result = DiscoveryService.reconcileCrossFamily(
                List.of(nginxFamily(), generic));

        // The nginx ports now ride the NGINX family recipe's app-port list…
        ProposedRecipe nginx = result.proposals().stream()
                .filter(p -> p.type() == RecipeType.NGINX).findFirst().orElseThrow();
        assertThat(nginx.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port)
                .containsExactly(tuple("nginx", 80), tuple("nginx", 443));

        // …and are gone from the generic app monitor, which keeps only the plain app.
        ProposedRecipe reducedGeneric = result.proposals().stream()
                .filter(p -> p.type() == RecipeType.MONITOR).findFirst().orElseThrow();
        assertThat(reducedGeneric.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port)
                .containsExactly(tuple("orders", 8080));
        // The generic monitor still has an item, so it is not force-refreshed-empty.
        assertThat(result.forceRefreshEmpty()).isEmpty();
    }

    @Test
    void reconcile_GenericEmptiedByRelocation_IsFlaggedForEmptyRefresh() {
        // A generic monitor whose ONLY items are the nginx ports is emptied by the relocation; it is
        // flagged so persist clears its stale list rather than leaving the moved-away entries.
        ProposedRecipe generic = genericMonitor(List.of(app("nginx", 80, "/var/www"),
                app("nginx", 443, "/var/www")));

        CrossFamilyReconciled result = DiscoveryService.reconcileCrossFamily(
                List.of(nginxFamily(), generic));

        ProposedRecipe reducedGeneric = result.proposals().stream()
                .filter(p -> p.type() == RecipeType.MONITOR).findFirst().orElseThrow();
        assertThat(reducedGeneric.appPortList()).isEmpty();
        assertThat(result.forceRefreshEmpty()).containsExactly("MONITOR generic app monitor");
    }

    @Test
    void reconcile_NoTypedFamilyPresent_LeavesEverythingUntouched() {
        // Without a NGINX/DATABASE recipe in the pass, the fingerprinted items stay on the generic
        // monitor (still grouped by their shared contextKey, spec-066).
        ProposedRecipe generic = genericMonitor(List.of(app("nginx", 80, "/var/www"),
                app("nginx", 443, "/var/www")));

        CrossFamilyReconciled result = DiscoveryService.reconcileCrossFamily(List.of(generic));

        assertThat(result.proposals()).containsExactly(generic);
        assertThat(result.forceRefreshEmpty()).isEmpty();
    }

    @Test
    void reconcile_PostgresPortWithDatabaseRecipe_FoldsUnderDatabaseFamily() {
        ProposedRecipe database = new ProposedRecipe(RecipeType.DATABASE, "postgresql",
                "Discovered PostgreSQL operations.", NO_ACTIONS);
        ProposedRecipe generic = genericMonitor(List.of(app("postgres", 5432, "/var/lib/postgresql")));

        CrossFamilyReconciled result = DiscoveryService.reconcileCrossFamily(
                List.of(database, generic));

        ProposedRecipe db = result.proposals().stream()
                .filter(p -> p.type() == RecipeType.DATABASE).findFirst().orElseThrow();
        assertThat(db.appPortList())
                .extracting(AppPortItem::appName, AppPortItem::port)
                .containsExactly(tuple("postgres", 5432));
    }
}
