package com.iskeru.computeadmin.ssh.api;

/**
 * DTO records for the {@code ssh} REST surface.
 *
 * <p>spec-003.
 */
public final class SshDtos {

    private SshDtos() {
    }

    /** The app's public key (to install on targets) and its fingerprint. */
    public record PublicKey(String publicKey, String fingerprint) {
    }
}
