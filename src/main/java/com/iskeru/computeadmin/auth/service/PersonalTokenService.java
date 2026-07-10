package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.model.PersonalToken;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.repository.PersonalTokenRepository;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Per-user MCP personal tokens. {@link #create} returns the plaintext
 * <strong>once</strong> and stores only its hash; {@link #list} and
 * {@link #revoke} are scoped to the current user. {@link #authenticate} is the
 * unbound lookup the MCP filter uses (by hash, rejecting revoked) to establish a
 * caller.
 *
 * <p>spec-011.
 */
@Service
public class PersonalTokenService {

    /** A freshly created token: the plaintext (shown once) and the stored row. */
    public record CreatedToken(String plaintext, PersonalToken token) {
    }

    private final PersonalTokenRepository tokens;
    private final AppUserRepository users;

    public PersonalTokenService(PersonalTokenRepository tokens, AppUserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Transactional
    public CreatedToken create(String label) {
        AppUser owner = currentUser();
        String plaintext = Tokens.randomSecret();
        PersonalToken token = new PersonalToken();
        token.setOwner(owner);
        token.setLabel(label == null || label.isBlank() ? "personal token" : label.trim());
        token.setTokenHash(Tokens.sha256Hex(plaintext));
        return new CreatedToken(plaintext, tokens.save(token));
    }

    /** Mints a token for {@code owner} with {@code label} — used by pairing approval. */
    @Transactional
    public CreatedToken createFor(AppUser owner, String label) {
        String plaintext = Tokens.randomSecret();
        PersonalToken token = new PersonalToken();
        token.setOwner(owner);
        token.setLabel(label);
        token.setTokenHash(Tokens.sha256Hex(plaintext));
        return new CreatedToken(plaintext, tokens.save(token));
    }

    public List<PersonalToken> list() {
        return tokens.findByOwnerId(currentUser().getId());
    }

    @Transactional
    public void revoke(String id) {
        PersonalToken token = tokens.findByIdAndOwnerId(id, currentUser().getId())
                .orElseThrow(() -> new TokenNotFoundException(id));
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
        }
    }

    /**
     * Authenticates a plaintext personal token: looks it up by hash (rejecting
     * revoked), touches {@code lastUsedAt}, and returns its owner. Runs
     * <strong>unbound</strong> — the MCP filter calls it before any scope exists.
     */
    @Transactional
    public Optional<AppUser> authenticate(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return Optional.empty();
        }
        return tokens.findByTokenHashAndRevokedAtIsNull(Tokens.sha256Hex(plaintext))
                .map(token -> {
                    token.setLastUsedAt(Instant.now());
                    AppUser owner = token.getOwner();
                    // Force the lazy owner proxy to initialize inside this
                    // transaction so the filter can read it after the session closes.
                    owner.getEmail();
                    return owner;
                });
    }

    private AppUser currentUser() {
        String userId = CurrentUser.require().userId();
        return users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }
}
