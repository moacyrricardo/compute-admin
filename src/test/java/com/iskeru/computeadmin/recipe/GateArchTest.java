package com.iskeru.computeadmin.recipe;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural guarantee behind the core invariant (spec-004): the {@code mcp}
 * module can neither approve nor bypass the gate. Approval is REST/UI-only, so
 * <strong>no class in the {@code mcp} package may reference {@code
 * ApprovalService}</strong>, and — since {@code mcp} is a thin adapter over
 * feature <em>services</em> — none may reference a {@code *Repository} either.
 *
 * <p>Asserted by scanning the {@code mcp} sources: a compile-time reference would
 * necessarily name the type in source. This is cheaper and clearer than pulling in
 * a bytecode arch-test dependency.
 *
 * <p>spec-008 extends the check to the whole write/run surface and adds an explicit
 * assertion that <strong>no MCP tool is an approve tool</strong>: no {@code
 * new McpSchema.Tool("name", …)} declaration in the package names a tool whose name
 * implies approval. Approval is REST/UI-only.
 *
 * <p>spec-004; extended in spec-008.
 */
class GateArchTest {

    private static final Path MCP_SOURCES =
            Path.of("src/main/java/com/iskeru/computeadmin/mcp");

    /** The first string literal argument of a {@code new McpSchema.Tool("name", …)}. */
    private static final Pattern TOOL_NAME =
            Pattern.compile("new McpSchema\\.Tool\\(\\s*\"([^\"]+)\"");

    @Test
    void mcpSourcesExist() {
        assertThat(Files.isDirectory(MCP_SOURCES))
                .as("mcp source package should exist at %s", MCP_SOURCES.toAbsolutePath())
                .isTrue();
        assertThat(mcpJavaFiles()).as("mcp package should contain at least one class").isNotEmpty();
    }

    @Test
    void noMcpClassReferencesApprovalService() {
        for (Path file : mcpJavaFiles()) {
            assertThat(read(file))
                    .as("mcp class %s must not reference ApprovalService (approval is UI-only)", file)
                    .doesNotContain("ApprovalService");
        }
    }

    @Test
    void noMcpClassReferencesARepository() {
        for (Path file : mcpJavaFiles()) {
            assertThat(read(file))
                    .as("mcp class %s must not reference a *Repository (mcp is a thin service adapter)", file)
                    .doesNotContain("Repository");
        }
    }

    @Test
    void noMcpToolIsAnApproveTool() {
        for (Path file : mcpJavaFiles()) {
            Matcher matcher = TOOL_NAME.matcher(read(file));
            while (matcher.find()) {
                String toolName = matcher.group(1).toLowerCase();
                assertThat(toolName)
                        .as("mcp tool %s in %s must not be an approve tool (approval is UI-only)",
                                toolName, file)
                        .doesNotContain("approve")
                        .doesNotContain("approval");
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
