package com.iskeru.computeadmin.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;

/**
 * Real {@link SshExecutor} over Apache MINA SSHD. Connects as the target's login
 * user, authenticates with the app keypair ({@link KeyService}), and accepts any
 * host key ({@link AcceptAllServerKeyVerifier}, S3). {@code sudo} prefixes the
 * argv with {@code sudo -n} (passwordless, S5). The SSH {@code exec} channel has
 * no argv-level protocol, so the command necessarily reaches the target as a
 * single shell line; the S4 injection guarantee is therefore delivered by POSIX
 * single-quoting each argv element into that line, so a typed parameter stays one
 * literal argument and cannot break out.
 *
 * <p>Default bean; the {@code localssh} profile swaps in {@link LocalDevSshExecutor}.
 *
 * <p>spec-003.
 */
@Component
@Profile("!localssh")
public class MinaSshExecutor implements SshExecutor {

    private final KeyService keyService;
    private final Duration connectTimeout;
    private final Duration execTimeout;

    public MinaSshExecutor(KeyService keyService,
                           @Value("${ca.ssh.connect-timeout-seconds:10}") long connectTimeoutSeconds,
                           @Value("${ca.ssh.exec-timeout-seconds:60}") long execTimeoutSeconds) {
        this.keyService = keyService;
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        this.execTimeout = Duration.ofSeconds(execTimeoutSeconds);
    }

    @Override
    public ExecResult exec(SshTarget target, List<String> argv, boolean sudo) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = run(target, argv, sudo, out, err);
        return new ExecResult(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void execStreaming(SshTarget target, List<String> argv, boolean sudo, OutputSink sink) {
        OutputStream out = chunkStream(sink::onStdout);
        OutputStream err = chunkStream(sink::onStderr);
        int exit = run(target, argv, sudo, out, err);
        sink.onComplete(exit);
    }

    private int run(SshTarget target, List<String> argv, boolean sudo, OutputStream out, OutputStream err) {
        String command = assembleCommand(argv, sudo);
        try (SshClient client = SshClient.setUpDefaultClient()) {
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
            client.start();
            try (ClientSession session = client
                    .connect(target.loginUser(), target.host(), target.port())
                    .verify(connectTimeout)
                    .getSession()) {
                session.addPublicKeyIdentity(keyService.keyPair());
                session.auth().verify(connectTimeout);
                try (ChannelExec channel = session.createExecChannel(command)) {
                    channel.setOut(out);
                    channel.setErr(err);
                    channel.open().verify(connectTimeout);
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), execTimeout);
                    Integer exit = channel.getExitStatus();
                    return exit == null ? -1 : exit;
                }
            } finally {
                client.stop();
            }
        } catch (IOException e) {
            throw new SshExecutionException(target, e);
        }
    }

    /**
     * Joins argv into a command string with every element single-quoted, so the
     * remote shell treats each as one literal argument. {@code sudo -n} is
     * prepended (as bare tokens, never quoted) when escalation is requested.
     */
    static String assembleCommand(List<String> argv, boolean sudo) {
        StringBuilder command = new StringBuilder();
        if (sudo) {
            command.append("sudo -n ");
        }
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) {
                command.append(' ');
            }
            command.append(singleQuote(argv.get(i)));
        }
        return command.toString();
    }

    /** POSIX single-quote escaping: wrap in '...', closing/reopening around any '. */
    private static String singleQuote(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    private static OutputStream chunkStream(java.util.function.Consumer<String> consumer) {
        return new OutputStream() {
            @Override
            public void write(int b) {
                consumer.accept(String.valueOf((char) (b & 0xff)));
            }

            @Override
            public void write(byte[] b, int off, int len) {
                consumer.accept(new String(b, off, len, StandardCharsets.UTF_8));
            }
        };
    }
}
