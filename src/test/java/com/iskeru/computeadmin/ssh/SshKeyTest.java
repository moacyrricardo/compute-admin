package com.iskeru.computeadmin.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App keypair lifecycle (spec-003): generated on first boot at the configured
 * path, exposed as an OpenSSH public key plus SHA-256 fingerprint, and reused
 * (not regenerated) on the next boot from the same path.
 *
 * <p>spec-003.
 */
class SshKeyTest {

    @Test
    void init_OnFirstBoot_GeneratesAndExposesTheKey(@TempDir Path dir) {
        Path keyPath = dir.resolve("id_ed25519");
        KeyService service = new KeyService(keyPath.toString());

        service.init();

        assertThat(Files.exists(keyPath)).isTrue();
        assertThat(service.publicKeyOpenSsh()).startsWith("ssh-ed25519 ");
        assertThat(service.fingerprint()).startsWith("SHA256:");
        assertThat(service.keyPair()).isNotNull();
    }

    @Test
    void init_OnSecondBoot_ReusesTheSameKey(@TempDir Path dir) {
        Path keyPath = dir.resolve("id_ed25519");

        KeyService first = new KeyService(keyPath.toString());
        first.init();

        KeyService second = new KeyService(keyPath.toString());
        second.init();

        assertThat(second.publicKeyOpenSsh()).isEqualTo(first.publicKeyOpenSsh());
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }
}
