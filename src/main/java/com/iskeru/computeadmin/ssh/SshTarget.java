package com.iskeru.computeadmin.ssh;

/**
 * Where an {@link SshExecutor} should connect: a host, port, and login user. The
 * app keypair (never a per-target credential) authenticates.
 *
 * <p>spec-003.
 */
public record SshTarget(String host, int port, String loginUser) {
}
