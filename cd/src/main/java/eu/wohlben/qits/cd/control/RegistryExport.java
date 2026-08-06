package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.control.RegistryClient.RegEnvironment;
import eu.wohlben.qits.cd.control.RegistryClient.ServiceUpsert;
import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import eu.wohlben.qits.cd.persistence.CdApplicationRepository;
import eu.wohlben.qits.cd.persistence.CdEnvironmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The one-time handover: the environments and applications cd v1 held locally, pushed into
 * qits-serviceregistry the first time cd v2 boots against an empty one.
 *
 * <p><b>Three conditions, all required.</b> The registry has to answer, it has to hold <em>no</em>
 * environments, and there have to be local rows to send. An empty registry is the only safe signal
 * that nothing has been exported yet — a registry someone has since edited must never be overwritten
 * by a stale copy of tables cd stopped maintaining.
 *
 * <p><b>Never fatal.</b> A registry that is down at boot is logged at WARN and retried on the next
 * boot; the process comes up either way and deploys as soon as the registry is back. Startup that
 * required the registry would make a registry outage a platform outage, and cd is the thing that
 * would have to redeploy it.
 *
 * <p>After this runs, {@code cd_environment} and {@code cd_application} are <b>frozen</b>: nothing
 * reads or writes them. They are kept for one rollout so the export can be repeated or audited, and
 * a later cleanup migration drops them.
 */
@ApplicationScoped
public class RegistryExport {

  private static final Logger LOG = Logger.getLogger(RegistryExport.class);

  @Inject RegistryClient registry;
  @Inject CdEnvironmentRepository environments;
  @Inject CdApplicationRepository applications;

  /**
   * Runs at startup, off the test profile — the {@code DeployService.onStart} arrangement, and for
   * the same reason: a suite drives {@link #exportOnce()} directly rather than racing a boot hook.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    exportOnce();
  }

  /**
   * @return true when rows were sent, false when there was nothing to do or the registry could not
   *     be reached — both are ordinary outcomes and neither throws
   */
  public boolean exportOnce() {
    List<RegEnvironment> remote;
    try {
      remote = registry.environments();
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not reach qits-serviceregistry to export the local environments (%s) — retrying at"
              + " the next start",
          e.getMessage());
      return false;
    }
    if (!remote.isEmpty()) {
      return false; // already the system of record; the local tables are frozen history
    }

    List<CdEnvironment> localEnvironments =
        QuarkusTransaction.requiringNew().call(() -> List.copyOf(environments.listNewestFirst()));
    if (localEnvironments.isEmpty()) {
      return false;
    }
    List<CdApplication> localApplications =
        QuarkusTransaction.requiringNew().call(() -> List.copyOf(applications.listAll()));

    // The registry mints its own ids, so the export maps local id -> registry id by NAME, which is
    // the one identity both sides agree on.
    // One item's failure does not abandon the rest: the guard above only fires on an EMPTY
    // registry, so a run that stopped half way would leave a registry no later boot re-enters.
    // Everything that did not land is named in one WARN instead.
    List<String> failed = new ArrayList<>();
    Map<String, String> exported = new LinkedHashMap<>();
    for (CdEnvironment environment : localEnvironments) {
      try {
        RegEnvironment created =
            registry.createEnvironment(environment.name, environment.branch, environment.network);
        exported.put(environment.id, created.id());
        LOG.infof("Exported environment %s to qits-serviceregistry", environment.name);
      } catch (RuntimeException e) {
        failed.add("environment " + environment.name + " (" + e.getMessage() + ")");
      }
    }

    // One service per NAME, with the links of every local row that carried it: an application in
    // two environments was two rows here and is one service with two links over there.
    Map<String, ServiceUpsert> services = new LinkedHashMap<>();
    for (CdApplication application : localApplications) {
      ServiceUpsert current = services.get(application.name);
      Set<String> links =
          new LinkedHashSet<>(current == null ? List.of() : current.environmentIds());
      if (application.deploymentTarget != CdDeploymentTarget.SINGLETON
          && application.environment != null) {
        String registryId = exported.get(application.environment.id);
        if (registryId != null) {
          links.add(registryId);
        }
      }
      services.put(
          application.name,
          new ServiceUpsert(
              application.name,
              application.deploymentTarget,
              application.branch,
              application.availableOnEnv || (current != null && current.availableOnEnv()),
              application.healthPath != null
                  ? application.healthPath
                  : current == null ? null : current.healthPath(),
              List.copyOf(links)));
    }

    for (ServiceUpsert service : services.values()) {
      try {
        registry.upsertService(service);
      } catch (RuntimeException e) {
        failed.add("service " + service.name() + " (" + e.getMessage() + ")");
      }
    }
    if (!failed.isEmpty()) {
      LOG.warnf(
          "Exported %d environment(s) and %d service(s) into qits-serviceregistry; %d did NOT land"
              + " and need re-sending by hand: %s",
          exported.size(), services.size(), failed.size(), String.join(", ", failed));
    } else {
      LOG.infof(
          "Exported %d environment(s) and %d service(s) into qits-serviceregistry; the local"
              + " cd_environment/cd_application tables are frozen from here on",
          exported.size(), services.size());
    }
    return true;
  }
}
