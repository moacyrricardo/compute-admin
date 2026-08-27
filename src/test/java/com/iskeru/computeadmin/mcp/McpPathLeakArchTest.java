package com.iskeru.computeadmin.mcp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of spec-055's S9 guarantee (the 028:224–227 deferred hardening),
 * asserted by scanning the {@code mcp} sources in the {@link
 * com.iskeru.computeadmin.recipe.GateArchTest} style: no serializer may <strong>re-introduce
 * a raw path leak</strong> onto the MCP surface. It bans two patterns:
 *
 * <ul>
 *   <li>an argToken serializer that maps a JSON {@code "value"} directly to a raw {@code
 *       getValue()} call (the pre-spec-055 {@code ListActionsTool.tokenView} leak) — values
 *       must route through {@code renderTokenValue}, which basename-renders path-shaped
 *       literals;
 *   <li>an MCP DTO field that exposes a context/app-root path
 *       ({@code contextKey}/{@code contextDisplay}/{@code scriptFolder}/{@code appRoot}).
 * </ul>
 *
 * <p>The scan guards the <em>pattern</em>; the runtime fix in {@code ListActionsTool}
 * guarantees the <em>output</em> ({@link ListActionsToolTest}). 060 verifies both end-to-end.
 *
 * <p>spec-055.
 */
class McpPathLeakArchTest {

    private static final Path MCP_SOURCES =
            Path.of("src/main/java/com/iskeru/computeadmin/mcp");

    /** A JSON {@code "value"} mapped straight from a raw {@code x.getValue()} call. */
    private static final Pattern RAW_VALUE_ECHO =
            Pattern.compile("\"value\"\\s*,\\s*[A-Za-z_][A-Za-z0-9_]*\\.getValue\\(\\)");

    /** Context/app-root path fields that must never surface on an MCP DTO. */
    private static final List<String> BANNED_PATH_FIELDS =
            List.of("contextKey", "contextDisplay", "scriptFolder", "appRoot");

    @Test
    void noMcpSerializerEchoesARawArgTokenValue() {
        for (Path file : mcpJavaFiles()) {
            assertThat(RAW_VALUE_ECHO.matcher(read(file)).find())
                    .as("mcp class %s must not map \"value\" to a raw getValue() — route path-shaped "
                            + "LITERALs through a basename renderer (S9, spec-055)", file)
                    .isFalse();
        }
    }

    @Test
    void noMcpDtoExposesAContextOrAppRootPathField() {
        for (Path file : mcpJavaFiles()) {
            String source = read(file);
            for (String field : BANNED_PATH_FIELDS) {
                assertThat(source)
                        .as("mcp class %s must not expose the path field %s on the MCP surface "
                                + "(S9, spec-055)", file, field)
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
