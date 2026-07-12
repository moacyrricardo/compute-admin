package com.iskeru.computeadmin.auth.repository;

import com.iskeru.computeadmin.auth.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link AppUser}. Lookups by email — the sign-in
 * identity and the uniqueness key.
 *
 * <p>spec-011; email-only since spec-014.
 */
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    Optional<AppUser> findByEmail(String email);
}
