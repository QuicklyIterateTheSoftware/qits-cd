package eu.wohlben.qits.cd.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Guards the build-succeeded intake under {@code /cd/api/events/} with a single static token — the
 * {@code CiTokenFilter} pattern exactly, and for the same single reason: that path is on the
 * gateway's token-free {@code PublicPaths} allowlist (the caller is qits-ci's {@code
 * CdBuildNotifier}, a process holding no user session), so this filter is the write protection on
 * a spelling the session policy deliberately does not cover.
 *
 * <p>The environment surface is <b>not</b> guarded here, deliberately: it is not on the gateway's
 * allowlist (so the front door already demands a session for it), and on qits-net the platform's
 * services are trusted callers. Hardening intra-network machine surfaces beyond that is a decision
 * for later, made once and platform-wide — not piecemeal in this filter.
 *
 * <p>The header is {@code X-CD-Token}. When {@code qits.cd.token} is blank (the dev/test default)
 * the guard is a no-op, keeping dev and the suites friction-free. Reads (GET) are never guarded —
 * what is deployed where must be visible without a machine token.
 */
@Provider
public class CdTokenFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-CD-Token";

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  // Optional so a blank/absent value is "no token configured" (open) — an empty String value is
  // treated as absent by SmallRye Config and would fail a plain String injection.
  @ConfigProperty(name = "qits.cd.token")
  Optional<String> configuredToken;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String token = configuredToken.map(String::trim).filter(t -> !t.isEmpty()).orElse(null);
    if (token == null) {
      return; // open in dev/test — no token configured
    }
    // getPath() is relative to the JAX-RS base (quarkus.rest.path, /cd/api); normalize any leading
    // slash. Matching the resource rather than the whole service is deliberate: the intake is the
    // guarded surface, and a future write elsewhere under /cd/api should have to opt into this
    // guard consciously. Note the filter fails OPEN for unrecognised paths — CdTokenGuardTest is
    // what stands between a renamed resource and a guard that quietly stopped matching.
    String path = requestContext.getUriInfo().getPath();
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    if (!(path.equals("events") || path.startsWith("events/"))
        || !WRITE_METHODS.contains(requestContext.getMethod())) {
      return;
    }
    if (!token.equals(requestContext.getHeaderString(TOKEN_HEADER))) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(Map.of("message", "Missing or invalid " + TOKEN_HEADER))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }
}
