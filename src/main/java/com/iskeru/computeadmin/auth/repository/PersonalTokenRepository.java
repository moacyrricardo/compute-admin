package com.iskeru.computeadmin.auth.repository;

import com.iskeru.computeadmin.auth.model.PersonalToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link PersonalToken}. The MCP filter authenticates
 * by hash (rejecting revoked); the token UI lists a user's tokens.
 *
 * <p>spec-011.
 */
public interface PersonalTokenRepository extends JpaRepository<PersonalToken, String> {

    Optional<PersonalToken> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    List<PersonalToken> findByOwnerId(String ownerId);

    Optional<PersonalToken> findByIdAndOwnerId(String id, String ownerId);
}
