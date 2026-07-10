package com.iskeru.computeadmin.ssh;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Injection-safety of {@link MinaSshExecutor#assembleCommand} (S4). SSH {@code exec}
 * runs a single command string through the remote shell, so the executor must
 * POSIX-single-quote each argv element: every value — including one carrying
 * spaces, single quotes, or shell metacharacters — has to stay one literal
 * argument and cannot break out into command injection. {@code sudo} prepends the
 * bare {@code sudo -n} tokens, unquoted.
 *
 * <p>spec-003.
 */
class MinaSshExecutorTest {

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
