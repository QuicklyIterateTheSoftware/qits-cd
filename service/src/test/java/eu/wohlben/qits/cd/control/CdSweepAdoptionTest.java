package eu.wohlben.qits.cd.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import eu.wohlben.qits.cd.persistence.CdApplicationRepository;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The startup sweep's two verdicts on an in-flight row, package-local so the suite can drive
 * {@link DeployService#sweepInFlight()} without a real StartupEvent: a {@code STARTING} row whose
 * container is THIS process is a self-update handoff that succeeded and is adopted; anything else
 * in flight was interrupted and fails, exactly as before.
 */
@QuarkusTest
public class CdSweepAdoptionTest {

  @Inject DeployService deployService;
  @Inject FakeDeploymentDriver driver;
  @Inject CdApplicationRepository applications;
  @Inject CdDeploymentRepository deployments;

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

  private String deployment(String environmentId, CdDeploymentStatus status, String containerName) {
    String id = UUID.randomUUID().toString();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdApplication app = applications.listByEnvironment(environmentId).get(0);
              CdDeployment row = new CdDeployment();
              row.id = id;
              row.application = app;
              row.commitSha = "c".repeat(40);
              row.status = status;
              row.containerName = containerName;
              row.createdAt = Instant.now();
              if (status == CdDeploymentStatus.ACTIVE) {
                row.finishedAt = Instant.now();
              }
              deployments.persist(row);
            });
    return id;
  }

  private String statusOf(String deploymentId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> deployments.findById(deploymentId).status.name());
  }

  @Test
  public void aStartingRowWhoseContainerIsThisProcessIsAdoptedAndItsPredecessorDecommissioned() {
    String environmentId = createEnvironment("sweep-adopt", "repo-adopt", "qits-cd");
    String predecessor = deployment(environmentId, CdDeploymentStatus.ACTIVE, "old-cd");
    String handedOff = deployment(environmentId, CdDeploymentStatus.STARTING, "new-cd");
    driver.scriptSelfId("abcdef123456");
    driver.scriptContainerId("new-cd", "abcdef123456" + "0".repeat(52));

    deployService.sweepInFlight();

    assertEquals("ACTIVE", statusOf(handedOff));
    assertEquals("DECOMMISSIONED", statusOf(predecessor));
  }

  @Test
  public void aStartingRowWhoseContainerIsGoneStillFails() {
    String environmentId = createEnvironment("sweep-fail", "repo-sweep-fail", "qits-cd");
    String interrupted = deployment(environmentId, CdDeploymentStatus.STARTING, "vanished");
    driver.scriptSelfId("abcdef123456");
    // containerId("vanished") answers blank: the referee rolled back and removed the successor.

    deployService.sweepInFlight();

    assertEquals("FAILED", statusOf(interrupted));
  }
}
