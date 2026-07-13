package com.iskeru.computeadmin.ssh;

import java.util.List;

/**
 * A minimal always-succeeding {@link SshExecutor} for slice tests that must supply the
 * port bean (since {@code ApprovalService}/{@code RunService} now depend on it via
 * {@code ScriptPinService}, spec-015) but do not exercise real SSH. A {@code sha256sum}
 * probe returns a well-formed 64-hex digest so approving a {@code CUSTOM} action pins
 * cleanly; any other command just exits zero.
 *
 * <p>spec-015.
 */
public class StubSshExecutor implements SshExecutor {

    /** A valid 64-char hex digest (the SHA-256 of the empty input) any probe reports. */
    public static final String STUB_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Override
    public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
        if (!argv.isEmpty() && "sha256sum".equals(argv.get(0))) {
            String path = argv.size() > 1 ? argv.get(1) : "";
            return new ExecResult(0, STUB_HASH + "  " + path + "\n", "");
        }
        return new ExecResult(0, "ok\n", "");
    }

    @Override
    public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
        sink.onStdout("ok\n");
        sink.onComplete(0);
    }
}
