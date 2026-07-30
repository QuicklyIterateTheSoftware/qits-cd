package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under {@code mvn verify
 * -DskipITs=false}, the GraalVM binary under {@code mvn verify -Dnative}. The assertions are chosen
 * for what a native build can silently lose rather than for API coverage (that is the
 * {@code @QuarkusTest} suite's job):
 *
 * <ul>
 *   <li>the routes are where the config says — {@code quarkus.rest.path} and {@code
 *       quarkus.http.non-application-root-path} are build-time settings baked into the artifact;
 *   <li>the shipped datasource default connects and {@code db/cd/migration/V1__init.sql} survived
 *       as a resource — a migration is loaded by scanning a classpath location, exactly the shape
 *       native-image drops;
 *   <li>an environment round-trips through Hibernate/Panache in the packaged process.
 * </ul>
 *
 * <p>No deployment is driven here: that needs docker, and the packaged process carries the real
 * {@link eu.wohlben.qits.cd.dockerhost.DockerDeploymentDriver}. The container runtime is pointed
 * at a binary that does not exist, which exercises the best-effort seam (an environment must exist
 * even when docker is unreachable) and keeps this IT free of host side effects.
 */
@QuarkusIntegrationTest
@TestProfile(CdPackagedSurfaceIT.PackagedUnderTarget.class)
public class CdPackagedSurfaceIT {

  /**
   * Relocates the launched artifact's state under {@code target/} by moving {@code user.home}, not
   * by restating the settings — cd's datasource default is {@code ${user.home}}-rooted in the cd
   * jar's {@code META-INF/microprofile-config.properties}, so overriding {@code user.home} leaves
   * the <b>shipped</b> JDBC URL itself under test (the AUTO_SERVER lesson from qits-ci).
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {
    static final Path HOME = Path.of("target", "cd-packaged-it-home").toAbsolutePath();

    @Override
    public Map<String, String> getConfigOverrides() {
      deleteRecursively(HOME);
      return Map.of(
          "user.home", HOME.toString(),
          // No docker on purpose: every driver call must degrade to a warning, never a failure.
          "qits.cd.container-runtime", "docker-absent-for-this-it");
    }
  }

  @Test
  public void theApiDocumentAndItsUiAreServedUnderTheGatewaySegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /cd on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/cd/q/openapi").then().statusCode(200);
    given().when().get("/cd/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  public void theIntakeIsAtTheAddressQitsCiPostsTo() {
    // qits-ci's CdBuildNotifier delivers here fire-and-forget: a wrong path raises no error on
    // either side and deployments simply never happen, so the address is asserted from the
    // artifact. An empty body must reach @Valid — a 400 proves the resource, not the router's 404.
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/cd/api/events/build-succeeded")
        .then()
        .statusCode(400);
  }

  @Test
  public void anEnvironmentRoundTripsThroughFlywayAndPanache() {
    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name", "packaged-env",
                "applications", List.of(Map.of("repoId", "packaged-repo", "name", "packaged-app"))))
        .when()
        .post("/cd/api/environments")
        .then()
        .statusCode(201);

    given()
        .when()
        .get("/cd/api/environments")
        .then()
        .statusCode(200);

    // The round trip above would look identical against an in-memory database, so pin that the
    // process really opened the ${user.home}-rooted file H2 the cd jar ships.
    assertTrue(
        Files.isDirectory(PackagedUnderTarget.HOME.resolve(".qits/data/cd/h2")),
        "the shipped file-H2 default must be what the packaged process opened");
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception e) {
      throw new IllegalStateException("could not clear " + root, e);
    }
  }
}
