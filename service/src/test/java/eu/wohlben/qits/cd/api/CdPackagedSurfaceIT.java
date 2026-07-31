package eu.wohlben.qits.cd.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>It is also <b>the only test here that ever sees the client</b>. Quinoa is disabled by default
 * in test mode, so no {@code @QuarkusTest} in this repo has a client in it at all — a unit test
 * asserting something about {@code /cd/} would pass against a process serving nothing. What the SPA
 * is actually served as is proven here or nowhere, and the probe list is the platform's, from
 * {@code docs/project-setup-quinoa-angular.md}:
 *
 * <ul>
 *   <li>{@code /cd/} → 200 HTML carrying the right {@code <base href>} — the client's own spelling
 *       of the segment, set in another repository's {@code angular.json}, where no build here can
 *       check it. Wrong, and the page loads and then fetches its JavaScript from nowhere.
 *   <li>a deep link → 200 {@code index.html}, so the Angular router owns it across a reload
 *   <li>{@code /cd/api/<real>} → the API's own answer; {@code /cd/api/nope} → 404 and <b>not the
 *       client</b>. That is what {@code quarkus.quinoa.ignored-path-prefixes} buys, and the caller
 *       it protects is qits-ci's fire-and-forget intake — the one that would never report being
 *       handed a web page instead of an answer.
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
  public void theClientIsServedAtTheSegmentWithItsOwnBaseHref() {
    String html =
        given()
            .when()
            .get("/cd/")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        html.contains("<base href=\"/cd/\">"),
        "the client's baseHref must be the segment it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  public void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    String deepLink =
        given()
            .when()
            .get("/cd/some/route")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains("<base href=\"/cd/\">"),
        "a deep link must answer with index.html, not with a differently-shaped page");
  }

  @Test
  public void theBareSegmentRedirectsRatherThanFourOhFouring() {
    // Quinoa mounts at /cd/*, which does not match the bare segment (upstream #960) — the redirect
    // in webui/WebUiRedirect is this service's answer, and only the packaged process has both it
    // and a real client to bounce to.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/cd")
        .then()
        .statusCode(301)
        .header("Location", "/cd/");
  }

  @Test
  public void aMistypedMachinePathIsNeverTheClient() {
    // The whole reason quarkus.quinoa.ignored-path-prefixes is set: without /api in that list this
    // answers 200 with index.html, and qits-ci's intake — which swallows delivery failures at debug
    // — would parse the client's not-found page as an accepted delivery.
    //
    // The assertion is "404, and not the CLIENT" rather than the reference's shorter "404, never
    // HTML", because what actually comes back here is Vert.x' own stock 53-byte
    // `<h1>Resource not found</h1>` — text/html, and correct. Every sibling service answers a
    // mistyped machine path the same way; asserting on the content type alone would fail against
    // the right behaviour while still passing against the wrong one (index.html is text/html too).
    String body = given().when().get("/cd/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains("<base href=\"/cd/\">"),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // /q is the second half of the ignore list, and the derivation would have covered both — this
    // pins that setting the key by hand did not drop one.
    String underQ =
        given().when().get("/cd/q/health/nope").then().statusCode(404).extract().asString();
    assertFalse(
        underQ.contains("<base href=\"/cd/\">"),
        "a mistyped non-application path must not be answered with the client; got: " + underQ);

    // qits-gateway routes verbatim by prefix, so there is no unprefixed form to fall back to.
    given().when().get("/api/environments").then().statusCode(404);
  }

  @Test
  public void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    // The path this service's own health gate would curl for a peer, at the address the deployment
    // convention assumes — under quarkus.http.non-application-root-path, not the rest path.
    given()
        .when()
        .get("/cd/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
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
