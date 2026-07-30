package eu.wohlben.qits.cd.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * The deployed posture (dev-user fallback blanked): the gateway-injected header is the identity,
 * and its absence is anonymous rather than denied. Every assertion is about <em>who the request
 * is</em>, never a status code — this service has no authorization policy and must not grow one by
 * accident of a test expecting a 401.
 */
@QuarkusTest
@TestProfile(NoDevUserProfile.class)
class ForwardAuthTest {

  @Test
  void theGatewayInjectedHeaderEstablishesTheIdentity() {
    given()
        .header("X-Qits-User", "alice")
        .when()
        .get("/cd/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo("alice"));
  }

  @Test
  void noHeaderIsAnonymousAndStillServed() {
    // Anonymous is "no name for the audit row", not a security state — the request proceeds.
    given()
        .when()
        .get("/cd/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void aBlankHeaderIsAnonymousNotAnEmptyPrincipal() {
    given()
        .header("X-Qits-User", "  ")
        .when()
        .get("/cd/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(true));
  }

  @Test
  void theIdentityCarriesNoRoles() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Groups", "admin")
        .when()
        .get("/cd/api/test-identity")
        .then()
        .statusCode(200)
        .body("principal", equalTo("alice"))
        .body("roles", empty());
  }
}
