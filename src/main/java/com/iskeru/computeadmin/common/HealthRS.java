package com.iskeru.computeadmin.common;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Health resource. Establishes the "resources return DTO records" convention and
 * proves the RESTEasy-beside-Spring-Boot seam works end to end.
 *
 * <p>spec-001.
 */
@Component
@Path("/health")
@Produces(MediaType.APPLICATION_JSON)
public class HealthRS {

    private final String version;

    public HealthRS(@Value("${ca.version:dev}") String version) {
        this.version = version;
    }

    @GET
    public CommonDtos.Health health() {
        return new CommonDtos.Health("ok", version);
    }
}
