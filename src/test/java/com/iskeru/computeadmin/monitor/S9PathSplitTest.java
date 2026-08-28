package com.iskeru.computeadmin.monitor;

import com.iskeru.computeadmin.recipe.api.RecipeDtos.AppPortView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S9 split spec-063 pins on: a logical <strong>path</strong> may ride the authenticated web
 * DTOs (the admin UI is entitled to it — the 028 precedent), while it must never reach the MCP
 * surface. This test asserts both halves:
 *
 * <ul>
 *   <li><strong>Web side allows it.</strong> {@link AppPortView} — a UI DTO — carries an absolute
 *       {@code contextDisplay}/{@code scriptFolder} path through unchanged.</li>
 *   <li><strong>MCP side forbids it.</strong> No {@code mcp/*Tool} source names any of the
 *       context/app-root path fields as a serialized member (the complement of
 *       {@code McpPathLeakArchTest}, focused on the spec-063 fields).</li>
 * </ul>
 *
 * <p>{@code contextKey} — the internal dedup key — is not exposed on the web DTO either; the UI
 * keys on {@code contextDisplay}. {@code GateArchTest}/{@code McpPathLeakArchTest} stay green
 * unedited; this only pins the additive split.
 *
 * <p>spec-063.
 */
class S9PathSplitTest {

    private static final Path MCP_SOURCES =
            Path.of("src/main/java/com/iskeru/computeadmin/mcp");

    /** The context/path fields the web DTO may carry but no MCP tool may. */
    private static final List<String> PATH_FIELDS =
            List.of("contextKey", "contextDisplay", "scriptFolder", "sourceNote", "contextScripts");

    @Test
    void webDto_MayCarryAnAbsolutePath() {
        AppPortView view = new AppPortView("postgres", 5432, "process",
                "/var/lib/postgresql", List.of("postgres"),
                "common service · postgres", "high", "/var/lib/postgresql/bin");

        assertThat(view.contextDisplay()).isEqualTo("/var/lib/postgresql");
        assertThat(view.scriptFolder()).isEqualTo("/var/lib/postgresql/bin");
        // The UI DTO carries no internal contextKey field at all — the identity key stays service-side.
        assertThat(AppPortView.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("contextKey");
    }

    @Test
    void noMcpToolSurfacesAContextPathField() {
        for (Path file : mcpJavaFiles()) {
            String source = read(file);
            for (String field : PATH_FIELDS) {
                assertThat(source)
                        .as("mcp class %s must not surface the context path field %s (S9, spec-063)",
                                file, field)
                        .doesNotContain("\"" + field + "\"");
            }
        }
    }

    private List<Path> mcpJavaFiles() {
        try (Stream<Path> paths = Files.walk(MCP_SOURCES)) {
            return paths.filter(p -> p.toString().endsWith(".java")).toList();
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
