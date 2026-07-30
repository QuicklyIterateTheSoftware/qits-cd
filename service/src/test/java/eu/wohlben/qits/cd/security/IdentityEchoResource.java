package eu.wohlben.qits.cd.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Test-only: reports what the request resolved to, so the header contract can be asserted directly.
 * Served under {@code /cd/api/test-identity} — the suite inherits {@code quarkus.rest.path=/cd/api}
 * from the application's own config, so the prefix the gateway routes on is exercised by every
 * test.
 *
 * <p>Hidden from the OpenAPI document: {@code OpenApiSchemaExportTest} generates {@code
 * docs/openapi.yml} from a running {@code @QuarkusTest}, which indexes the test classpath too.
 */
@Path("/test-identity")
@Produces(MediaType.APPLICATION_JSON)
public class IdentityEchoResource {

  @Inject SecurityIdentity identity;

  public record Identity(boolean anonymous, String principal, Set<String> roles) {}

  @GET
  @Operation(hidden = true)
  public Identity get() {
    return new Identity(
        identity.isAnonymous(),
        identity.getPrincipal() == null ? null : identity.getPrincipal().getName(),
        identity.getRoles());
  }
}
