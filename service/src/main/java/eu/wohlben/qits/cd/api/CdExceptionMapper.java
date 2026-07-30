package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.error.CdException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps cd's framework-free {@link CdException}s (carrying a status code) to HTTP responses — kept
 * here in {@code service} because the cd module carries no JAX-RS.
 */
@Provider
public class CdExceptionMapper implements ExceptionMapper<CdException> {

  @Override
  public Response toResponse(CdException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
