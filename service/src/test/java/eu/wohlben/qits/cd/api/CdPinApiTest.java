package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /cd/api/pins} over real deployments, driven through the intake exactly as a green
 * build drives it — the shape a garbage collector binds to, and the proof that the pins follow the
 * cutover rather than restating it.
 *
 * <p>The listing is instance-wide, so every case reads its own application's entry out of it rather
 * than asserting the whole document: the suite shares one database and other classes deploy too.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdPinApiTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);
  private static final String SHA_D = "d".repeat(40);

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
    StubRegistry.reset();
  }

  @Test
  public void theShasOfEveryEnvironmentRunningAnApplicationAreOneEntry() {
    // Two environments running one application name, each a rollback step deep: the entry carries
    // all four shas, because either environment's next restart pulls its own serving sha and either
    // rollback pulls its own predecessor.
    String staging = createEnvironment("pins-staging");
    String live = createEnvironment("pins-live");

    deploy("repo-pins", "environment/pins-staging", SHA_A, staging, 1);
    deploy("repo-pins", "environment/pins-staging", SHA_B, staging, 2);
    deploy("repo-pins", "environment/pins-live", SHA_C, live, 1);
    deploy("repo-pins", "environment/pins-live", SHA_D, live, 2);

    // Serving shas sorted, then rollback shas sorted — a union over environments has no recency to
    // order by, so the answer is stable rather than pretending to be a sequence.
    assertEquals(List.of(SHA_B, SHA_D, SHA_A, SHA_C), shasOf("repo-pins"));
  }

  @Test
  public void aFailedGateLeavesThePinsWhereItLeavesTheApplication() {
    // The anchor to the real rollback: a failed health gate removes the fresh container and
    // restarts what the cutover stopped, so the previous deployment is still ACTIVE and serving
    // (CdDeploymentFlowTest.aFailedGateRestartsWhatTheCutoverStopped). The pins say exactly that —
    // the serving sha, and no rollback target, because nothing ever served before it. The failed
    // sha is pinned by nothing: no container was created from it.
    String environmentId = createEnvironment("pins-gate");
    deploy("repo-pins-gate", "environment/pins-gate", SHA_A, environmentId, 1);

    driver.scriptHealth(new DeploymentDriver.HealthResult(false, "container exited"));
    deploy("repo-pins-gate", "environment/pins-gate", SHA_B, environmentId, 2);

    assertEquals(List.of(SHA_A), shasOf("repo-pins-gate"));
  }

  @Test
  public void aCutoverMovesThePinsExactlyOneStep() {
    // Three green builds in a row: the newest serves, the one before it is the rollback target, and
    // the oldest is pinned by nothing — one rollback step is what cd can actually perform.
    String environmentId = createEnvironment("pins-steps");
    deploy("repo-pins-steps", "environment/pins-steps", SHA_A, environmentId, 1);
    deploy("repo-pins-steps", "environment/pins-steps", SHA_B, environmentId, 2);

    assertEquals(List.of(SHA_B, SHA_A), shasOf("repo-pins-steps"));

    deploy("repo-pins-steps", "environment/pins-steps", SHA_C, environmentId, 3);

    assertEquals(List.of(SHA_C, SHA_B), shasOf("repo-pins-steps"));
  }

  @Test
  public void anApplicationThatNeverDeployedIsAbsentRatherThanEmpty() {
    // An environment created and nothing green yet: there is no serving sha, so there is no entry.
    // An empty one would read as "this name is pinned" to a collector that keeps what it is told.
    createEnvironment("pins-idle");

    assertEquals(
        List.of(),
        pins().stream()
            .filter(pin -> "repo-pins-idle".equals(pin.get("applicationName")))
            .toList());
  }

  @SuppressWarnings("unchecked")
  private List<String> shasOf(String applicationName) {
    return pins().stream()
        .filter(pin -> applicationName.equals(pin.get("applicationName")))
        .map(pin -> (List<String>) pin.get("shas"))
        .findFirst()
        .orElseGet(() -> fail("no pin for " + applicationName + " in " + pins()));
  }

  private List<Map<String, Object>> pins() {
    return given().when().get("/cd/api/pins").then().statusCode(200).extract().jsonPath().getList("pins");
  }

  private String createEnvironment(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  /** One green build, awaited to a settled row — the pins are read off finished deployments. */
  private void deploy(String repoId, String branch, String sha, String environmentId, int expected) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-pins", "repoId", repoId, "branch", branch, "commitSha", sha))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);
    long deadline = System.currentTimeMillis() + 15_000;
    while (System.currentTimeMillis() < deadline) {
      List<Map<String, Object>> deployments =
          given()
              .when()
              .get("/cd/api/deployments?environmentId=" + environmentId)
              .then()
              .statusCode(200)
              .extract()
              .jsonPath()
              .getList("deployments");
      boolean settled =
          deployments.size() == expected
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
    fail("deployments of " + environmentId + " did not settle to " + expected);
  }
}
