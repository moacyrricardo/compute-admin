package com.iskeru.computeadmin.auth.api;

import com.iskeru.computeadmin.common.CurrentUser;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JAX-RS request filter bound to {@link Secured} resources. {@code JwtScopeFilter}
 * has already run on the servlet thread and bound the {@link
 * com.iskeru.computeadmin.common.AuthContext} iff a valid JWT was present; this
 * filter simply refuses the request with 401 when nothing is bound. It never
 * validates the JWT itself — that stays in one place.
 *
 * <p>spec-011.
 */
@Provider
@Secured
@Component
public class AuthFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (CurrentUser.optional().isEmpty()) {
            requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "unauthorized"))
                    .build());
        }
    }
}
