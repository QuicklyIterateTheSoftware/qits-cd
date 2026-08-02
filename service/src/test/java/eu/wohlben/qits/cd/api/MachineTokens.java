package eu.wohlben.qits.cd.api;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Set;

/**
 * The test issuer: mints the RS256 tokens qits-idp will mint in production, signed with the key
 * pair in {@code src/test/resources}. {@link MachineGuardEnforcedProfile} hands the public half to
 * quarkus-oidc, so a token made here is validated by the real extension — signature, issuer and
 * audience — rather than by a fake identity slipped past it.
 *
 * <p>The shape is the contract's: {@code iss} is the configured issuer, {@code sub} is the client
 * id, and {@code aud} is a JSON <b>array</b> of target service ids, which is what the idp always
 * emits and therefore what this service must accept.
 */
final class MachineTokens {

  static final String SIGNING_KEY = "/machine-token-signing-key.pem";
  static final String VERIFICATION_KEY = "/machine-token-verification-key.pem";

  /** The issuer qits-cd is configured against — see quarkus.oidc.auth-server-url. */
  static final String ISSUER = "http://qits-idp:8080/idp";

  /** A token from {@code clientId}, addressed to {@code audiences}, valid for five minutes. */
  static String token(String clientId, String... audiences) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject(clientId)
        .audience(Set.of(audiences))
        .expiresIn(Duration.ofMinutes(5))
        .jws()
        .sign(privateKey());
  }

  /** The PEM's contents, for a caller that needs the key material rather than a token. */
  static String pem(String resource) {
    try (var in = MachineTokens.class.getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Missing test key " + resource, e);
    }
  }

  private static PrivateKey privateKey() {
    try {
      return KeyUtils.decodePrivateKey(pem(SIGNING_KEY));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the test signing key", e);
    }
  }

  private MachineTokens() {}
}
