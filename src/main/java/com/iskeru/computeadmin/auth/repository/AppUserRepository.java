package com.iskeru.computeadmin.auth.repository;

import com.iskeru.computeadmin.auth.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data repository for {@link AppUser}. Lookups by Google subject (login
 * find-or-create) and email.
 *
 * <p>spec-011.
 */
public interface AppUserRepository extends JpaRepository<AppUser, String> {

    Optional<AppUser> findByGoogleSub(String googleSub);

    Optional<AppUser> findByEmail(String email);
}
