package eu.wohlben.qits.cd.registry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.DeployService;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.control.FakeSpecSource;
import eu.wohlben.qits.cd.control.RegistryExport;
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
 * What an unreachable qits-serviceregistry does to cd, which is the whole of how far the dependency
 * reaches.
 *
 * <p>Three claims, and each is a decision rather than an accident:
 *
 * <ul>
 *   <li>a build that arrives during an outage is a <b>recorded FAILED deployment naming the cause</b>
 *       in every tier this application is known to deploy into — the posture an unreadable spec
 *       already had — and a repository with no history gets nothing, the "202 and nothing happened"
 *       an unknown repository has always had;
 *   <li>the <b>startup path never touches the registry</b>: the sweep works and the export declines
 *       quietly, so the process boots clean while it is down;
 *   <li>{@code GET /cd/api/pins} keeps answering, because qits-artifacts' image GC reads it
 *       fail-closed and a registry outage must not stop garbage collection across the platform.
 * </ul>
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdRegistryOutageTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;
  @Inject DeployService deployService;
  @Inject RegistryExport export;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
    StubRegistry.reset();
  }

  @Test
  public void aBuildDuringAnOutageFailsWhereTheApplicationLastDeployed() {
    String environmentId = createEnvironment("outage-tier");
    postBuildSucceeded("repo-outage", "environment/outage-tier", SHA_A);
    awaitDeployments(environmentId, 1);

    StubRegistry.scriptOffline();
    postBuildSucceeded("repo-outage", "environment/outage-tier", SHA_B);
    awaitWorkerIdle();
    // The listing is itself a proxied read, so the registry comes back before the rows are read —
    // what is under test is what cd wrote while it was down.
    StubRegistry.scriptOnline();
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);

    assertEquals("FAILED", deployments.get(0).get("status"));
    assertEquals(SHA_B, deployments.get(0).get("commitSha"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("service registry unreachable"),
        "the cause is on the row: " + deployments.get(0).get("detail"));
    // The previous deployment is untouched and still serving: nothing was pulled or started on a
    // guess about where this build belongs.
    assertEquals("ACTIVE", deployments.get(1).get("status"));
    assertEquals(1, driver.started().size(), "the failed build started nothing new");
  }

  @Test
  public void aBuildDuringAnOutageForAnUnknownRepositoryRecordsNothing() {
    StubRegistry.scriptOffline();
    postBuildSucceeded("repo-outage-unknown", "main", SHA_A);
    awaitWorkerIdle();

    // No row exists to fail, so there is nothing to say — exactly what an unknown repository has
    // always got. A row invented here would be a deployment nobody asked for.
    assertEquals(
        List.of(),
        pins().stream()
            .filter(pin -> "repo-outage-unknown".equals(pin.get("applicationName")))
            .toList());
    assertEquals(List.of(), driver.started());
  }

  @Test
  public void aRegistryThatAnswersAnErrorIsTheSameFailure() {
    // Reachable and refusing is not better than absent: cd still does not know where this belongs.
    String environmentId = createEnvironment("outage-500");
    postBuildSucceeded("repo-500", "environment/outage-500", SHA_A);
    awaitDeployments(environmentId, 1);

    StubRegistry.scriptStatus(500);
    postBuildSucceeded("repo-500", "environment/outage-500", SHA_B);
    awaitWorkerIdle();
    StubRegistry.scriptOnline();
    List<Map<String, Object>> deployments = awaitDeployments(environmentId, 2);
    assertEquals("FAILED", deployments.get(0).get("status"));
    assertTrue(
        ((String) deployments.get(0).get("detail")).contains("service registry unreachable"),
        deployments.get(0).get("detail").toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void theStartupPathAndThePinsSurviveTheOutage() {
    // The two things a boot does — the in-flight sweep and the one-time export — plus the read
    // qits-artifacts depends on. None of them may need the registry.
    String environmentId = createEnvironment("outage-boot");
    postBuildSucceeded("repo-boot", "environment/outage-boot", SHA_A);
    awaitDeployments(environmentId, 1);

    StubRegistry.scriptOffline();

    // The export is the one startup step that talks to the registry at all, and it declines rather
    // than throwing. The other one, the in-flight sweep, reads nothing but cd's own columns —
    // CdSweepAdoptionTest drives it directly, from inside its package.
    assertFalse(export.exportOnce(), "an unreachable registry declines the export, never fatally");

    List<String> shas =
        pins().stream()
            .filter(pin -> "repo-boot".equals(pin.get("applicationName")))
            .map(pin -> (List<String>) pin.get("shas"))
            .findFirst()
            .orElseGet(() -> fail("the pins must answer while the registry is down: " + pins()));
    assertEquals(List.of(SHA_A), shas);
  }

  // --- helpers ----------------------------------------------------------------------------------

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

  private void postBuildSucceeded(String repoId, String branch, String sha) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("runId", "run-outage", "repoId", repoId, "branch", branch, "commitSha", sha))
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
