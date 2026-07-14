package com.iskeru.computeadmin.demo;

import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The {@code demo}-profile {@link SshExecutor}: returns scripted stdout/exit per
 * {@code (host, argv)} from {@link DemoFleet} instead of connecting over SSH, so the
 * demo/GIF harness can drive discovery + the monitor polls against a deterministic
 * <em>fake fleet</em> with no real hosts. See {@code demo/fake-fleet.md}.
 *
 * <p>{@code @Primary} so it wins injection over the real {@code MinaSshExecutor} (which
 * is {@code @Profile("!localssh")} and so also present under {@code demo}); {@code
 * @Profile("demo")} so it has <strong>zero</strong> effect on dev/prod/test runs. It
 * never touches the real transport, the approval gate, or the data model.
 */
@Component
@Primary
@Profile("demo")
public class CannedSshExecutor implements SshExecutor {

    @Override
    public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
        return DemoFleet.exec(target.host(), argv);
    }

    @Override
    public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
        ExecResult result = exec(target, argv, sudo);
        if (result.stdout() != null && !result.stdout().isEmpty()) {
            sink.onStdout(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isEmpty()) {
            sink.onStderr(result.stderr());
        }
        sink.onComplete(result.exitCode());
    }
}
