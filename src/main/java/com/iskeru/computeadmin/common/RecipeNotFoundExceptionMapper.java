package com.iskeru.computeadmin.common;

import com.iskeru.computeadmin.recipe.service.RecipeNotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Maps {@link RecipeNotFoundException} to a 404 JSON body {@code {"error":
 * "recipe_not_found"}}.
 *
 * <p>spec-004.
 */
@Provider
@Component
public class RecipeNotFoundExceptionMapper implements ExceptionMapper<RecipeNotFoundException> {

    @Override
    public Response toResponse(RecipeNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "recipe_not_found"))
                .build();
    }
}
