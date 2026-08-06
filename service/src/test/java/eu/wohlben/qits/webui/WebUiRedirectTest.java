package eu.wohlben.qits.webui;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The bare segment redirects to the client; nothing else moves. Quinoa is off under {@code %test},
 * which is exactly why this is testable here at all: the redirect is this service's own route, not
 * Quinoa's, and it must answer whether or not a client is packaged.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
class WebUiRedirectTest {

  @Test
  void theBareSegmentRedirectsToTheClient() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/cd")
        .then()
        .statusCode(301)
        .header("Location", equalTo("/cd/"));
  }

  @Test
  void theQueryStringTravels() {
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/cd?x=y")
        .then()
        .statusCode(301)
        .header("Location", equalTo("/cd/?x=y"));
  }

  @Test
  void theSlashFormIsNotThisRoutesBusiness() {
    // Vert.x path routes are trailing-slash tolerant, so without the exact-path guard this redirect
    // would answer /cd/ too — sitting AHEAD of Quinoa and looping onto itself. With Quinoa off under
    // %test the slash form falls through to a plain 404, which is exactly the proof: this route let
    // it pass.
    given().redirects().follow(false).when().get("/cd/").then().statusCode(404);
  }

  @Test
  void aWriteToTheBareSegmentIsMethodNotAllowed() {
    // The route matches the path but names GET and HEAD, so Vert.x answers 405 — the machine client
    // learns the truth instead of being bounced at HTML.
    given().redirects().follow(false).when().post("/cd").then().statusCode(405);
  }
}
