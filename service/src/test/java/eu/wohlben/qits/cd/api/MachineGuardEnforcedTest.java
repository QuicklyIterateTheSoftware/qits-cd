package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The build-succeeded intake with the gate on — the posture a deployment reaches by setting {@code
 * QITS_AUTH_MACHINE_REQUIRED=true} once qits-idp is up.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in {@link
 * MachineGuardEnforcedProfile}. So these tests fail if the OIDC configuration in
 * application.properties is wrong, not only if the guard is missing.
 */
@QuarkusTest
@TestProfile(MachineGuardEnforcedProfile.class)
class MachineGuardEnforcedTest {

  private static final String EVENT =
      """
      {"repoId":"guarded-repo","branch":"main","commitSha":"a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"}
      """;

  @Test
  void aTokenMintedForThisServiceIsAccepted() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-cd"))
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        // 202 and nothing deploys: no environment listens to this branch, which is the intake's
        // normal answer. What is asserted is that the guard let the caller through.
        .statusCode(202);
  }

  @Test
  void aCallWithNoTokenIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        // MachineAuth.require() throws UnauthorizedException; Quarkus REST maps it. This is the
        // exact call qits-ci makes today, and it stops working the moment the gate is on — which
        // is why the sender has to be sending before a deployment flips it.
        .statusCode(401);
  }

  @Test
  void aTokenMintedForAnotherServiceIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token("qits-ci", "qits-artifacts"))
        .body(EVENT)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        // Refused by quarkus.oidc.token.audience before the guard ever sees the identity, so the
        // answer is a 401 challenge rather than MachineAuth's 403. Both doors are shut; this test
        // pins which one shuts first, because that is what a caller debugging a 401 will read.
        .statusCode(401);
  }

  @Test
  void theEnvironmentSurfaceStaysOpenToUsers() {
    // The other half of the rule. Enforcement is per call site, so turning the gate on must not
    // close a path a person reaches through the gateway's session — this one carries no guard and
    // answers a caller with no bearer at all.
    given().when().get("/cd/api/environments").then().statusCode(200);
  }
}
