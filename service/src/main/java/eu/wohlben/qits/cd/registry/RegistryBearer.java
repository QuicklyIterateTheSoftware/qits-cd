package eu.wohlben.qits.cd.registry;

import eu.wohlben.qits.cd.error.RegistryException;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The token qits-cd presents when it writes to qits-serviceregistry — {@code
 * aud=qits-serviceregistry}, minted by qits-idp against this service's own client credentials.
 * qits-ci's {@code CdBearer} is the same class pointed the other way, and this is deliberately its
 * copy rather than an abstraction: it is fifteen lines and the two services share no jar.
 *
 * <p>The registry guards its <b>writes</b> and the live platform runs with the gate on, so an
 * environment create, a PATCH, a delete and every derived-registration upsert need one. Reads are
 * open; the bearer is attached to all of them anyway, because one code path is fewer things to get
 * wrong than two.
 *
 * <p><b>Absent unless a deployment configures it.</b> {@code quarkus.oidc-client.client-enabled} is
 * shipped {@code false}, so the extension builds a disabled client, the process boots with no secret
 * and nothing is ever dialled; {@link #header()} answers empty and the calls go bare, exactly as
 * they do on a clone-alone {@code mvn verify} and against a registry whose own gate is off. One
 * switch, read by the extension and by this class, so a deployment cannot half-enable it.
 *
 * <p><b>Blocking, and cached.</b> The callers are the deploy worker and REST worker threads, neither
 * of which is an event loop, so this awaits rather than returning a {@code Uni}. {@link TokensHelper}
 * is what makes it one fetch rather than one per call: it holds the token until it expires and
 * refreshes it in the background, so a restarted idp pauses new issuance and nothing else.
 */
@ApplicationScoped
public class RegistryBearer {

  /**
   * The single switch, read from the extension's own key rather than shadowed by one of ours.
   * Deliberately required — a deployment that deletes the shipped line fails to start instead of
   * quietly dropping the credential off every registry write.
   */
  @ConfigProperty(name = "quarkus.oidc-client.client-enabled")
  boolean enabled;

  @ConfigProperty(name = "qits.cd.registry-timeout-seconds")
  long timeoutSeconds;

  @Inject OidcClient oidcClient;

  private final TokensHelper tokens = new TokensHelper();

  /**
   * The {@code Authorization} value to send, or empty when no client credentials are configured.
   *
   * @throws RegistryException when a client IS configured and the token could not be minted — the
   *     same posture an unreachable registry has, because it is the same outcome: the write cannot
   *     be made, and a bare call to a guarded write would be a 401 nobody could explain.
   */
  public Optional<String> header() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.of(
          "Bearer "
              + tokens
                  .getTokens(oidcClient)
                  .await()
                  .atMost(Duration.ofSeconds(timeoutSeconds))
                  .getAccessToken());
    } catch (RuntimeException e) {
      throw new RegistryException(
          "could not mint a qits-serviceregistry token at qits-idp: " + e, e);
    }
  }
}
