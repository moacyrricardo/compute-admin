package com.iskeru.computeadmin.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.computeadmin.discovery.service.DockerComposeDiscoverer;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.monitor.model.Bucket;
import com.iskeru.computeadmin.monitor.model.ConsumerRole;
import com.iskeru.computeadmin.monitor.model.Dedication;
import com.iskeru.computeadmin.recipe.model.RecipeType;
import com.iskeru.computeadmin.ssh.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static com.iskeru.computeadmin.discovery.FakeSshExecutor.notFound;
import static com.iskeru.computeadmin.discovery.FakeSshExecutor.ok;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DockerComposeDiscoverer} against a fake executor (spec-033): compose-project
 * grouping, datastore classification (DEDICATED to a project, SHARED when standalone),
 * the DOCKER bucket remainder, and the fixed read-only checks. Every proposal is a
 * {@code MONITOR} recipe — no new {@code RecipeType}. Per-machine enablement gating
 * (whether this discoverer is invoked at all) is superseded to spec-035 and covered by
 * {@link DiscoveryGatingTest}; this discoverer always probes when {@code discover} is
 * called.
 *
 * <p>spec-033.
 */
class DockerComposeDiscovererTest {

    private final ObjectMapper json = new ObjectMapper();

    // orders: web (app) + db (postgres, dedicated); a standalone redis (shared);
    // a standalone rabbitmq (not a datastore → DOCKER bucket).
    private static final String PS_JSON = String.join("\n",
            row("orders-web-1", "orders/web:latest", "com.docker.compose.project=orders,com.docker.compose.service=web"),
            row("orders-db-1", "postgres:16", "com.docker.compose.project=orders,com.docker.compose.service=db"),
            row("cache", "redis:7-alpine", ""),
            row("broker", "rabbitmq:3-management", ""));

    private static String row(String names, String image, String labels) {
        return "{\"Names\":\"" + names + "\",\"Image\":\"" + image + "\",\"Labels\":\"" + labels + "\"}";
    }

    private DockerComposeDiscoverer discoverer() {
        return new DockerComposeDiscoverer(json);
    }

    @Test
    void discover_DockerNotInstalled_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(argv -> notFound());

        assertThat(discoverer().discover(machine(), ssh)).isEmpty();
    }

    @Test
    void discover_GroupsComposeProject_IsOneConsumerWithDatastoreServices() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerWith(PS_JSON));

        List<ProposedRecipe> recipes = discoverer().discover(machine(), ssh);

        ProposedRecipe orders = recipe(recipes, "orders");
        assertThat(orders.type()).isEqualTo(RecipeType.MONITOR);
        assertThat(orders.appPortList()).isEmpty();
        assertThat(orders.actions()).extracting(ProposedAction::name)
                .containsExactly("docker stats", "docker disk", "docker volumes");

        // spec-038: a compose project is ONE consumer carrying ALL its services — app AND
        // datastore — each tagged with its role; there is NO separate dedicated consumer.
        assertThat(orders.dockerConsumers()).extracting(DockerConsumer::name).containsExactly("orders");
        DockerConsumer app = consumer(orders, "orders");
        assertThat(app.role()).isEqualTo(ConsumerRole.APP);
        assertThat(app.dedication()).isNull();
        assertThat(app.services()).extracting(DockerConsumer.DockerService::name)
                .containsExactly("orders-web-1", "orders-db-1");

        // The postgres container is a role=DATABASE SERVICE of the project — dedicated to
        // it by virtue of that role + parent project (the Databases lens derives from it).
        DockerConsumer.DockerService web = service(app, "orders-web-1");
        assertThat(web.role()).isEqualTo(ConsumerRole.APP);
        DockerConsumer.DockerService db = service(app, "orders-db-1");
        assertThat(db.role()).isEqualTo(ConsumerRole.DATABASE);
    }

    @Test
    void discover_StandaloneDatastore_IsShared_AndNonDatastoreGoesToBucket() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerWith(PS_JSON));

        List<ProposedRecipe> recipes = discoverer().discover(machine(), ssh);

        // The bare redis is a SHARED datastore consumer (no owning project).
        DockerConsumer redis = consumer(recipe(recipes, "cache"), "cache");
        assertThat(redis.role()).isEqualTo(ConsumerRole.DATABASE);
        assertThat(redis.dedication()).isEqualTo(Dedication.SHARED);
        assertThat(redis.owner()).isNull();

        // rabbitmq isn't a datastore → the DOCKER bucket (empty services, spec-032 §5).
        DockerConsumer bucket = consumer(recipe(recipes, "docker containers"), "docker");
        assertThat(bucket.bucket()).isEqualTo(Bucket.DOCKER);
        assertThat(bucket.role()).isEqualTo(ConsumerRole.OTHER);
        assertThat(bucket.services()).isEmpty();
    }

    @Test
    void discover_NoContainers_ProposesNothing() {
        FakeSshExecutor ssh = new FakeSshExecutor(dockerWith(""));

        assertThat(discoverer().discover(machine(), ssh)).isEmpty();
    }

    private Function<List<String>, ExecResult> dockerWith(String psJson) {
        return argv -> {
            if (argv.equals(List.of("command", "-v", "docker"))) {
                return ok("/usr/bin/docker");
            }
            if (argv.equals(List.of("docker", "ps", "--format", "{{json .}}"))) {
                return ok(psJson);
            }
            return notFound();
        };
    }

    private static ProposedRecipe recipe(List<ProposedRecipe> recipes, String name) {
        return recipes.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    private static DockerConsumer consumer(ProposedRecipe recipe, String name) {
        return recipe.dockerConsumers().stream()
                .filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static DockerConsumer.DockerService service(DockerConsumer consumer, String name) {
        return consumer.services().stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static Machine machine() {
        Machine machine = new Machine();
        machine.setHost("host");
        machine.setPort(22);
        machine.setLoginUser("deploy");
        return machine;
    }
}
