package eu.wohlben.qits.cd.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.CdSpecSource;
import eu.wohlben.qits.cd.control.DeployService;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.control.FakeSpecSource;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Derived registration and deploy resolution, now that both go through qits-serviceregistry: what a
 * green build <b>writes</b> over the wire, and what it <b>reads back</b> to decide where to deploy.
 *
 * <p>{@link CdRegistryOutageTest} is the other half — the same paths with the registry gone.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdRegistryRegistrationTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    StubRegistry.reset();
  }

  @Test
  public void aGreenBuildRegistersTheServiceAndLinksItIntoEveryMatchingTier() {
    // Two tiers on one branch is legitimate, and the link set the upsert sends has to carry both:
    // PUT replaces the whole set, so one link at a time would unlink the other.
    String one = createEnvironment("reg-one", "environment/shared");
    String two = createEnvironment("reg-two", "environment/shared");
    postBuildSucceeded("repo-reg", "environment/shared", SHA_A);
    awaitStarted(2);

    StubRegistry.Svc service = StubRegistry.service("repo-reg");
    assertEquals(CdDeploymentTarget.ENVIRONMENT, service.target());
    assertNull(service.branch(), "an environment service takes its branch from its tier");
    assertEquals(List.of(one, two), List.copyOf(service.environmentIds()));
  }

  @Test
  public void aBuildOnOneBranchKeepsTheLinksAnotherTierAlreadyHad() {
    // The regression this guards: `PUT services/{name}` replaces the link set, so a build on
    // environment/dev that sent only dev's id would silently unlink preprod.
    String dev = createEnvironment("reg-dev", "environment/reg-dev");
    String preprod = createEnvironment("reg-preprod", "environment/reg-preprod");
    postBuildSucceeded("repo-both", "environment/reg-preprod", SHA_A);
    awaitStarted(1);
    postBuildSucceeded("repo-both", "environment/reg-dev", SHA_B);
    awaitStarted(2);

    assertEquals(
        List.of(preprod, dev),
        List.copyOf(StubRegistry.service("repo-both").environmentIds()),
        "the preprod link survived the dev build");
  }

  @Test
  public void aPublicNodeIsUpsertedWithAvailableOnEnv() {
    String environmentId = createEnvironment("reg-hub", "environment/reg-hub");
    specs.script(
        "repo-reg-gw", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.ENVIRONMENT, true, null, null));
    postBuildSucceeded("repo-reg-gw", "environment/reg-hub", SHA_A);
    awaitStarted(1);

    StubRegistry.Svc service = StubRegistry.service("repo-reg-gw");
    assertTrue(service.availableOnEnv(), "the spec's availableOnEnv reaches the registry");
    assertEquals(List.of(environmentId), List.copyOf(service.environmentIds()));
  }

  @Test
  public void aSingletonIsUpsertedWithItsOwnBranchAndNoLinks() {
    // A singleton has NO links, and that absence is the model: it is implicitly linked everywhere,
    // which is what makes a new environment pick it up without anyone editing it.
    createEnvironment("reg-single", "environment/reg-single");
    specs.script(
        "repo-reg-idp",
        new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, "release", null));
    postBuildSucceeded("repo-reg-idp", "release", SHA_A);
    awaitStarted(1);

    StubRegistry.Svc service = StubRegistry.service("repo-reg-idp");
    assertEquals(CdDeploymentTarget.SINGLETON, service.target());
    assertEquals("release", service.branch());
    assertEquals(List.of(), service.environmentIds());
  }

  @Test
  public void aRepositoryThatNamesNoHealthPathGetsTheConventionOne() {
    // The debt this closes: registration had no source for the path, so every row was written null
    // and every service mounted under its own prefix failed a gate against a URL that 404s.
    createEnvironment("reg-health", "environment/reg-health");
    postBuildSucceeded("qits-observability", "environment/reg-health", SHA_A);
    awaitStarted(1);

    assertEquals(
        "/observability/q/health/ready",
        StubRegistry.service("qits-observability").healthPath(),
        "the convention is derived from the name and WRITTEN, not left for the deploy default");
    assertEquals("/observability/q/health/ready", driver.started().get(0).healthPath());
  }

  @Test
  public void theRepositorysOwnHealthPathWinsOverTheConvention() {
    // The gateway owns the root path space, so the convention would send its gate to a 404.
    createEnvironment("reg-health-gw", "environment/reg-health-gw");
    specs.script(
        "qits-gateway",
        new CdSpecSource.DeploymentSpec(
            CdDeploymentTarget.ENVIRONMENT, true, null, "/q/health/ready"));
    postBuildSucceeded("qits-gateway", "environment/reg-health-gw", SHA_A);
    awaitStarted(1);

    assertEquals("/q/health/ready", StubRegistry.service("qits-gateway").healthPath());
  }

  @Test
  public void anOperatorsHealthPathSurvivesAReRegistration() {
    // A path already in the registry is somebody's fix for a service cd could not guess. A later
    // green build that says nothing about the path must leave it alone.
    String environmentId = createEnvironment("reg-health-keep", "environment/reg-health-keep");
    StubRegistry.seedService(
        new StubRegistry.Svc(
            "qits-odd",
            CdDeploymentTarget.ENVIRONMENT,
            null,
            false,
            "/hand/placed/health",
            List.of(environmentId),
            Instant.now()));

    postBuildSucceeded("qits-odd", "environment/reg-health-keep", SHA_A);
    awaitStarted(1);

    assertEquals("/hand/placed/health", StubRegistry.service("qits-odd").healthPath());
  }

  @Test
  public void aSingletonGetsTheSameHealthPathResolution() {
    // The platform plane is not a different rule: the convention, the spec and an existing value
    // rank the same way there.
    specs.script(
        "qits-idp", new CdSpecSource.DeploymentSpec(CdDeploymentTarget.SINGLETON, false, null, null));
    postBuildSucceeded("qits-idp", "main", SHA_A);
    awaitStarted(1);

    assertEquals("/idp/q/health/ready", StubRegistry.service("qits-idp").healthPath());
    assertEquals("/idp/q/health/ready", driver.started().get(0).healthPath());
  }

  @Test
  public void aBranchNoTierTracksWritesNothingAtAll() {
    // 202 and silence is the normal answer for a green build on a branch without an environment,
    // and it must not leave a service row behind that later looks like a registration.
    createEnvironment("reg-quiet", "environment/reg-quiet");
    postBuildSucceeded("repo-quiet", "main", SHA_A);
    awaitWorkerIdle();

    assertNull(StubRegistry.service("repo-quiet"), "nothing was upserted: " + StubRegistry.calls());
    assertEquals(List.of(), driver.started());
  }

  @Test
  public void anUnreadableSpecResolvesFromTheRegistrysOwnLinks() {
    // The spec read failed, so cd falls back to where the registry says this service is linked —
    // and records the failure there rather than guessing a topology.
    String environmentId = createEnvironment("reg-fallback", "environment/reg-fallback");
    StubRegistry.seedService(
        new StubRegistry.Svc(
            "repo-fallback",
            CdDeploymentTarget.ENVIRONMENT,
            null,
            false,
            null,
            List.of(environmentId),
            Instant.now()));
    specs.scriptFailure("repo-fallback", "the git host answered 500");

    postBuildSucceeded("repo-fallback", "environment/reg-fallback", SHA_A);
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 1);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("the git host answered 500"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    assertEquals(List.of(), driver.pulledRefs(), "cd never guesses a topology");
  }

  // --- helpers ----------------------------------------------------------------------------------

  private String createEnvironment(String name, String branch) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "branch", branch))
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
        .body(Map.of("runId", "run-reg", "repoId", repoId, "branch", branch, "commitSha", sha))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);
  }

  private void awaitStarted(int count) {
    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.started().size() < count && System.currentTimeMillis() < deadline) {
      sleep();
    }
    assertEquals(count, driver.started().size(), "started containers");
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
      if (deployments.size() == count
          && deployments.stream()
              .noneMatch(d -> "QUEUED".equals(d.get("status")) || "STARTING".equals(d.get("status")))) {
        return deployments;
      }
      sleep();
    }
    return fail("deployments of " + environmentId + " did not settle to " + count);
  }

  private void awaitWorkerIdle() {
    try {
      deployService.awaitIdle();
    } catch (Exception e) {
      throw new IllegalStateException("the deploy worker did not drain", e);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(50);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
