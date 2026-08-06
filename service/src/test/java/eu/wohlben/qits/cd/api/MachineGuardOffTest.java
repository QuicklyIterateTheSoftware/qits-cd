package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The shipped posture: {@code qits.auth.machine.required} defaults to false, so the intake accepts
 * the credential-free POST qits-ci sends today. This is the test that says adoption changed nothing
 * — it runs on the default profile, against the same config a deployment gets.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
class MachineGuardOffTest {

  @Test
  void theIntakeAcceptsACredentialFreeCall() {
    given()
        .contentType(ContentType.JSON)
        .body(
            """
            {"repoId":"unguarded-repo","branch":"main",
             "commitSha":"b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"}
            """)
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }
}
