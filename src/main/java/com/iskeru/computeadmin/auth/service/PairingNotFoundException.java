package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.common.AppException;
import jakarta.ws.rs.core.Response;

/**
 * Thrown when a pairing user code does not resolve to a request. Maps to
 * <strong>HTTP 404</strong> {@code {"error":"pairing_not_found"}}.
 *
 * <p>spec-011; carries its own response since spec-046.
 */
public class PairingNotFoundException extends AppException {

    public PairingNotFoundException(String userCode) {
        super("Pairing request not found: " + userCode, Response.Status.NOT_FOUND, "pairing_not_found");
    }
}
