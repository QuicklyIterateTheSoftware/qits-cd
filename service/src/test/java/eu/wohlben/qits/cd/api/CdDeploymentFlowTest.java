package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The deployment loop end to end, against {@link FakeDeploymentDriver}: intake → queued deployment
 * → pull → start → health gate → cutover, and each of the recorded failure shapes. The boundary
 * starts at the build-succeeded POST, not at a CI run — what qits-ci sends and when belongs to that
 * repo's tests (the CiPipelineBoundaryTest stance).
 *
 * <p>Deployments execute on cd's worker, so the tests poll the read surface to a deadline rather
 * than reaching into the service — the same way a caller experiences the API.
 */
@QuarkusTest
public class CdDeploymentFlowTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  @Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  private String createEnvironment(String name, String repoId, String appName) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "applications", List.of(Map.of("repoId", repoId, "name", appName))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  private void postBuildSucceeded(String repoId, String branch, String sha) {
    postBuildSucceeded("run-1", repoId, branch, sha);
  }

  private void postBuildSucceeded(String runId, String repoId, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", runId, "repoId", repoId, "branch", branch, "commitSha", sha))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  private List<Map<String, Object>> awaitDeployments(String environmentId, int count) {
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
          deployments.size() == count
              && deployments.stream()
                  .noneMatch(
                      d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")));
      if (settled) {
        return deployments;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  @Test
  public void aGreenBuildOnTheListenedBranchDeploys() {
    String environmentId = createEnvironment("flow-green", "repo-green", "app-green");
    postBuildSucceeded("repo-green", "epic/flow-green", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    Map<String, Object> deployment = deployments.get(0);
    assertEquals("ACTIVE", deployment.get("status"));
    assertEquals(SHA_A, deployment.get("commitSha"));
    assertEquals("app-green", deployment.get("applicationName"));
    // The run that caused it, straight from the intake and out again on the read surface — this is
    // the whole deployment -> /ci/runs/<runId> click-through.
    assertEquals("run-1", deployment.get("runId"));
    String containerName = (String) deployment.get("containerName");
    assertTrue(
        containerName.startsWith("qits-cd-flow-green-app-green-"),
        "container is named after environment, application and deployment: " + containerName);

    // The image reference is DERIVED — the convention is the contract under test.
    assertEquals(List.of("qits-artifacts:8080/qits/app-green:" + SHA_A), driver.pulledRefs());
    DeploymentDriver.StartSpec spec = driver.started().get(0);
    assertEquals("qits-env-flow-green", spec.network());
    assertEquals("app-green", spec.applicationName());
    // No healthPath named at creation: the shipped default reaches the driver.
    assertEquals("/q/health/ready", spec.healthPath());
    // Nothing was decommissioned — there was nothing before.
    assertEquals(List.of(), driver.removedContainers());
  }

  @Test
  public void theNextGreenBuildCutsOverAndDecommissionsThePrevious() {
    String environmentId = createEnvironment("flow-cutover", "repo-cutover", "app-cutover");
    postBuildSucceeded("repo-cutover", "epic/flow-cutover", SHA_A);
    awaitDeployments(environmentId, 1);
    String firstContainer = driver.started().get(0).containerName();

    postBuildSucceeded("repo-cutover", "epic/flow-cutover", SHA_B);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);

    // Newest-first: the sha-B deployment is ACTIVE, the sha-A one decommissioned — and only after
    // the new one passed the gate was the old container removed.
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals(SHA_B, deployments.get(0).get("commitSha"));
    assertEquals("DECOMMISSIONED", deployments.get(1).get("status"));
    assertEquals(List.of(firstContainer), driver.removedContainers());
  }

  @Test
  public void aMissingImageIsItsOwnRecordedOutcome() {
    driver.scriptPull(
        new DeploymentDriver.PullResult(
            DeploymentDriver.PullOutcome.IMAGE_MISSING, "manifest unknown"));
    String environmentId = createEnvironment("flow-noimage", "repo-noimage", "app-noimage");
    postBuildSucceeded("repo-noimage", "epic/flow-noimage", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("IMAGE_MISSING", deployments.get(0).get("status"));
    String detail = (String) deployments.get(0).get("detail");
    assertTrue(
        detail.contains("qits-artifacts:8080/qits/app-noimage:" + SHA_A),
        "the detail names the reference nothing published: " + detail);
    // Nothing was started and nothing removed — the previous state is untouched.
    assertEquals(List.of(), driver.started());
    assertEquals(List.of(), driver.removedContainers());
  }

  @Test
  public void aFailedHealthGateRemovesTheFreshContainerAndKeepsTheOldOneServing() {
    String environmentId = createEnvironment("flow-unhealthy", "repo-unhealthy", "app-unhealthy");
    postBuildSucceeded("repo-unhealthy", "epic/flow-unhealthy", SHA_A);
    awaitDeployments(environmentId, 1);
    String healthyContainer = driver.started().get(0).containerName();

    driver.scriptHealth(new DeploymentDriver.HealthResult(false, "container exited"));
    postBuildSucceeded("repo-unhealthy", "epic/flow-unhealthy", SHA_B);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);

    assertEquals("FAILED", deployments.get(0).get("status"));
    assertEquals(SHA_B, deployments.get(0).get("commitSha"));
    // The invariant: the previous deployment is still ACTIVE and its container was never removed;
    // the fresh container is the one that went.
    assertEquals("ACTIVE", deployments.get(1).get("status"));
    String freshContainer = driver.started().get(1).containerName();
    assertEquals(List.of(freshContainer), driver.removedContainers());
    assertTrue(!driver.removedContainers().contains(healthyContainer));
  }

  @Test
  public void aRefusedStartIsAFailedDeployment() {
    driver.scriptStart(new DeploymentDriver.StartResult(false, "docker: connection refused"));
    String environmentId = createEnvironment("flow-refused", "repo-refused", "app-refused");
    postBuildSucceeded("repo-refused", "epic/flow-refused", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
  }

  @Test
  public void theReplaceCutoverStopsAliasHoldersBeforeStartingAndRemovesThemAfterTheGate() {
    // The predecessor here is NOT one of cd's own rows — it is whatever holds the alias, which is
    // how the compose-seeded originals hand over to cd on their first pipeline deployment.
    String environmentId = createEnvironment("flow-replace", "repo-replace", "app-replace");
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder("c0ffee".repeat(10) + "beef", "seeded-original")));
    postBuildSucceeded("repo-replace", "epic/flow-replace", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals(List.of("seeded-original"), driver.stoppedContainers());
    assertEquals(List.of("seeded-original"), driver.removedContainers());
    assertEquals(List.of(), driver.restartedContainers());
    // The order IS the feature: stopped before the fresh start, removed only after the gate.
    List<String> calls = driver.calls();
    assertTrue(
        calls.indexOf("stop:seeded-original") < calls.indexOf("start:" + driver.started().get(0).containerName())
            && calls.indexOf("remove:seeded-original") > calls.indexOf("start:" + driver.started().get(0).containerName()),
        "stop < start < remove, got " + calls);
  }

  @Test
  public void aFailedGateRestartsWhatTheCutoverStopped() {
    String environmentId = createEnvironment("flow-rollback", "repo-rollback", "app-rollback");
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder("dead".repeat(16), "previous-app")));
    driver.scriptHealth(new DeploymentDriver.HealthResult(false, "container exited"));
    postBuildSucceeded("repo-rollback", "epic/flow-rollback", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertEquals(List.of("previous-app"), driver.stoppedContainers());
    assertEquals(List.of("previous-app"), driver.restartedContainers());
    // Removed: only the fresh container that failed its gate — never the restarted predecessor.
    assertEquals(List.of(driver.started().get(0).containerName()), driver.removedContainers());
  }

  @Test
  public void aSelfUpdateStartsTheSuccessorAndHandsArbitrationToTheReferee() {
    // Deploying the application whose alias this very instance holds: the worker must not stop
    // its own process. It starts the successor, launches the detached referee, and leaves the row
    // STARTING — the surviving instance's sweep records the outcome, not this one.
    String environmentId = createEnvironment("flow-self", "repo-self", "qits-cd");
    String selfId = "abcdef123456";
    String selfFullId = selfId + "f".repeat(52);
    driver.scriptSelfId(selfId);
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder(selfFullId, "qits-cd")));
    postBuildSucceeded("repo-self", "epic/flow-self", SHA_A);

    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.handoffs().isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertEquals(1, driver.handoffs().size(), "the referee was launched");
    DeploymentDriver.HandoffSpec handoff = driver.handoffs().get(0);
    assertEquals(selfFullId, handoff.oldContainerId());
    assertEquals(driver.started().get(0).containerName(), handoff.newContainerName());
    // Nothing stopped, nothing removed by THIS process — the referee owns retirement.
    assertEquals(List.of(), driver.stoppedContainers());
    assertEquals(List.of(), driver.removedContainers());
    // The row stays STARTING: adoption (successor) or the restart sweep (rollback) finishes it.
    Map<String, Object> row =
        given()
            .when()
            .get("/cd/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("deployments")
            .get(0);
    assertEquals("STARTING", row.get("status"));
  }

  @Test
  public void aBranchWithoutAnEnvironmentDeploysNothing() {
    String environmentId = createEnvironment("flow-other", "repo-other", "app-other");
    // Same repo, different branch; and a different repo on the listened branch.
    postBuildSucceeded("repo-other", "main", SHA_A);
    postBuildSucceeded("repo-unrelated", "epic/flow-other", SHA_A);

    // 202 either way (fire-and-forget sender), but nothing was queued or pulled.
    awaitDeployments(environmentId, 0);
    assertEquals(List.of(), driver.pulledRefs());
  }

  @Test
  public void eachDeploymentCarriesTheRunOfTheBuildThatCausedIt() {
    // Two green builds of the same application: each row names its own run, so the click-through
    // from a historical deployment reaches the build that produced THAT image, not the newest one.
    String environmentId = createEnvironment("flow-runid", "repo-runid", "app-runid");
    postBuildSucceeded("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", "repo-runid", "epic/flow-runid", SHA_A);
    awaitDeployments(environmentId, 1);
    postBuildSucceeded("b41d7e90-9a11-4c33-8f0d-77c0e13a4412", "repo-runid", "epic/flow-runid", SHA_B);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);
    assertEquals("b41d7e90-9a11-4c33-8f0d-77c0e13a4412", deployments.get(0).get("runId"));
    assertEquals(SHA_B, deployments.get(0).get("commitSha"));
    assertEquals("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", deployments.get(1).get("runId"));
    assertEquals(SHA_A, deployments.get(1).get("commitSha"));
  }

  @Test
  public void aDeploymentWithNoRunNamesNoneRatherThanInventingOne() {
    // The sender may omit runId — every deployment recorded before the column existed reads this
    // way too, and the read surface must say null rather than guess a run from the sha.
    String environmentId = createEnvironment("flow-norunid", "repo-norunid", "app-norunid");
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("repoId", "repo-norunid", "branch", "epic/flow-norunid", "commitSha", SHA_A))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertNull(deployments.get(0).get("runId"));
  }

  @Test
  public void anOversizedRunIdIsRejectedRatherThanFailingTheInsert() {
    // The column is bounded, and the sender is fire-and-forget: without the boundary check this is
    // a 500 on an insert and a deployment that silently never happens.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "runId", "r".repeat(300),
                "repoId", "repo-bigrun",
                "branch", "main",
                "commitSha", SHA_A))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(400);
  }

  @Test
  public void malformedIdentifiersAreRejectedNotQueued() {
    // The intake is attacker-reachable; a sha that could escape an image reference must never
    // reach a docker argv (400 from cd's own validation, not a queued deployment).
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "repoId", "repo-x",
                "branch", "main",
                "commitSha", "latest; docker run --privileged evil"))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(400);
  }
}
