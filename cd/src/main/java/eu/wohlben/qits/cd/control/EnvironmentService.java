package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.control.RegistryClient.RegEnvironment;
import eu.wohlben.qits.cd.control.RegistryClient.RegService;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle: creation with the conventions filled in (branch {@code
 * environment/<name>}, bundle network {@code qits-env-<name>}), rename/retarget, and teardown.
 *
 * <p><b>The rows are qits-serviceregistry's; the docker is cd's.</b> Since the extraction the
 * registry is the system of record for environments and services, and this service is the
 * operational door onto it — the place where a create also makes a network and a delete also reaps
 * containers, because cd is the only thing on the platform holding a docker socket. Every read and
 * every row write here is a proxied call; the request and response shapes did not change, which is
 * what keeps the bootstrap and qits-spa-cd working across the switch.
 *
 * <p>An environment is a <b>tier</b> and is created deliberately — nothing derives one. What is
 * derived is everything inside it: a green build on the environment's branch registers the
 * repository's service in the registry ({@link DeployService}), so this service creates the tier and
 * the builds fill it.
 *
 * <p>Validation stays <b>here</b>, in front of the proxy. These names become docker network names,
 * network aliases and image path segments, so cd owes an attacker-reachable surface its own check
 * rather than borrowing another service's — and the registry ships the same vocabulary anyway.
 */
@ApplicationScoped
public class EnvironmentService {

  private static final Logger LOG = Logger.getLogger(EnvironmentService.class);

  /**
   * The branch an environment listens to when its creator names none. A tier deploys from its own
   * ref — {@code main} stays the integration trunk, and a release reaches dev by fast-forwarding
   * {@code environment/dev} onto it.
   */
  public static final String BRANCH_PREFIX = "environment/";

  /** The per-environment bundle network when the creator names none. */
  public static final String NETWORK_PREFIX = CdNetworks.BUNDLE_PREFIX;

  @Inject RegistryClient registry;
  @Inject CdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;

  /**
   * The transition network every container also joins ({@link DeployService}). Read here for one
   * reason only: a teardown must never take it down. See {@link #delete}.
   *
   * <p>{@code Optional} for the same reason it is there — SmallRye reads the enforcement flip's own
   * empty value as ABSENT rather than as an empty string.
   */
  @ConfigProperty(name = "qits.cd.legacy-network")
  Optional<String> legacyNetwork;

  /**
   * Create the tier in the registry, then make sure its network exists.
   *
   * <p>The docker half is best-effort and happens <b>after</b> the row: an environment whose network
   * could not be created yet is still an environment (the driver re-ensures every network before
   * every deployment), so a momentarily unreachable docker must not make the create fail. That was
   * true when the row was local and is unchanged now that it is a proxied call.
   */
  public RegEnvironment create(String name, String branch, String network) {
    CdIdentifiers.requireName(name, "environment name");
    String effectiveBranch =
        CdIdentifiers.requireBranch(isBlank(branch) ? BRANCH_PREFIX + name : branch);
    String effectiveNetwork =
        isBlank(network)
            ? CdNetworks.bundle(name)
            : CdIdentifiers.requireName(network, "network name");

    RegEnvironment environment = registry.createEnvironment(name, effectiveBranch, effectiveNetwork);
    driver.ensureNetwork(
        new DeploymentDriver.Network(
            environment.network(),
            environment.id(),
            DeploymentDriver.NetworkKind.BUNDLE,
            null));
    return environment;
  }

  /**
   * Rename an environment or point it at another branch. Both fields are optional; an omitted one
   * is left alone.
   *
   * <p><b>No docker side effects, deliberately.</b> This is the migration path onto the branch
   * convention and onto new names, and a rename that tore containers down would be a delete in
   * disguise — the one operation this platform's memories say never to reach for on a live
   * environment. What a rename does change is the names the <em>next</em> deployment derives
   * ({@code qits-env-<env>-<app>}); the running containers keep the networks they are on until
   * their own next deploy moves them.
   */
  public RegEnvironment update(String environmentId, String name, String branch) {
    String newName = isBlank(name) ? null : CdIdentifiers.requireName(name, "environment name");
    String newBranch = isBlank(branch) ? null : CdIdentifiers.requireBranch(branch);
    return registry.updateEnvironment(environmentId, newName, newBranch);
  }

  /**
   * Tear the environment down: cd's own deployment rows, then the containers and the networks, then
   * the tier itself in the registry.
   *
   * <p><b>Docker first, the registry last</b>, and the order is the contract. The teardown is
   * label-driven — it reaps by {@code qits.cd.environment} and removes the networks carrying that
   * id — so it needs nothing from the registry, but a registry delete that went first would leave a
   * failed teardown with no row to retry it from. Deleting last means a half-finished teardown is
   * still addressable.
   *
   * <p>The docker half is best-effort otherwise: a teardown must succeed even when docker already
   * lost the containers.
   *
   * <p>The order between the container reap and the network removal is load-bearing too. Singletons
   * live on this environment's per-application networks without belonging to the environment, so
   * they survive the reap and would then hold every network open — docker refuses to remove a
   * network with an endpoint on it. They are disconnected first, and only the networks THIS
   * environment owns (its bundle plus everything labelled with its id) are removed, so a singleton
   * keeps every other environment it serves.
   *
   * <p><b>The legacy network is never one of them.</b> An environment may have been created with
   * {@code qits.cd.legacy-network} as its bundle — the dev tier IS that case, its bundle is
   * {@code qits-net} — and it is not that environment's to take away: it is the transition
   * membership of every container on the host, singletons included. Disconnecting singletons from
   * it would cut qits-idp and qits-cd off from the platform, and cd would be doing it to itself
   * mid-request. So it is skipped for both steps, and the environment's derived per-application
   * networks still go.
   */
  public void delete(String environmentId) {
    RegEnvironment environment = registry.environment(environmentId);
    String legacy = legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).orElse(null);
    Set<String> networks = new LinkedHashSet<>();
    networks.add(environment.network());
    for (DeploymentDriver.Network network : driver.networks()) {
      if (environmentId.equals(network.environmentId())) {
        networks.add(network.name());
      }
    }
    if (networks.remove(legacy)) {
      LOG.infof(
          "Environment %s was on the legacy network '%s' — left in place, it is the platform's",
          environment.name(), legacy);
    }

    QuarkusTransaction.requiringNew()
        .run(() -> deployments.delete("environmentId = ?1", environmentId));
    int removed = driver.removeEnvironmentContainers(environmentId);
    if (removed > 0) {
      LOG.infof("Removed %d container(s) of environment %s", removed, environmentId);
    }
    for (DeploymentDriver.Endpoint singleton : driver.singletonContainers()) {
      for (String network : networks) {
        driver.disconnect(network, singleton.id());
      }
    }
    for (String network : networks) {
      driver.removeNetwork(network);
    }

    registry.deleteEnvironment(environmentId);
  }

  public RegEnvironment require(String environmentId) {
    return registry.environment(environmentId);
  }

  public List<RegEnvironment> list() {
    return registry.environments();
  }

  /**
   * One application as cd's read surface reports it: a registry service flattened into one tier.
   * {@code environmentId} and {@code environmentName} are null exactly for a singleton, and for an
   * environment service the registry currently links nowhere.
   */
  public record ApplicationView(RegService service, String environmentId, String environmentName) {}

  /**
   * The applications of one environment, as the environment's own read has always answered it:
   * the tier's services, <b>without</b> the singletons.
   *
   * <p>The registry's links query returns both — a cd asking "what must be linked into my
   * environment" needs the singletons too — but this is the environment aggregate, and a singleton
   * belongs to no tier. Singletons are reached through the flat listing, which is why that listing
   * exists.
   */
  public List<ApplicationView> applicationsOf(RegEnvironment environment) {
    List<ApplicationView> scoped = new ArrayList<>();
    for (RegService service : registry.linksOf(environment.id())) {
      if (service.target() != CdDeploymentTarget.SINGLETON) {
        scoped.add(new ApplicationView(service, environment.id(), environment.name()));
      }
    }
    return List.copyOf(scoped);
  }

  /**
   * Every application cd deploys, flat: one row per environment link, one row per singleton.
   *
   * <p>Flat because a singleton belongs to no environment — reading the registry through the
   * environments would leave qits-idp and qits-serviceregistry out of it, which are the two a reader
   * most wants to find.
   */
  public List<ApplicationView> allApplications() {
    Map<String, String> environmentNames = new LinkedHashMap<>();
    for (RegEnvironment environment : registry.environments()) {
      environmentNames.put(environment.id(), environment.name());
    }
    List<ApplicationView> views = new ArrayList<>();
    for (RegService service : registry.services()) {
      if (service.target() == CdDeploymentTarget.SINGLETON || service.environmentIds().isEmpty()) {
        views.add(new ApplicationView(service, null, null));
        continue;
      }
      for (String environmentId : service.environmentIds()) {
        views.add(
            new ApplicationView(service, environmentId, environmentNames.get(environmentId)));
      }
    }
    return List.copyOf(views);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
