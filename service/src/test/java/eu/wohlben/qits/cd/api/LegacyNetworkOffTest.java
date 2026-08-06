package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.cd.control.FakeDeploymentDriver;
import eu.wohlben.qits.cd.control.FakeSpecSource;
import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What emptying {@code qits.cd.legacy-network} changes: the dual-home join, and nothing else. */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
@TestProfile(LegacyNetworkOffProfile.class)
public class LegacyNetworkOffTest {

  private static final String SHA = "a".repeat(40);

  @Inject FakeDeploymentDriver driver;
  @Inject FakeSpecSource specs;

  @BeforeEach
  void reset() {
    driver.reset();
    specs.reset();
  }

  @Test
  public void anEmptyLegacyNetworkDropsTheDualHomeJoinAndTheSearchOnIt() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "flip"))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201);
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "repoId", "repo-flip", "branch", "environment/flip", "commitSha", SHA))
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(202);

    long deadline = System.currentTimeMillis() + 15_000;
    while (driver.started().isEmpty() && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (driver.started().isEmpty()) {
      fail("nothing was started");
    }

    // The container runs on its own network and joins nothing else: no environment names it a
    // public node, and there is no legacy network left to dual-home onto.
    assertEquals("qits-env-flip-repo-flip", driver.started().get(0).network());
    assertEquals(List.of(), driver.connections());
    // ...and the predecessor search no longer looks there either, which is what makes the flip
    // the moment a stale container on qits-net stops being anyone's predecessor.
    assertTrue(
        driver.aliasSearches().stream().noneMatch(s -> s.contains("qits-net")),
        "the search is off the legacy network: " + driver.aliasSearches());
  }
}
