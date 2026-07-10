package com.iskeru.computeadmin.config;

import com.iskeru.computeadmin.common.Actor;
import com.iskeru.computeadmin.common.CurrentActor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tests the ambient-actor seam: {@link CurrentActor#require()} fails when
 * unbound, and {@link ActorScopeFilter} binds the right {@link Actor} per path.
 *
 * <p>spec-002.
 */
class ActorScopeTest {

    @Test
    void require_WhenUnbound_ThrowsIllegalState() {
        assertThatThrownBy(CurrentActor::require)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void optional_WhenUnbound_IsEmpty() {
        assertThat(CurrentActor.optional()).isEmpty();
    }

    @Test
    void filter_WhenApiPath_BindsUiActor() throws Exception {
        assertThat(actorBoundFor("/api/health")).isEqualTo(Actor.UI);
    }

    @Test
    void filter_WhenMcpPath_BindsMcpActor() throws Exception {
        assertThat(actorBoundFor("/mcp/sse")).isEqualTo(Actor.MCP);
    }

    @Test
    void filter_WhenScopeUnwinds_LeavesActorUnbound() throws Exception {
        actorBoundFor("/mcp/sse");
        assertThat(CurrentActor.optional()).isEmpty();
    }

    /** Runs the filter for {@code uri} and returns the actor observed downstream. */
    private Actor actorBoundFor(String uri) throws Exception {
        ActorScopeFilter filter = new ActorScopeFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<Actor> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(CurrentActor.require());

        filter.doFilter(request, response, chain);
        return observed.get();
    }
}
