package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The environment surface end to end — against {@link FakeDeploymentDriver} (no docker) and {@link
 * StubRegistry} (a real socket speaking qits-serviceregistry's contract, no shared code).
 *
 * <p>The subject changed with the extraction and the assertions changed with it: this used to prove
 * that cd wrote the right local rows, and now proves that cd <b>proxies</b> — the same request and
 * response shapes, the registry holding the truth, and the docker side effects still cd's. Tests
 * address the absolute {@code /cd/api} paths, which is what makes them catch a prefix regression.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdEnvironmentApiTest {

  @jakarta.inject.Inject FakeDeploymentDriver driver;

  @BeforeEach
  void reset() {
    driver.reset();
    StubRegistry.reset();
  }

  private String create(Map<String, Object> payload) {
    return given()
        .contentType(ContentType.JSON)
        .body(payload)
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .extract()
        .path("environment.id");
  }

  @Test
  public void creationFillsTheConventionsAndEnsuresTheNetwork() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-conventions"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.name", equalTo("env-conventions"))
        .body("environment.branch", equalTo("environment/env-conventions"))
        .body("environment.network", equalTo("qits-env-env-conventions"))
        .body("environment.applications", hasSize(0))
        .body("environment.id", notNullValue());

    // The conventions are cd's — it fills them in and the registry stores what it was sent.
    StubRegistry.Env stored =
        StubRegistry.environments().stream()
            .filter(env -> env.name().equals("env-conventions"))
            .findFirst()
            .orElseThrow();
    assertEquals("environment/env-conventions", stored.branch());
    assertEquals("qits-env-env-conventions", stored.network());

    assertTrue(
        driver.ensuredNetworks().contains("qits-env-env-conventions"),
        "creation must ensure the environment's network");
  }

  @Test
  public void explicitBranchAndNetworkWin() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-explicit", "branch", "main", "network", "custom-net"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.branch", equalTo("main"))
        .body("environment.network", equalTo("custom-net"));
  }

  @Test
  public void declaredApplicationsAreAcceptedAndIgnored() {
    // The deprecated field. It is still accepted so an older sender's payload deserializes, but the
    // registry holds one identity for a service (its name), and rows are derived from each
    // repository's own deployments.yml — so nothing is registered from it.
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "env-declared",
                "applications",
                    List.of(Map.of("repoId", "repo-declared", "name", "app-declared"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201)
        .body("environment.applications", hasSize(0));

    assertEquals(List.of(), StubRegistry.services(), "nothing was registered from the payload");
  }

  @Test
  public void aDuplicateNameIsAConflict() {
    Map<String, Object> payload = Map.of("name", "env-duplicate");
    given().contentType(ContentType.JSON).body(payload).when().post("/cd/api/environments")
        .then().statusCode(201);
    given().contentType(ContentType.JSON).body(payload).when().post("/cd/api/environments")
        .then().statusCode(409);
  }

  @Test
  public void hostileNamesAreRejectedBeforeTheyReachAnArgvOrTheRegistry() {
    // The name becomes a docker network name, an alias and an image path segment. cd owes its own
    // check here rather than borrowing the registry's: this surface is attacker-reachable, and a
    // 400 that never left the process is the cheapest kind.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "Evil Name"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(400);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-hostile-net", "network", "--privileged"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(400);

    assertEquals(List.of(), StubRegistry.calls(), "neither request reached the registry");
  }

  @Test
  public void applicationsAreOptionalOnCreate() {
    // The shape every caller should send: a tier is created, and what it holds is derived from
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
  public void theEnvironmentReadShowsTheRegistrysServicesWithoutTheSingletons() {
    String environmentId = create(Map.of("name", "env-read"));
    putService("app-read", CdDeploymentTarget.ENVIRONMENT, null, List.of(environmentId));
    putService("qits-idp", CdDeploymentTarget.SINGLETON, "main", List.of());

    given()
        .when()
        .get("/cd/api/environments/" + environmentId)
        .then()
        .statusCode(200)
        // The environment aggregate is the tier's own services. Singletons belong to no tier and
        // are reached through the flat listing instead — which does show both.
        .body("environment.applications", hasSize(1))
        .body("environment.applications[0].name", equalTo("app-read"))
        .body("environment.applications[0].repoId", equalTo("app-read"))
        .body("environment.applications[0].environmentId", equalTo(environmentId))
        .body("environment.applications[0].environmentName", equalTo("env-read"))
        .body("environment.applications[0].target", equalTo("ENVIRONMENT"));

    List<Map<String, Object>> flat =
        given()
            .when()
            .get("/cd/api/applications")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList("applications");
    assertEquals(2, flat.size(), "the flat listing carries the singleton too: " + flat);
  }

  @Test
  public void patchRenamesAndRetargetsWithoutTouchingDocker() {
    String environmentId = create(Map.of("name", "env-patch"));
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
    String environmentId = create(Map.of("name", "env-patch-partial"));

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
    create(Map.of("name", "env-patch-taken"));
    String environmentId = create(Map.of("name", "env-patch-other"));

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
    String environmentId = create(Map.of("name", "env-derived-teardown"));
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-derived-teardown-app-x",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-x"));
    driver.scriptSingletonContainers(List.of(new DeploymentDriver.Endpoint("idp-id", "qits-idp")));

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
  public void teardownLeavesTheLegacyNetworkAloneWhenItIsTheEnvironmentsBundle() {
    // The dev tier's shape exactly: its bundle IS qits.cd.legacy-network. That network is the
    // transition membership of every container on the host — singletons included — so it is not
    // this environment's to take away. Disconnecting the singletons from it would cut qits-idp and
    // qits-cd off from the platform, and cd would be doing it to itself mid-request.
    String environmentId = create(Map.of("name", "env-legacy-bundle", "network", "qits-net"));
    driver.reset();
    driver.scriptExistingNetwork(
        new DeploymentDriver.Network(
            "qits-env-env-legacy-bundle-app-y",
            environmentId,
            DeploymentDriver.NetworkKind.APPLICATION,
            "app-y"));
    driver.scriptSingletonContainers(List.of(new DeploymentDriver.Endpoint("cd-id", "qits-cd")));

    given().when().delete("/cd/api/environments/" + environmentId).then().statusCode(204);

    assertTrue(
        driver.disconnections().stream().noneMatch(d -> d.startsWith("qits-net:")),
        "no singleton is taken off the legacy network: " + driver.disconnections());
    assertTrue(
        !driver.removedNetworks().contains("qits-net"),
        "and the legacy network itself stays: " + driver.removedNetworks());
    // The environment's OWN derived network still goes, singleton disconnected from it first.
    assertTrue(
        driver.disconnections().contains("qits-env-env-legacy-bundle-app-y:cd-id"),
        driver.disconnections().toString());
    assertTrue(driver.removedNetworks().contains("qits-env-env-legacy-bundle-app-y"));
  }

  @Test
  public void teardownRemovesContainersAndNetworkAndThenTheTier() {
    String environmentId = create(Map.of("name", "env-teardown"));

    given().when().delete("/cd/api/environments/" + environmentId).then().statusCode(204);
    given().when().get("/cd/api/environments/" + environmentId).then().statusCode(404);

    assertTrue(
        driver.removedEnvironments().contains(environmentId),
        "teardown must remove the environment's containers");
    assertTrue(
        driver.removedNetworks().contains("qits-env-env-teardown"),
        "teardown must remove the environment's network");
    assertTrue(
        StubRegistry.environments().stream().noneMatch(env -> env.id().equals(environmentId)),
        "and the tier is gone from the registry");
  }

  @Test
  public void theDockerTeardownRunsBeforeTheRegistryDelete() {
    // The order is the contract. The teardown is label-driven and needs nothing from the registry,
    // but a registry delete that went first would leave a failed teardown with no row to retry it
    // from — so a half-finished teardown stays addressable.
    String environmentId = create(Map.of("name", "env-order"));
    AtomicReference<List<String>> dockerAtDelete = new AtomicReference<>(List.of());
    AtomicReference<List<String>> networksAtDelete = new AtomicReference<>(List.of());
    StubRegistry.onEnvironmentDelete(
        id -> {
          dockerAtDelete.set(driver.removedEnvironments());
          networksAtDelete.set(driver.removedNetworks());
        });

    given().when().delete("/cd/api/environments/" + environmentId).then().statusCode(204);

    assertTrue(
        dockerAtDelete.get().contains(environmentId),
        "the containers were already reaped when the registry delete arrived: " + dockerAtDelete.get());
    assertTrue(
        networksAtDelete.get().contains("qits-env-env-order"),
        "and so was the network: " + networksAtDelete.get());
    assertTrue(
        StubRegistry.calls().contains("DELETE /environments/" + environmentId),
        "the registry delete did arrive: " + StubRegistry.calls());
  }

  @Test
  public void deletingAMissingEnvironmentIs404() {
    given().when().delete("/cd/api/environments/no-such-environment").then().statusCode(404);
  }

  @Test
  public void anUnreachableRegistryIsAGatewayFailureRatherThanAnEmptyAnswer() {
    // The environment surface is a door onto the registry, so an outage is 502 and never an empty
    // list — a caller reading "no environments" would take it for a platform with none.
    StubRegistry.scriptOffline();

    given().when().get("/cd/api/environments").then().statusCode(502);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "env-outage"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(502);
  }

  @Test
  public void deploymentsListingRequiresAnExistingEnvironment() {
    given().when().get("/cd/api/deployments").then().statusCode(400);
    given().when().get("/cd/api/deployments?environmentId=no-such").then().statusCode(404);
  }

  /** Seeds a service straight into the registry — what a green build would have written. */
  private void putService(
      String name, CdDeploymentTarget target, String branch, List<String> environmentIds) {
    StubRegistry.seedService(
        new StubRegistry.Svc(name, target, branch, false, null, environmentIds, Instant.now()));
  }
}
