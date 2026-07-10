package com.iskeru.computeadmin.auth;

import com.iskeru.computeadmin.auth.model.AppUser;
import com.iskeru.computeadmin.auth.model.PairingRequest;
import com.iskeru.computeadmin.auth.model.PairingStatus;
import com.iskeru.computeadmin.auth.repository.AppUserRepository;
import com.iskeru.computeadmin.auth.repository.PairingRequestRepository;
import com.iskeru.computeadmin.auth.service.PairingService;
import com.iskeru.computeadmin.auth.service.PairingService.PollState;
import com.iskeru.computeadmin.auth.service.PersonalTokenService;
import com.iskeru.computeadmin.common.AuthContext;
import com.iskeru.computeadmin.common.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP self-setup pairing: begin/poll is pending, a UI approve mints a user-bound
 * token the next poll returns once (then CONSUMED), and deny/expiry both stop
 * issuance. Codes are single-use and pending polls are interval rate-limited.
 *
 * <p>spec-011.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({PairingService.class, PersonalTokenService.class})
class PairingServiceTest {

    @Autowired
    private PairingService pairing;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PairingRequestRepository requests;

    private AppUser human;

    @BeforeEach
    void seedUser() {
        human = new AppUser();
        human.setEmail("human@example.com");
        human.setGoogleSub("dev|human@example.com");
        human.setName("human");
        human = users.save(human);
    }

    private <R> R asHuman(Supplier<R> body) {
        return CurrentUser.runWhere(AuthContext.ui(human.getId(), human.getEmail()), body::get);
    }

    @Test
    void poll_BeforeApproval_IsPending() {
        PairingService.BeginResult begun = pairing.begin();

        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.PENDING);
    }

    @Test
    void approve_ThenPoll_ReturnsTokenOnceThenConsumed() {
        PairingService.BeginResult begun = pairing.begin();

        asHuman(() -> {
            pairing.approve(begun.userCode());
            return null;
        });

        PairingService.PollResult first = pairing.poll(begun.deviceCode());
        assertThat(first.state()).isEqualTo(PollState.APPROVED);
        assertThat(first.token()).isNotBlank();

        // The minted token is bound to the approving human.
        assertThat(pairing.getByUserCode(begun.userCode()).getStatus()).isEqualTo(PairingStatus.CONSUMED);
        assertThat(pairing.getByUserCode(begun.userCode()).getApprovedByUserId()).isEqualTo(human.getId());

        // Single-use: a second poll no longer yields the token.
        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.EXPIRED);
    }

    @Test
    void poll_AfterDeny_ReturnsDenied() {
        PairingService.BeginResult begun = pairing.begin();

        asHuman(() -> {
            pairing.deny(begun.userCode());
            return null;
        });

        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.DENIED);
    }

    @Test
    void poll_AfterExpiry_ReturnsExpiredAndStopsIssuance() {
        PairingService.BeginResult begun = pairing.begin();
        PairingRequest request = requests.findByUserCode(begun.userCode()).orElseThrow();
        request.setExpiresAt(Instant.now().minusSeconds(1));
        requests.saveAndFlush(request);

        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.EXPIRED);
    }

    @Test
    void poll_TwiceInQuickSuccession_IsRateLimited() {
        PairingService.BeginResult begun = pairing.begin();

        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.PENDING);
        assertThat(pairing.poll(begun.deviceCode()).state()).isEqualTo(PollState.SLOW_DOWN);
    }
}
