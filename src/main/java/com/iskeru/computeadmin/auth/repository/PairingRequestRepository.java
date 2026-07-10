package com.iskeru.computeadmin.auth.repository;

import com.iskeru.computeadmin.auth.model.PairingRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link PairingRequest}. The client polls by device
 * code (hashed); the UI reads/approves by the human-readable user code.
 *
 * <p>spec-011.
 */
public interface PairingRequestRepository extends JpaRepository<PairingRequest, String> {

    Optional<PairingRequest> findByDeviceCodeHash(String deviceCodeHash);

    Optional<PairingRequest> findByUserCode(String userCode);
}
