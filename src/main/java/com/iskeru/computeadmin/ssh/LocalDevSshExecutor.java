package com.iskeru.computeadmin.ssh;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline {@link SshExecutor} for the {@code localssh} profile: runs the argv as a
 * local process instead of connecting over SSH, so the app can be exercised
 * without a reachable target. The normal dev/verify path uses
 * {@link MinaSshExecutor} against a throwaway sshd container (see project
 * {@code CLAUDE.md}); this exists only for fully offline work.
 *
 * <p>argv is handed straight to {@code ProcessBuilder} as discrete process
 * arguments (no shell involved at all) and {@code sudo} prepends {@code sudo -n}.
 * This honours the same argv-in, injection-safe {@link SshExecutor} contract as the
 * real adapter — which reaches the same guarantee by single-quoting each element
 * into the shell line SSH {@code exec} runs, rather than through the OS process API.
 *
 * <p>spec-003.
 */
@Component
@Profile("localssh")
public class LocalDevSshExecutor implements SshExecutor {

    @Override
    public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
        try {
            Process process = start(argv, sudo);
            String stdout = drain(process.getInputStream());
            String stderr = drain(process.getErrorStream());
            int exit = process.waitFor();
            return new ExecResult(exit, stdout, stderr);
        } catch (IOException e) {
            throw new SshExecutionException(target, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SshExecutionException(target, e);
        }
    }

    @Override
    public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
        ExecResult result = exec(target, argv, sudo);
        if (!result.stdout().isEmpty()) {
            sink.onStdout(result.stdout());
        }
        if (!result.stderr().isEmpty()) {
            sink.onStderr(result.stderr());
        }
        sink.onComplete(result.exitCode());
    }

    private static Process start(List<String> argv, boolean sudo) throws IOException {
        List<String> command = new ArrayList<>();
        if (sudo) {
            command.add("sudo");
            command.add("-n");
        }
        command.addAll(argv);
        return new ProcessBuilder(command).start();
    }

    private static String drain(java.io.InputStream stream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
            return builder.toString();
        }
    }
}
