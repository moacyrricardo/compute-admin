package com.iskeru.computeadmin.ssh;

import org.apache.sshd.client.SshClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Injection-safety of {@link MinaSshExecutor#assembleCommand} (S4). SSH {@code exec}
 * runs a single command string through the remote shell, so the executor must
 * POSIX-single-quote each argv element: every value — including one carrying
 * spaces, single quotes, or shell metacharacters — has to stay one literal
 * argument and cannot break out into command injection. {@code sudo} prepends the
 * bare {@code sudo -n} tokens, unquoted.
 *
 * <p>spec-003; shared-client pooling asserted in spec-013.
 */
class MinaSshExecutorTest {

    /**
     * spec-013: the single {@link SshClient} is started once (with its accept-all
     * verifier set once, S3) and reused across every {@code exec()} — {@code start()}
     * is never called per-command. Driven through the client-factory seam; the mock's
     * {@code connect} throws, so each {@code exec()} fails fast, but that is irrelevant
     * to the reuse assertion.
     */
    @Test
    void sharedClient_StartedOnce_AcrossManyExecs() throws Exception {
        SshClient client = mock(SshClient.class);
        KeyService keyService = mock(KeyService.class);
        when(client.connect(anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("no network in unit test"));

        MinaSshExecutor executor = new MinaSshExecutor(keyService, 1, 1, () -> client);

        for (int i = 0; i < 5; i++) {
            try {
                executor.exec(new SshTarget("host", 22, "user"), List.of("true"), false);
            } catch (RuntimeException expected) {
                // connect() throws in the mock; the point is that start() is not re-called.
            }
        }

        // Started once and its verifier set once, across all five execs.
        verify(client, times(1)).start();
        verify(client, times(1)).setServerKeyVerifier(any());
    }

    @Test
    void assembleCommand_QuotesEachArgvElement() {
        String command = MinaSshExecutor.assembleCommand(
                List.of("systemctl", "restart", "nginx"), false);

        assertThat(command).isEqualTo("'systemctl' 'restart' 'nginx'");
    }

    @Test
    void assembleCommand_WithSudo_PrependsBareSudoTokens() {
        String command = MinaSshExecutor.assembleCommand(
                List.of("systemctl", "restart", "nginx"), true);

        assertThat(command).isEqualTo("sudo -n 'systemctl' 'restart' 'nginx'");
    }

    @Test
    void assembleCommand_ValueWithSpaces_StaysOneQuotedArgument() {
        String command = MinaSshExecutor.assembleCommand(
                List.of("echo", "hello world"), false);

        assertThat(command).isEqualTo("'echo' 'hello world'");
    }

    @Test
    void assembleCommand_Sha256sumProbeWithSpacedPath_QuotesPathAsOneArgument() {
        // The spec-015 content-pinning probe: `sha256sum <path>`. A path with spaces must
        // stay one literal argument so the digest is of that file, not two mangled ones.
        String command = MinaSshExecutor.assembleCommand(
                List.of("sha256sum", "/path with space/run.sh"), false);

        assertThat(command).isEqualTo("'sha256sum' '/path with space/run.sh'");
    }

    @Test
    void assembleCommand_ValueWithSingleQuote_IsPosixEscaped() {
        String command = MinaSshExecutor.assembleCommand(
                List.of("echo", "it's a test"), false);

        // POSIX single-quote escaping: close, emit an escaped quote, reopen.
        assertThat(command).isEqualTo("'echo' 'it'\\''s a test'");
    }

    @Test
    void assembleCommand_ShellMetacharacters_AreQuotedNotInterpreted() {
        String command = MinaSshExecutor.assembleCommand(
                List.of("echo", "; rm -rf / $(whoami) `id` && reboot | tee"), false);

        assertThat(command).isEqualTo("'echo' '; rm -rf / $(whoami) `id` && reboot | tee'");
        // The entire injection payload is wrapped in one pair of single quotes:
        // no unescaped ; $ ` & | escapes the quoting to reach the shell.
        assertThat(command).startsWith("'echo' '");
        assertThat(command).endsWith("'");
    }
}
