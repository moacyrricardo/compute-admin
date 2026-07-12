package com.iskeru.computeadmin.ssh;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression for ed25519 client authentication (the core bug: MINA had
 * no EdDSA provider at runtime, so every target authenticated with the app key
 * failed and machines showed UNREACHABLE).
 *
 * <p>Starts an <strong>embedded</strong> MINA {@link SshServer} on a loopback
 * ephemeral port that accepts <strong>exactly</strong> the app's public key
 * ({@link KeyService#keyPair()}), then drives a command through the REAL
 * {@link MinaSshExecutor} (default profile, not {@code localssh}) and asserts it
 * authenticates, runs, and returns exit 0 with the expected stdout.
 *
 * <p>No Docker, no fixed port. This exercises the exact ed25519 client-auth path
 * that was broken: it fails before the dependency/{@link KeyService} fix and passes
 * after.
 */
class MinaSshExecutorRealSshTest {

    private static final String MARKER = "IT-OK";

    private SshServer server;

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void realMinaExecutor_AuthenticatesWithAppEd25519Key_AndRunsCommand(@TempDir Path dir) throws Exception {
        KeyService keyService = new KeyService(dir.resolve("id_ed25519").toString());
        keyService.init();
        PublicKey appPublicKey = keyService.keyPair().getPublic();

        int port = startServerAcceptingOnly(appPublicKey, dir.resolve("hostkey.ser"));

        MinaSshExecutor executor = new MinaSshExecutor(keyService, 10, 30);
        try {
            ExecResult result = executor.exec(
                    new SshTarget(InetAddress.getLoopbackAddress().getHostAddress(), port, "admin"),
                    List.of("echo", MARKER),
                    false);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains(MARKER);
        } finally {
            executor.shutdown();
        }
    }

    /** Boots an embedded sshd on an ephemeral loopback port accepting only {@code allowed}. */
    private int startServerAcceptingOnly(PublicKey allowed, Path hostKeyPath) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost(InetAddress.getLoopbackAddress().getHostAddress());
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));
        server.setPublickeyAuthenticator(
                (username, key, session) -> KeyUtils.compareKeys(key, allowed));
        server.setCommandFactory(new FixedOutputCommandFactory());
        server.start();
        return server.getPort();
    }

    /** Ignores the command line and emits a fixed marker with exit 0 — enough to prove the channel ran. */
    private static final class FixedOutputCommandFactory implements CommandFactory {
        @Override
        public Command createCommand(ChannelSession channel, String command) {
            return new FixedOutputCommand();
        }
    }

    private static final class FixedOutputCommand implements Command {
        private OutputStream out;
        private ExitCallback callback;

        @Override
        public void setInputStream(InputStream in) {
            // no stdin consumed
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            // no stderr produced
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.write((MARKER + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            callback.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
            // nothing to release
        }
    }
}
