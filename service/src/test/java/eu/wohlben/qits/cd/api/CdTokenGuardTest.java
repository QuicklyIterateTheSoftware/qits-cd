package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With {@code qits.cd.token} configured, {@link CdTokenFilter} guards the build-succeeded intake —
 * and ONLY the intake: it is the one cd path on the gateway's token-free allowlist, so this filter
 * is its whole write protection. The environment surface is deliberately unguarded (session policy
 * at the front door, trusted callers on qits-net) and the test pins that too, so widening the
 * guard is a conscious change rather than a drive-by. The blank-token open mode is exercised
 * implicitly by every other cd test (no token in test properties).
 *
 * <p>The filter fails open for paths it does not recognise, so these tests POST the real absolute
 * addresses: a guard that quietly stopped matching shows up here as a 2xx where a 401 must be.
 */
@QuarkusTest
@TestProfile(CdTokenGuardTest.WithToken.class)
public class CdTokenGuardTest {

  static final String TOKEN = "cd-guard-test-token";

  public static class WithToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.cd.token", TOKEN);
    }
  }

  private static final Map<String, Object> EVENT =
      Map.of("repoId", "some-repo", "branch", "main", "commitSha", "1".repeat(40));

  private static Map<String, Object> environment(String name) {
    return Map.of("name", name, "applications", List.of());
  }

  @Test
  public void intakeWithoutTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(401);
  }

  @Test
  public void intakeWithWrongTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .header("X-CD-Token", "not-the-token")
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(401);
  }

  @Test
  public void intakeWithTokenIsAccepted() {
    // 202 — accepted; no environment listens to the branch, so nothing deploys.
    given()
        .contentType(ContentType.JSON)
        .header("X-CD-Token", TOKEN)
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  @Test
  public void theEnvironmentSurfaceIsNotTokenGuarded() {
    // Deliberate: /cd/api/environments is not on the gateway's public allowlist, so the front door
    // already demands a session for it, and on qits-net the callers are trusted. The intake is the
    // only path whose write protection is this token.
    given()
        .contentType(ContentType.JSON)
        .body(environment("guard-env-open"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201);

    given().when().delete("/cd/api/environments/no-such-environment").then().statusCode(404);
  }

  @Test
  public void readsAreNotTokenGuarded() {
    given().when().get("/cd/api/environments").then().statusCode(200);
  }
}
