package com.iskeru.computeadmin.config;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.springframework.stereotype.Component;

/**
 * JAX-RS application root. All {@code *RS} resources are served under {@code /api}
 * by the RESTEasy dispatcher (not Spring MVC).
 *
 * <p>spec-001.
 */
@Component
@ApplicationPath("/api")
public class JaxRsApplication extends Application {
}
