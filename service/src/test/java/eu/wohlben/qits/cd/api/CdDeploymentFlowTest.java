package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-1", "repoId", repoId, "branch", branch, "commitSha", sha))
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
