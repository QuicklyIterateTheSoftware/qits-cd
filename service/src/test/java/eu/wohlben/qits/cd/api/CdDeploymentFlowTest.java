package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.CdSpecSource;
import eu.wohlben.qits.cd.control.DeployService;
import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.control.FakeSpecSource;
import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
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
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject CdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
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

  /**
   * Drain the worker. A build-succeeded event is handled there in one piece — spec read,
   * registration, queueing, deployment — so "nothing happened" is only assertable once the worker
   * has had the event and finished with it. No sleep: the hook queues a no-op behind the work and
   * waits on it.
   */
  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  /**
   * A singleton's deployments are unreachable through the environment-scoped listing, so a test
   * about one reads the row directly — the same thing CdSweepAdoptionTest does for the same reason.
   */
  private CdDeployment deploymentOf(String applicationId, String sha) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                deployments.listByApplication(applicationId).stream()
                    .filter(d -> sha.equals(d.commitSha))
                    .findFirst()
                    .orElseThrow(
                        () -> new AssertionError("no deployment of " + applicationId + " at " + sha)));
  }

  /** Singletons have no environment to read deployments through — wait on the driver instead. */
  private void awaitStarted(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.started().size() < count && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    assertEquals(count, driver.started().size(), "started containers");
  }

  @Test
  public void aGreenBuildOnTheListenedBranchDeploys() {
    String environmentId = createEnvironment("flow-green", "repo-green", "app-green");
    postBuildSucceeded("repo-green", "environment/flow-green", SHA_A);

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
    // The primary network is the application's OWN, not the environment's bundle: an ordinary
    // application is a spoke, and only its own containers are on it.
    assertEquals("qits-env-flow-green-app-green", spec.network());
    assertEquals("app-green", spec.applicationName());
    assertEquals(CdDeploymentTarget.ENVIRONMENT, spec.target());
    // ...and it joins the legacy network after the start, which is the transition membership that
    // keeps today's direct cross-application URLs resolving.
    assertTrue(
        driver.connections().contains("qits-net:" + containerName + ":app-green"),
        "the fresh container joins the legacy network: " + driver.connections());
    // No healthPath named at creation: the shipped default reaches the driver.
    assertEquals("/q/health/ready", spec.healthPath());
    // Nothing was decommissioned — there was nothing before.
    assertEquals(List.of(), driver.removedContainers());
  }

  @Test
  public void theNextGreenBuildCutsOverAndDecommissionsThePrevious() {
    String environmentId = createEnvironment("flow-cutover", "repo-cutover", "app-cutover");
    postBuildSucceeded("repo-cutover", "environment/flow-cutover", SHA_A);
    awaitDeployments(environmentId, 1);
    String firstContainer = driver.started().get(0).containerName();

    postBuildSucceeded("repo-cutover", "environment/flow-cutover", SHA_B);
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
    postBuildSucceeded("repo-noimage", "environment/flow-noimage", SHA_A);

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
    postBuildSucceeded("repo-unhealthy", "environment/flow-unhealthy", SHA_A);
    awaitDeployments(environmentId, 1);
    String healthyContainer = driver.started().get(0).containerName();

    driver.scriptHealth(new DeploymentDriver.HealthResult(false, "container exited"));
    postBuildSucceeded("repo-unhealthy", "environment/flow-unhealthy", SHA_B);
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
    postBuildSucceeded("repo-refused", "environment/flow-refused", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
  }

  @Test
  public void theReplaceCutoverStopsAliasHoldersBeforeStartingAndRemovesThemAfterTheGate() {
    // The predecessor here is NOT one of cd's own rows — it is whatever holds the alias, which is
    // how the compose-seeded originals hand over to cd on their first pipeline deployment.
    String environmentId = createEnvironment("flow-replace", "repo-replace", "app-replace");
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder("c0ffee".repeat(10) + "beef", "seeded-original", null)));
    postBuildSucceeded("repo-replace", "environment/flow-replace", SHA_A);

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
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder("dead".repeat(16), "previous-app", null)));
    driver.scriptHealth(new DeploymentDriver.HealthResult(false, "container exited"));
    postBuildSucceeded("repo-rollback", "environment/flow-rollback", SHA_A);

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
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder(selfFullId, "qits-cd", null)));
    postBuildSucceeded("repo-self", "environment/flow-self", SHA_A);

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
    // The successor is started through the same StartSpec as any other deployment, so it carries
    // the same identity into the same argv builder: deploying cd itself is not a second code path
    // that could miss the OTel resource attributes.
    assertEquals(SHA_A, driver.started().get(0).commitSha());
    assertEquals("flow-self", driver.started().get(0).environmentName());
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
  public void aSingletonSelfUpdateStillHandsArbitrationToTheReferee() {
    // cd itself becomes a singleton, so the handoff path has to work with the new naming and the
    // new networks — a predecessor found on the legacy network, a successor named after the plane,
    // and still no container stopped by this process.
    specs.script(
        "qits-cd", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    String selfId = "abcdef123456";
    String selfFullId = selfId + "f".repeat(52);
    driver.scriptSelfId(selfId);
    driver.scriptAliasHolders(List.of(new DeploymentDriver.Holder(selfFullId, "qits-cd", null)));
    postBuildSucceeded("qits-cd", "main", SHA_A);

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
    String successor = driver.started().get(0).containerName();
    assertEquals(successor, handoff.newContainerName());
    assertTrue(successor.startsWith("qits-cd-singleton-qits-cd-"), successor);
    assertEquals("qits-platform", driver.started().get(0).network());
    // The successor is on its networks BEFORE the referee stops the predecessor — it has to be
    // reachable the moment it passes its gate.
    List<String> calls = driver.calls();
    assertTrue(
        calls.indexOf("connect:qits-net:" + successor + ":qits-cd") < calls.indexOf("handoff:" + successor),
        "joined before the handoff: " + calls);
    // Nothing stopped, nothing removed by THIS process — the referee owns retirement.
    assertEquals(List.of(), driver.stoppedContainers());
    assertEquals(List.of(), driver.removedContainers());
  }

  @Test
  public void aBranchNoEnvironmentListensToDeploysNothing() {
    String environmentId = createEnvironment("flow-other", "repo-other", "app-other");
    postBuildSucceeded("repo-other", "main", SHA_A);

    // 202 (fire-and-forget sender), but nothing was queued or pulled.
    awaitWorkerIdle();
    awaitDeployments(environmentId, 0);
    assertEquals(List.of(), driver.pulledRefs());
  }

  @Test
  public void aRepositoryTheEnvironmentNeverHeardOfRegistersItself() {
    // Derived registration: nothing declared repo-derived anywhere, and a green build on the
    // environment's branch is the whole registration. The row is named after the repository and
    // carries the defaults its (absent) deployments.yml implies.
    String environmentId = createEnvironment("flow-derive", "repo-seeded", "app-seeded");
    postBuildSucceeded("repo-derived", "environment/flow-derive", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("ACTIVE", deployments.get(0).get("status"));
    assertEquals("repo-derived", deployments.get(0).get("applicationName"));

    Map<String, Object> registered =
        given()
            .when()
            .get("/cd/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-derived".equals(a.get("repoId")))
            .findFirst()
            .orElseThrow();
    assertEquals("ENVIRONMENT", registered.get("target"));
    assertEquals(false, registered.get("availableOnEnv"));
    assertEquals("flow-derive", registered.get("environmentName"));
    assertNull(registered.get("branch"), "an environment application takes its tier's branch");
  }

  @Test
  public void aPublicNodeJoinsTheBundleAndEveryApplicationNetworkOfItsEnvironment() {
    String environmentId = createEnvironment("flow-hub", "repo-hub-seed", "app-hub-seed");
    // One application network of this environment already exists — the hub has to end up on it.
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-flow-hub-app-hub-seed",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-hub-seed"));
    specs.script(
        "repo-gw", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.ENVIRONMENT, true, null));
    postBuildSucceeded("repo-gw", "environment/flow-hub", SHA_A);

    awaitDeployments(environmentId, 1);
    String container = driver.started().get(0).containerName();
    assertEquals("qits-env-flow-hub-repo-gw", driver.started().get(0).network());
    assertTrue(driver.started().get(0).availableOnEnv());
    assertTrue(
        driver.connections().contains("qits-env-flow-hub:" + container + ":repo-gw"),
        "the public node joins its environment's bundle: " + driver.connections());
    assertTrue(
        driver
            .connections()
            .contains("qits-env-flow-hub-app-hub-seed:" + container + ":repo-gw"),
        "and every application network of that environment: " + driver.connections());
  }

  @Test
  public void aSingletonRunsOnThePlatformNetworkAndJoinsEveryApplicationNetwork() {
    String environmentId = createEnvironment("flow-single", "repo-single-seed", "app-single-seed");
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-flow-single-app-single-seed",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-single-seed"));
    specs.script(
        "repo-idp", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    // main, not the environment's branch: a singleton is platform-plane and deploys from its own.
    postBuildSucceeded("repo-idp", "main", SHA_A);

    awaitStarted(1);
    DeploymentDriver.StartSpec spec = driver.started().get(0);
    assertEquals("qits-platform", spec.network());
    assertEquals(CdDeploymentTarget.SINGLETON, spec.target());
    assertNull(spec.environmentId(), "a singleton belongs to no tier");
    assertNull(spec.environmentName());
    assertTrue(
        spec.containerName().startsWith("qits-cd-singleton-repo-idp-"),
        "singletons are named after the plane, not an environment: " + spec.containerName());
    assertTrue(
        driver
            .connections()
            .contains("qits-env-flow-single-app-single-seed:" + spec.containerName() + ":repo-idp"),
        "a singleton joins every application network of every environment: " + driver.connections());
    assertTrue(
        driver.connections().contains("qits-net:" + spec.containerName() + ":repo-idp"),
        "and the legacy network while the transition lasts");

    // The environment it is not part of deploys nothing on this event.
    assertEquals(
        0,
        given()
            .when()
            .get("/cd/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments")
            .size());
  }

  @Test
  public void aSingletonOnAnotherBranchDeploysNothing() {
    specs.script(
        "repo-pinned",
        new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, "release"));
    postBuildSucceeded("repo-pinned", "main", SHA_A);
    postBuildSucceeded("repo-pinned", "release", SHA_B);

    awaitStarted(1);
    assertEquals(SHA_B, driver.started().get(0).commitSha());
  }

  @Test
  public void aNewApplicationNetworkGetsTheHubAndEverySingletonJoinedToIt() {
    // The reconciliation: the network did not exist a moment ago, so nobody is on it. What the
    // application needs there is the environment's public nodes and every singleton — found by
    // container label, because docker is the membership bookkeeping.
    String environmentId = createEnvironment("flow-reconcile", "repo-rec-seed", "app-rec-seed");
    driver.scriptHubContainers(
        List.of(new DeploymentDriver.Endpoint("hub-id", "qits-gateway")));
    driver.scriptSingletonContainers(
        List.of(new DeploymentDriver.Endpoint("idp-id", "qits-idp")));
    postBuildSucceeded("repo-rec", "environment/flow-reconcile", SHA_A);

    awaitDeployments(environmentId, 1);
    assertTrue(
        driver.connections().contains("qits-env-flow-reconcile-repo-rec:hub-id:qits-gateway"),
        driver.connections().toString());
    assertTrue(
        driver.connections().contains("qits-env-flow-reconcile-repo-rec:idp-id:qits-idp"),
        driver.connections().toString());
  }

  @Test
  public void anApplicationNetworkThatOutlivedAFailedDeployIsStillReconciled() {
    // The network is made before the container starts, so a deployment that failed to start leaves
    // it behind with nobody on it. The next deploy does NOT create it, and if that were the
    // reconciliation's trigger the application would stay unreachable from the gateway and from
    // every singleton for as long as no hub happened to redeploy.
    String environmentId = createEnvironment("flow-reheal", "repo-reheal-seed", "app-reheal-seed");
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-flow-reheal-repo-reheal",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "repo-reheal"));
    driver.scriptHubContainers(List.of(new DeploymentDriver.Endpoint("hub-id", "qits-gateway")));
    driver.scriptSingletonContainers(List.of(new DeploymentDriver.Endpoint("cd-id", "qits-cd")));
    postBuildSucceeded("repo-reheal", "environment/flow-reheal", SHA_A);

    awaitDeployments(environmentId, 1);
    assertTrue(
        driver.connections().contains("qits-env-flow-reheal-repo-reheal:hub-id:qits-gateway"),
        "the hub is put back on a network it should already be on: " + driver.connections());
    assertTrue(
        driver.connections().contains("qits-env-flow-reheal-repo-reheal:cd-id:qits-cd"),
        "and so is every singleton: " + driver.connections());
  }

  @Test
  public void anUnlabelledAliasHolderIsAdoptedAsThePredecessor() {
    // The migration case, stated as its own test: a container the previous cd (or the bootstrap's
    // compose) started carries no environment label at all, and it is exactly the one a deployment
    // has to replace rather than run beside.
    String environmentId = createEnvironment("flow-adopt", "repo-adopt", "repo-adopt");
    driver.scriptAliasHolders(
        List.of(new DeploymentDriver.Holder("aa".repeat(32), "seeded-original", null)));
    postBuildSucceeded("repo-adopt", "environment/flow-adopt", SHA_A);

    awaitDeployments(environmentId, 1);
    assertEquals(List.of("seeded-original"), driver.stoppedContainers());
    assertTrue(driver.removedContainers().contains("seeded-original"));
  }

  @Test
  public void anAliasHolderOfThisEnvironmentIsReplacedAndOneOfAnotherIsLeftAlone() {
    // The union search covers the legacy network, and every tier is on it — so it returns another
    // environment's copy of the same application, healthy, under the same alias. Stopping that one
    // would be a deployment of one tier reaching into another; the environment label is what keeps
    // this deployment to its own.
    String environmentId = createEnvironment("flow-scope", "repo-scope", "repo-scope");
    driver.scriptAliasHolders(
        List.of(
            new DeploymentDriver.Holder("bb".repeat(32), "mine", environmentId),
            new DeploymentDriver.Holder("cc".repeat(32), "another-tiers", "some-other-env-id")));
    postBuildSucceeded("repo-scope", "environment/flow-scope", SHA_A);

    awaitDeployments(environmentId, 1);
    assertEquals(List.of("mine"), driver.stoppedContainers(), "only this environment's copy");
    assertTrue(driver.removedContainers().contains("mine"));
    assertTrue(
        driver.stoppedContainers().stream().noneMatch("another-tiers"::equals)
            && driver.removedContainers().stream().noneMatch("another-tiers"::equals),
        "the other tier's container is untouched: " + driver.calls());
  }

  @Test
  public void aSingletonNeverTakesAnEnvironmentsContainerAsItsPredecessor() {
    // A singleton belongs to no tier, so a container that carries a tier's id is never its
    // predecessor — while an unlabelled one still is, because that is what its own live migration
    // off the legacy network depends on.
    specs.script(
        "repo-plane", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    driver.scriptAliasHolders(
        List.of(
            new DeploymentDriver.Holder("dd".repeat(32), "an-env-copy", "some-env-id"),
            new DeploymentDriver.Holder("ee".repeat(32), "the-old-unlabelled-one", null)));
    postBuildSucceeded("repo-plane", "main", SHA_A);

    awaitStarted(1);
    awaitWorkerIdle();
    assertEquals(List.of("the-old-unlabelled-one"), driver.stoppedContainers());
    assertTrue(
        driver.removedContainers().stream().noneMatch("an-env-copy"::equals),
        "the environment's container is not the platform plane's to take: " + driver.calls());
  }

  @Test
  public void aRefusedJoinFailsTheDeploymentAndPutsThePredecessorBack() {
    // The health gate curls localhost INSIDE the container, so it passes just as happily on a
    // network nobody else is on: a join cd asked for and did not get can only be caught here. The
    // rollback is the failed-gate one — the fresh container goes, the predecessor serves again.
    String environmentId = createEnvironment("flow-nojoin", "repo-nojoin", "repo-nojoin");
    driver.scriptAliasHolders(
        List.of(new DeploymentDriver.Holder("ff".repeat(32), "still-serving", environmentId)));
    driver.scriptRefusedJoin("qits-net", "Error response from daemon: network qits-net not found");
    postBuildSucceeded("repo-nojoin", "environment/flow-nojoin", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("network qits-net not found"),
        "docker's own words are on the row: " + deployments.get(0).get("detail"));
    String fresh = driver.started().get(0).containerName();
    assertTrue(driver.removedContainers().contains(fresh), "the fresh container was removed");
    assertEquals(List.of("still-serving"), driver.restartedContainers());
    // Never gated: an unreachable container has nothing to prove.
    assertEquals(List.of(), driver.awaited());
  }

  @Test
  public void thePredecessorSearchCoversTheLegacyNetworkTooDuringTheMigration() {
    // The union IS the migration: today's containers hold their alias on qits-net and on no
    // per-application network at all, so a search of the new networks alone would start a second
    // copy beside the one that is serving.
    String environmentId = createEnvironment("flow-union", "repo-union", "app-union");
    postBuildSucceeded("repo-union", "environment/flow-union", SHA_A);
    awaitDeployments(environmentId, 1);

    assertTrue(
        driver.aliasSearches().stream()
            .anyMatch(s -> s.contains("qits-env-flow-union-app-union") && s.contains("qits-net")),
        "the search covers the primary and the legacy network: " + driver.aliasSearches());
  }

  @Test
  public void declaringItselfASingletonConvertsTheEnvironmentRowsItHad() {
    // The live migration, in one test: qits-idp and qits-cd are environment applications today and
    // become singletons with the commit that adds their deployments.yml. The old rows must not sit
    // beside the new one — one repository deploys to one place.
    String environmentId = createEnvironment("flow-convert", "repo-convert", "repo-convert");
    postBuildSucceeded("repo-convert", "environment/flow-convert", SHA_A);
    awaitDeployments(environmentId, 1);

    specs.script(
        "repo-convert", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    postBuildSucceeded("repo-convert", "main", SHA_B);
    awaitStarted(2);

    List<Map<String, Object>> registered =
        given()
            .when()
            .get("/cd/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-convert".equals(a.get("repoId")))
            .toList();
    assertEquals(1, registered.size(), "one row, not two: " + registered);
    assertEquals("SINGLETON", registered.get(0).get("target"));
    assertNull(registered.get(0).get("environmentId"));
    assertEquals("main", registered.get(0).get("branch"));

    // The history moved with it: the environment it left has no deployments of it any more, and
    // the row that was serving is decommissioned rather than deleted.
    assertEquals(
        0,
        given()
            .when()
            .get("/cd/api/deployments?environmentId=" + environmentId)
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("deployments")
            .size());
  }

  @Test
  public void flippingASingletonBackToAnEnvironmentIsRefusedOnTheRecord() {
    // The conversion runs one way only. Coming back has no answer to "which environment inherits
    // the history", and the environment deployment would find the running singleton through the
    // legacy network and remove it — leaving a singleton row saying ACTIVE about nothing. So it is
    // refused, and the refusal is written where an operator looks: a FAILED row on the singleton.
    createEnvironment("flow-unflip", "repo-unflip-seed", "app-unflip-seed");
    specs.script(
        "repo-unflip", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    postBuildSucceeded("repo-unflip", "main", SHA_A);
    awaitStarted(1);

    // The file goes back to saying `environment`, on the tier's own branch this time.
    specs.script(
        "repo-unflip", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.ENVIRONMENT, false, null));
    postBuildSucceeded("repo-unflip", "environment/flow-unflip", SHA_B);
    awaitWorkerIdle();

    // Nothing was registered into the environment and nothing new was deployed.
    List<Map<String, Object>> rows =
        given()
            .when()
            .get("/cd/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-unflip".equals(a.get("repoId")))
            .toList();
    assertEquals(1, rows.size(), "still one row, still the singleton: " + rows);
    assertEquals("SINGLETON", rows.get(0).get("target"));
    assertEquals(1, driver.started().size(), "the refused build started nothing");

    // ...and the refusal is on the record, naming the flip.
    CdDeployment refused = deploymentOf(rows.get(0).get("id").toString(), SHA_B);
    assertEquals("FAILED", refused.status.name());
    assertTrue(
        refused.detail.contains("deployment_target: environment"),
        "the detail names the flip: " + refused.detail);
    assertTrue(
        refused.detail.contains("Retire the singleton deliberately"),
        "and says what to do about it: " + refused.detail);
  }

  @Test
  public void twoIdenticalEventsArrivingTogetherRegisterOneSingleton() {
    // Derived registration is a read-then-write, and a singleton row is the one shape no database
    // constraint can guard: its environment_id is null, and a composite unique index treats nulls
    // as distinct. Handling the whole event on cd's single worker is what makes the pair atomic.
    specs.script(
        "repo-once", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null));
    int senders = 8;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(senders);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<?>> sent = new java.util.ArrayList<>();
    try {
      for (int i = 0; i < senders; i++) {
        sent.add(
            pool.submit(
                () -> {
                  go.await();
                  postBuildSucceeded("repo-once", "main", SHA_A);
                  return null;
                }));
      }
      go.countDown(); // every sender is parked on the latch, so they enter the intake together
      for (java.util.concurrent.Future<?> one : sent) {
        one.get();
      }
    } catch (Exception e) {
      throw new IllegalStateException("the concurrent senders failed", e);
    } finally {
      pool.shutdownNow();
    }
    awaitWorkerIdle();

    List<Map<String, Object>> rows =
        given()
            .when()
            .get("/cd/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .<Map<String, Object>>getList("applications")
            .stream()
            .filter(a -> "repo-once".equals(a.get("repoId")))
            .toList();
    assertEquals(1, rows.size(), "one singleton row for one repository: " + rows);
    assertEquals("SINGLETON", rows.get(0).get("target"));
  }

  @Test
  public void aSpecThatCannotBeReadFailsTheDeploymentRatherThanGuessing() {
    String environmentId = createEnvironment("flow-nospec", "repo-nospec", "app-nospec");
    specs.scriptFailure("repo-nospec", "the git host answered 500");
    postBuildSucceeded("repo-nospec", "environment/flow-nospec", SHA_A);

    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("the git host answered 500"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    // Nothing was pulled and nothing started — cd never guesses a topology.
    assertEquals(List.of(), driver.pulledRefs());
    assertEquals(List.of(), driver.started());
  }

  @Test
  public void eachDeploymentCarriesTheRunOfTheBuildThatCausedIt() {
    // Two green builds of the same application: each row names its own run, so the click-through
    // from a historical deployment reaches the build that produced THAT image, not the newest one.
    String environmentId = createEnvironment("flow-runid", "repo-runid", "app-runid");
    postBuildSucceeded("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61", "repo-runid", "environment/flow-runid", SHA_A);
    awaitDeployments(environmentId, 1);
    postBuildSucceeded("b41d7e90-9a11-4c33-8f0d-77c0e13a4412", "repo-runid", "environment/flow-runid", SHA_B);

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
        .body(Map.of("repoId", "repo-norunid", "branch", "environment/flow-norunid", "commitSha", SHA_A))
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
