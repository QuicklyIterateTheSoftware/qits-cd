package eu.wohlben.qits.cd.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.RegistryExport;
import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.entity.CdEnvironment;
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
 * The one-time handover: cd v1's local environments and applications pushed into
 * qits-serviceregistry the first time cd v2 boots against an empty one.
 *
 * <p>The rows are written straight into the frozen tables, which is the only way to have any — no
 * code path creates one any more, and that is the state the live rollout starts from.
 */
@QuarkusTest
@WithTestResource(value = StubRegistry.class, scope = TestResourceScope.GLOBAL)
public class CdRegistryExportTest {

  @Inject RegistryExport export;

  @BeforeEach
  void reset() {
    StubRegistry.reset();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdApplication.deleteAll();
              CdEnvironment.deleteAll();
            });
  }

  @Test
  public void anEmptyRegistryIsFilledFromTheLocalTables() {
    CdEnvironment dev = environment("exp-dev", "environment/exp-dev", "qits-net");
    CdEnvironment preprod = environment("exp-preprod", "environment/exp-preprod", "qits-env-exp-preprod");
    // One application in both tiers becomes ONE service with TWO links — the shape change the
    // extraction is: a service is an entity, an environment is a link.
    application("qits-gateway", dev, CdDeploymentTarget.ENVIRONMENT, null, true, "/gw/q/health/ready");
    application("qits-gateway", preprod, CdDeploymentTarget.ENVIRONMENT, null, true, "/gw/q/health/ready");
    application("qits-idp", null, CdDeploymentTarget.SINGLETON, "main", false, null);

    assertTrue(export.exportOnce());

    assertEquals(
        List.of("exp-dev", "exp-preprod"),
        StubRegistry.environments().stream().map(StubRegistry.Env::name).sorted().toList());
    String devId = registryIdOf("exp-dev");
    String preprodId = registryIdOf("exp-preprod");

    StubRegistry.Svc gateway = StubRegistry.service("qits-gateway");
    assertEquals(CdDeploymentTarget.ENVIRONMENT, gateway.target());
    assertTrue(gateway.availableOnEnv());
    assertEquals("/gw/q/health/ready", gateway.healthPath());
    assertEquals(
        List.of(devId, preprodId).stream().sorted().toList(),
        gateway.environmentIds().stream().sorted().toList());

    StubRegistry.Svc idp = StubRegistry.service("qits-idp");
    assertEquals(CdDeploymentTarget.SINGLETON, idp.target());
    assertEquals("main", idp.branch());
    assertEquals(List.of(), idp.environmentIds(), "a singleton is linked nowhere and everywhere");
  }

  @Test
  public void aRegistryThatAlreadyHasEnvironmentsIsNeverOverwritten() {
    // An empty registry is the only safe signal that nothing has been exported yet. Anything else
    // may have been edited since, and a stale copy of tables cd stopped maintaining must not win.
    StubRegistry.seedEnvironment("exp-live", "environment/exp-live", "qits-net");
    environment("exp-stale", "environment/exp-stale", "qits-env-exp-stale");

    assertFalse(export.exportOnce());

    assertEquals(
        List.of("exp-live"),
        StubRegistry.environments().stream().map(StubRegistry.Env::name).toList());
  }

  @Test
  public void nothingLocalMeansNothingToDo() {
    assertFalse(export.exportOnce());
    assertEquals(List.of(), StubRegistry.environments());
  }

  @Test
  public void aSecondRunAfterASuccessfulExportChangesNothing() {
    environment("exp-once", "environment/exp-once", "qits-net");
    assertTrue(export.exportOnce());
    assertFalse(export.exportOnce(), "the registry is no longer empty, so the export is done");
    assertEquals(1, StubRegistry.environments().size());
  }

  // --- the frozen tables ------------------------------------------------------------------------

  private CdEnvironment environment(String name, String branch, String network) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CdEnvironment row = new CdEnvironment();
              row.id = UUID.randomUUID().toString();
              row.name = name;
              row.branch = branch;
              row.network = network;
              row.createdAt = Instant.now();
              row.persist();
              return row;
            });
  }

  private void application(
      String name,
      CdEnvironment environment,
      CdDeploymentTarget target,
      String branch,
      boolean availableOnEnv,
      String healthPath) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdApplication row = new CdApplication();
              row.id = UUID.randomUUID().toString();
              row.name = name;
              row.repoId = name;
              row.environment =
                  environment == null
                      ? null
                      : CdEnvironment.<CdEnvironment>findById(environment.id);
              row.deploymentTarget = target;
              row.branch = branch;
              row.availableOnEnv = availableOnEnv;
              row.healthPath = healthPath;
              row.createdAt = Instant.now();
              row.persist();
            });
  }

  private String registryIdOf(String name) {
    return StubRegistry.environments().stream()
        .filter(env -> env.name().equals(name))
        .findFirst()
        .orElseThrow()
        .id();
  }
}
