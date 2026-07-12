package com.iskeru.computeadmin.auth.service;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.model.PairingRequest;
import com.iskeru.computeadmin.auth.model.PairingStatus;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.repository.PairingRequestRepository;
import com.iskeru.computeadmin.common.CurrentUser;
import com.iskeru.computeadmin.common.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP self-setup pairing (RFC 8628-style). The client {@link #begin}s a request
 * and {@link #poll}s it; a signed-in human {@link #approve}s or {@link #deny}s it
 * in the UI. Approval mints a {@link com.iskeru.computeadmin.auth.model.PersonalToken}
 * bound to that human; the very next poll returns the plaintext once, then the
 * request is {@link PairingStatus#CONSUMED}.
 *
 * <p>The minted plaintext never touches the DB (only its hash is stored on the
 * token). It is held in memory between approval and the single poll that drains
 * it — safe on this single local instance (see spec-011 Known Gaps). Polls that
 * arrive faster than the advertised interval get {@link PollState#SLOW_DOWN}.
 *
 * <p>spec-011.
 */
@Service
public class PairingService {

    /** RFC 8628-shaped result of {@link #begin}. */
    public record BeginResult(String deviceCode, String userCode, String verificationUrl,
                              long expiresIn, long interval) {
    }

    /** What a poll observed. {@code token} is non-null only for {@link PollState#APPROVED}. */
    public record PollResult(PollState state, String token) {
        static PollResult of(PollState state) {
            return new PollResult(state, null);
        }
    }

    public enum PollState {PENDING, SLOW_DOWN, DENIED, EXPIRED, APPROVED}

    /** Plaintext token minted at approval, keyed by pairing id, drained by poll. */
    private final Map<String, String> pendingPlaintext = new ConcurrentHashMap<>();
    /** Last poll instant per pairing id, for interval rate-limiting. */
    private final Map<String, Instant> lastPollAt = new ConcurrentHashMap<>();

    private final PairingRequestRepository requests;
    private final AppUserRepository users;
    private final PersonalTokenService tokenService;
    private final String baseUrl;
    private final Duration ttl;
    private final long intervalSeconds;

    public PairingService(PairingRequestRepository requests,
                          AppUserRepository users,
                          PersonalTokenService tokenService,
                          @Value("${ca.auth.base-url:http://localhost:8080}") String baseUrl,
                          @Value("${ca.auth.pairing.ttl-seconds:600}") long ttlSeconds,
                          @Value("${ca.auth.pairing.interval-seconds:5}") long intervalSeconds) {
        this.requests = requests;
        this.users = users;
        this.tokenService = tokenService;
        this.baseUrl = baseUrl;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.intervalSeconds = intervalSeconds;
    }

    @Transactional
    public BeginResult begin() {
        String deviceCode = Tokens.randomSecret();
        PairingRequest request = new PairingRequest();
        request.setDeviceCodeHash(Tokens.sha256Hex(deviceCode));
        request.setUserCode(Tokens.userCode());
        request.setStatus(PairingStatus.PENDING);
        request.setExpiresAt(Instant.now().plus(ttl));
        requests.save(request);
        // The UI is a hash-routed SPA served at "/", so the verification URL must point at
        // the in-app route (/#/setup), not a server path (/setup) — the latter 404s.
        String verificationUrl = baseUrl + "/#/setup?user_code=" + request.getUserCode();
        return new BeginResult(deviceCode, request.getUserCode(), verificationUrl,
                ttl.toSeconds(), intervalSeconds);
    }

    @Transactional
    public PollResult poll(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return PollResult.of(PollState.EXPIRED);
        }
        PairingRequest request = requests.findByDeviceCodeHash(Tokens.sha256Hex(deviceCode)).orElse(null);
        if (request == null) {
            return PollResult.of(PollState.EXPIRED);
        }
        if (isExpired(request)) {
            request.setStatus(PairingStatus.EXPIRED);
            forget(request.getId());
            return PollResult.of(PollState.EXPIRED);
        }
        return switch (request.getStatus()) {
            case DENIED -> {
                forget(request.getId());
                yield PollResult.of(PollState.DENIED);
            }
            case EXPIRED, CONSUMED -> {
                forget(request.getId());
                yield PollResult.of(PollState.EXPIRED);
            }
            case PENDING -> pollPending(request);
            case APPROVED -> drainApproved(request);
        };
    }

    private PollResult pollPending(PairingRequest request) {
        Instant now = Instant.now();
        Instant previous = lastPollAt.put(request.getId(), now);
        if (previous != null && Duration.between(previous, now).getSeconds() < intervalSeconds) {
            return PollResult.of(PollState.SLOW_DOWN);
        }
        return PollResult.of(PollState.PENDING);
    }

    private PollResult drainApproved(PairingRequest request) {
        String plaintext = pendingPlaintext.remove(request.getId());
        request.setStatus(PairingStatus.CONSUMED);
        lastPollAt.remove(request.getId());
        // If the plaintext was already drained (should not happen while APPROVED),
        // treat as expired rather than hand back nothing.
        return plaintext == null
                ? PollResult.of(PollState.EXPIRED)
                : new PollResult(PollState.APPROVED, plaintext);
    }

    /**
     * Drop the in-memory poll/plaintext state for a finished pairing so both maps
     * stay bounded. A well-behaved client polls until it observes a terminal state,
     * and that terminal poll evicts its own entries here.
     */
    private void forget(String pairingId) {
        pendingPlaintext.remove(pairingId);
        lastPollAt.remove(pairingId);
    }

    @Transactional
    public void approve(String userCode) {
        AppUser approver = currentUser();
        PairingRequest request = requirePending(userCode);
        PersonalTokenService.CreatedToken created =
                tokenService.createFor(approver, "MCP pairing " + LocalDate.now());
        request.setStatus(PairingStatus.APPROVED);
        request.setApprovedByUserId(approver.getId());
        request.setIssuedTokenId(created.token().getId());
        pendingPlaintext.put(request.getId(), created.plaintext());
    }

    @Transactional
    public void deny(String userCode) {
        currentUser();
        PairingRequest request = requirePending(userCode);
        request.setStatus(PairingStatus.DENIED);
    }

    /** What the {@code /setup} page reads to show the request for {@code userCode}. */
    public PairingRequest getByUserCode(String userCode) {
        return requests.findByUserCode(userCode)
                .orElseThrow(() -> new PairingNotFoundException(userCode));
    }

    private PairingRequest requirePending(String userCode) {
        PairingRequest request = getByUserCode(userCode);
        if (request.getStatus() != PairingStatus.PENDING || isExpired(request)) {
            // A non-pending or expired code cannot be acted on; do not leak which.
            throw new PairingNotFoundException(userCode);
        }
        return request;
    }

    private boolean isExpired(PairingRequest request) {
        return Instant.now().isAfter(request.getExpiresAt());
    }

    private AppUser currentUser() {
        String userId = CurrentUser.require().userId();
        return users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
    }
}
