package com.iskeru.computeadmin.machine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard on the core invariant (ARCH.md): the event path introduced by
 * spec-019 must stay a pure <em>status flag</em> — it may never run or approve an
 * action. Mirroring {@link com.iskeru.computeadmin.recipe.GateArchTest}, this scans
 * the {@code machine/event} sources and asserts no class references
 * {@code RunService} or {@code ApprovalService}. A compile-time call to either would
 * necessarily name the type in source, so the absence of the name is proof the
 * listener can neither execute nor approve — approval stays UI-only.
 *
 * <p>spec-019.
 */
class MachineEventArchTest {

    private static final Path EVENT_SOURCES =
            Path.of("src/main/java/com/iskeru/computeadmin/machine/event");

    @Test
    void eventSourcesExist() {
        assertThat(Files.isDirectory(EVENT_SOURCES))
                .as("machine/event source package should exist at %s", EVENT_SOURCES.toAbsolutePath())
                .isTrue();
        assertThat(eventJavaFiles()).as("machine/event package should contain at least one class").isNotEmpty();
    }

    @Test
    void noEventClassReferencesRunOrApprovalService() {
        for (Path file : eventJavaFiles()) {
            String source = read(file);
            assertThat(source)
                    .as("event class %s must not reference RunService (the listener never runs an action)", file)
                    .doesNotContain("RunService");
            assertThat(source)
                    .as("event class %s must not reference ApprovalService (approval is UI-only)", file)
                    .doesNotContain("ApprovalService");
        }
    }

    private List<Path> eventJavaFiles() {
        try (Stream<Path> paths = Files.walk(EVENT_SOURCES)) {
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
