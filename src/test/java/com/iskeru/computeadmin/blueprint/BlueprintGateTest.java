package com.iskeru.computeadmin.blueprint;

import com.iskeru.computeadmin.blueprint.model.BlueprintAction;
import com.iskeru.computeadmin.blueprint.model.RecipeBlueprint;
import com.iskeru.computeadmin.machine.model.Machine;
import com.iskeru.computeadmin.recipe.model.ApprovalState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural guarantee for blueprints (spec-010), extending {@code GateArchTest}
 * to the blueprint surface. A blueprint is authoring-only: it has <strong>no
 * approval state and no run path</strong>, and the {@code mcp} blueprint tools never
 * reference {@code ApprovalService} (a blueprint has nothing to approve; the
 * per-machine gate stays UI-only).
 *
 * <p>Asserted two ways: reflection over the blueprint model (no {@code ApprovalState}
 * field, no {@code Machine}/run linkage), and a source scan of the {@code mcp}
 * package's blueprint tools (a compile-time reference would name the type in source).
 *
 * <p>spec-010.
 */
class BlueprintGateTest {

    private static final Path MCP_SOURCES =
            Path.of("src/main/java/com/iskeru/computeadmin/mcp");

    @Test
    void recipeBlueprint_HasNoApprovalStateAndNoMachine() {
        for (Field field : RecipeBlueprint.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("RecipeBlueprint.%s must not carry approval state", field.getName())
                    .isNotEqualTo(ApprovalState.class);
            assertThat(field.getType())
                    .as("RecipeBlueprint.%s must not link a Machine (blueprints are machine-independent)", field.getName())
                    .isNotEqualTo(Machine.class);
            assertThat(field.getName().toLowerCase())
                    .as("RecipeBlueprint.%s must not name an approval field", field.getName())
                    .doesNotContain("approval");
        }
    }

    @Test
    void blueprintAction_HasNoApprovalStateAndNoRunPath() {
        for (Field field : BlueprintAction.class.getDeclaredFields()) {
            assertThat(field.getType())
                    .as("BlueprintAction.%s must not carry approval state", field.getName())
                    .isNotEqualTo(ApprovalState.class);
            assertThat(field.getType())
                    .as("BlueprintAction.%s must not link a Machine", field.getName())
                    .isNotEqualTo(Machine.class);
            assertThat(field.getName().toLowerCase())
                    .as("BlueprintAction.%s must not name an approval/run field", field.getName())
                    .doesNotContain("approval")
                    .doesNotContain("approved")
                    .doesNotContain("run");
        }
    }

    @Test
    void mcpBlueprintTools_Exist() {
        assertThat(blueprintToolFiles())
                .as("expected at least one mcp blueprint tool to guard")
                .isNotEmpty();
    }

    @Test
    void mcpBlueprintTools_DoNotReferenceApprovalService() {
        for (Path file : blueprintToolFiles()) {
            assertThat(read(file))
                    .as("mcp blueprint tool %s must not reference ApprovalService (blueprints have no approval)", file)
                    .doesNotContain("ApprovalService");
        }
    }

    /** The mcp source files whose name or content concerns blueprints. */
    private List<Path> blueprintToolFiles() {
        try (Stream<Path> paths = Files.walk(MCP_SOURCES)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("Blueprint"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
