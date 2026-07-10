package com.iskeru.computeadmin.auth.service;

/**
 * Thrown when a pairing user code does not resolve to a request. Maps to
 * <strong>HTTP 404</strong>.
 *
 * <p>spec-011.
 */
public class PairingNotFoundException extends RuntimeException {

    public PairingNotFoundException(String userCode) {
        super("Pairing request not found: " + userCode);
    }
}
