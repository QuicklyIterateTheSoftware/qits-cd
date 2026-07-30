package eu.wohlben.qits.cd.security;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Completes {@link ForwardAuthMechanism}'s trusted request into a {@link SecurityIdentity}: the
 * principal is the header-supplied username, and that is all.
 *
 * <p><b>No roles, deliberately</b> — authorization is a single global check performed at
 * qits-gateway, which emits no groups header. No code in this service makes a role decision, so
 * roles here would be a security control that decides nothing.
 */
@ApplicationScoped
public class ForwardAuthIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

  @Override
  public Class<TrustedAuthenticationRequest> getRequestType() {
    return TrustedAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TrustedAuthenticationRequest request, AuthenticationRequestContext context) {
    return Uni.createFrom()
        .item(
            QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.getPrincipal()))
                .build());
  }
}
