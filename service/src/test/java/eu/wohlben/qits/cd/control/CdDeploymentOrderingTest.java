package eu.wohlben.qits.cd.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import eu.wohlben.qits.cd.registry.StubRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Newest-first is one answer, not a coin flip.
 *
 * <p>The listings used to order by {@code createdAt desc, id desc}, and the id is a random UUID —
 * so two rows recorded in the same tick came back in whichever order their UUIDs happened to sort
 * in, and a re-read could disagree with the first. That is the shape of the flake seen on
 * {@code eachDeploymentCarriesTheRunOfTheBuildThatCausedIt}, and it is worse than a flaky test: the
 * client reads "the first row per application is the current one" straight off this order.
 *
 * <p>V5's {@code seq} identity column is the tiebreak, and these rows are written with a deliberately
 * identical {@code createdAt} so nothing but the tiebreak can decide.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdDeploymentOrderingTest {

  @Inject CdDeploymentRepository deployments;

  @BeforeEach
  void reset() {
    StubRegistry.reset();
  }

  @Test
  public void twoRowsRecordedInTheSameTickComeBackInTheOrderTheyWereWritten() {
    String environmentId = "ordering-" + UUID.randomUUID();
    Instant sameTick = Instant.parse("2026-08-06T10:00:00Z");
    // Ids chosen so `id desc` would put the SECOND row first — the old tiebreak's exact failure.
    String first = "aaaa" + UUID.randomUUID();
    String second = "zzzz" + UUID.randomUUID();
    write(first, environmentId, sameTick, "sha-first");
    write(second, environmentId, sameTick, "sha-second");

    List<String> ids =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    deployments.listByEnvironmentNewestFirst(environmentId).stream()
                        .map(d -> d.id)
                        .toList());
    assertEquals(List.of(second, first), ids, "the newer row is first, deterministically");

    // ...and it says the same thing on a second read, which is the half a random tiebreak fails.
    List<String> again =
        QuarkusTransaction.requiringNew()
            .call(
                () ->
                    deployments.listByEnvironmentNewestFirst(environmentId).stream()
                        .map(d -> d.id)
                        .toList());
    assertEquals(ids, again);
  }

  private void write(String id, String environmentId, Instant createdAt, String sha) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdDeployment row = new CdDeployment();
              row.id = id;
              row.applicationName = "app-ordering";
              row.environmentId = environmentId;
              row.commitSha = sha;
              row.status = CdDeploymentStatus.ACTIVE;
              row.createdAt = createdAt;
              row.finishedAt = createdAt;
              deployments.persist(row);
            });
  }
}
