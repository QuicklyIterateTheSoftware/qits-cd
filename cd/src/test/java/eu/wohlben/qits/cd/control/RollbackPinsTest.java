package eu.wohlben.qits.cd.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.cd.control.RollbackPins.Pin;
import eu.wohlben.qits.cd.control.RollbackPins.Row;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pin rule, case by case. Plain JUnit — the rule's whole input is "which rows exist, in what
 * order", so nothing here needs a running application or a database.
 *
 * <p>The first four cases are the ones qits-artifacts' OCI collector held before the policy moved
 * here, restated in cd's own vocabulary; the fifth is the question cd's data can answer and the
 * collector's could not — a row that never served.
 *
 * <p>Shas are written as full 40-hex commit shas because that is what a deployment row carries and
 * what post-receive pushes as an image tag.
 */
class RollbackPinsTest {

  private static final String SHA_A = "a".repeat(40);
  private static final String SHA_B = "b".repeat(40);
  private static final String SHA_C = "c".repeat(40);
  private static final String SHA_D = "d".repeat(40);

  @Test
  void theServingShaIsPinnedEvenWhenItIsTheOnlyDeploymentTheApplicationEverHad() {
    assertEquals(
        List.of(new Pin("qits-artifacts", List.of(SHA_A))),
        RollbackPins.of(List.of(row("app-1", "qits-artifacts", SHA_A, CdDeploymentStatus.ACTIVE))),
        "one deployment, one pin — there is nothing to roll back to yet");
  }

  @Test
  void theRollbackTargetIsTheNewestDistinctShaAndARedeployOfTheSameShaIsNotOne() {
    // The ordering subtlety, and the whole reason "previous DISTINCT" is spelled that way: a
    // redeploy writes a second row at the SAME sha. Reading that as the previous version pins a
    // duplicate of what is already running and drops the only thing a rollback could pull.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-1", "qits-cd", SHA_A, CdDeploymentStatus.ACTIVE),
                row("app-1", "qits-cd", SHA_A, CdDeploymentStatus.DECOMMISSIONED),
                row("app-1", "qits-cd", SHA_B, CdDeploymentStatus.DECOMMISSIONED),
                row("app-1", "qits-cd", SHA_C, CdDeploymentStatus.DECOMMISSIONED)));

    assertEquals(
        List.of(new Pin("qits-cd", List.of(SHA_A, SHA_B))),
        pins,
        "one rollback step, not every sha a row ever named — pinning them all reclaims nothing");
  }

  @Test
  void anApplicationWithNoActiveRowPinsNothing() {
    // Nothing serves it, so no restart pulls anything and no rollback has a target. Reporting an
    // empty entry would read as "this name is pinned"; the application is simply absent.
    assertEquals(
        List.of(),
        RollbackPins.of(
            List.of(
                row("app-1", "qits-spa-home", SHA_B, CdDeploymentStatus.FAILED),
                row("app-1", "qits-spa-home", SHA_A, CdDeploymentStatus.DECOMMISSIONED))));
  }

  @Test
  void theUnionIsPerApplicationNameNotPerEnvironment() {
    // An application belongs to an environment, so one service in two environments is two
    // application rows sharing one image name — and both environments' shas pin that image. Naming
    // one environment leaves the other's next restart with no image to pull.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-staging", "qits-events", SHA_D, CdDeploymentStatus.ACTIVE),
                row("app-staging", "qits-events", SHA_C, CdDeploymentStatus.DECOMMISSIONED),
                row("app-live", "qits-events", SHA_B, CdDeploymentStatus.ACTIVE),
                row("app-live", "qits-events", SHA_A, CdDeploymentStatus.DECOMMISSIONED)));

    assertEquals(
        List.of(new Pin("qits-events", List.of(SHA_B, SHA_D, SHA_A, SHA_C))),
        pins,
        "one entry, four shas: the serving ones sorted, then the rollback ones sorted");
  }

  @Test
  void anAttemptThatNeverServedIsNotARollbackTarget() {
    // The case cd's own data settles: a failed deployment and one whose image never arrived sit
    // between the serving row and the row that served before it. A rollback goes back to what
    // served — DeployService restarts the container it stopped, and only a passed health gate ever
    // decommissioned a row — so those two are skipped rather than ending the search.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-1", "qits-projects", SHA_A, CdDeploymentStatus.ACTIVE),
                row("app-1", "qits-projects", SHA_C, CdDeploymentStatus.FAILED),
                row("app-1", "qits-projects", SHA_D, CdDeploymentStatus.IMAGE_MISSING),
                row("app-1", "qits-projects", SHA_B, CdDeploymentStatus.DECOMMISSIONED)));

    assertEquals(
        List.of(new Pin("qits-projects", List.of(SHA_A, SHA_B))),
        pins,
        "the failed and image-missing attempts pin nothing; the sha that served does");
  }

  @Test
  void anInFlightRowNeitherPinsNorHidesTheServingOne() {
    // QUEUED and STARTING are the two non-terminal states, and a row in either has served nothing
    // yet. The newest ACTIVE row is still the serving one, and the search past it is unaffected.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-1", "qits-workspaces", SHA_C, CdDeploymentStatus.QUEUED),
                row("app-1", "qits-workspaces", SHA_A, CdDeploymentStatus.ACTIVE),
                row("app-1", "qits-workspaces", SHA_B, CdDeploymentStatus.DECOMMISSIONED)));

    assertEquals(List.of(new Pin("qits-workspaces", List.of(SHA_A, SHA_B))), pins);
  }

  @Test
  void theAnswerIsOrderedByApplicationNameWhateverOrderTheRowsArriveIn() {
    // A collector diffs two reports; row order is the database's business and must not reach it.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-2", "qits-stt", SHA_B, CdDeploymentStatus.ACTIVE),
                row("app-1", "qits-artifacts", SHA_A, CdDeploymentStatus.ACTIVE),
                row("app-3", "qits-ci", SHA_C, CdDeploymentStatus.ACTIVE)));

    assertEquals(
        List.of("qits-artifacts", "qits-ci", "qits-stt"), pins.stream().map(Pin::applicationName).toList());
  }

  @Test
  void aShaServingOneEnvironmentAndRollingBackAnotherIsReportedOnce() {
    // The union's own edge: one environment is a version ahead of the other, so the older sha is
    // both what live serves and what staging would fall back to. It has to be kept, once.
    List<Pin> pins =
        RollbackPins.of(
            List.of(
                row("app-staging", "qits-gateway", SHA_B, CdDeploymentStatus.ACTIVE),
                row("app-staging", "qits-gateway", SHA_A, CdDeploymentStatus.DECOMMISSIONED),
                row("app-live", "qits-gateway", SHA_A, CdDeploymentStatus.ACTIVE)));

    assertEquals(List.of(new Pin("qits-gateway", List.of(SHA_A, SHA_B))), pins);
  }

  private static Row row(
      String applicationId, String applicationName, String sha, CdDeploymentStatus status) {
    return new Row(applicationId, applicationName, sha, status);
  }
}
