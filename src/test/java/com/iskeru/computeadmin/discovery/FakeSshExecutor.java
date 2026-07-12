package com.iskeru.computeadmin.discovery;

import com.iskeru.computeadmin.ssh.ExecResult;
import com.iskeru.computeadmin.ssh.OutputSink;
import com.iskeru.computeadmin.ssh.SshExecutor;
import com.iskeru.computeadmin.ssh.SshTarget;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Test {@link SshExecutor} that returns canned probe output keyed by the exact argv
 * and <strong>records every command it is asked to run</strong>. The recording is
 * how a test asserts discovery only ever sent fixed read-only probes and never a
 * mutating command (spec-006).
 *
 * <p>It also records, per {@code exec}, whether a real transaction was active
 * ({@link TransactionSynchronizationManager#isActualTransactionActive()}) — the seam
 * spec-013 uses to prove discovery runs its probes <strong>outside</strong> any
 * transaction.
 *
 * <p>spec-006; transaction-activity recording in spec-013.
 */
class FakeSshExecutor implements SshExecutor {

    /** Every argv passed to {@link #exec}/{@link #execStreaming}, in order. */
    final List<List<String>> commands = new ArrayList<>();

    /** Per {@code exec}, whether a real transaction was active at the time. */
    final List<Boolean> transactionActive = new ArrayList<>();

    private final Function<List<String>, ExecResult> responder;

    FakeSshExecutor(Function<List<String>, ExecResult> responder) {
        this.responder = responder;
    }

    static ExecResult ok(String stdout) {
        return new ExecResult(0, stdout, "");
    }

    static ExecResult notFound() {
        return new ExecResult(1, "", "");
    }

    @Override
    public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
        commands.add(List.copyOf(argv));
        transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
        ExecResult result = responder.apply(argv);
        return result == null ? notFound() : result;
    }

    @Override
    public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
        commands.add(List.copyOf(argv));
        transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
        sink.onComplete(0);
    }
}
