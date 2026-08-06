package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import eu.wohlben.qits.cd.error.ConflictException;
import eu.wohlben.qits.cd.error.NotFoundException;
import eu.wohlben.qits.cd.persistence.CdApplicationRepository;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import eu.wohlben.qits.cd.persistence.CdEnvironmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle: creation with the conventions filled in (branch {@code
 * environment/<name>}, bundle network {@code qits-env-<name>}), rename/retarget, and teardown.
 *
 * <p>An environment is a <b>tier</b> and is created deliberately — nothing derives one. What is
 * derived is everything inside it: a green build on the environment's branch registers the
 * repository's application here ({@link DeployService}), so this service creates the tier and the
 * builds fill it.
 *
 * <p>The docker side of create and delete is best-effort and happens <b>after</b> the transaction:
 * an environment whose network could not be created yet is still an environment (the driver
 * re-ensures every network before every deployment), and a teardown must not roll back the delete
 * because a container was already gone. The rows are the truth; docker is made to match it.
 *
 * <p>Transactions are programmatic ({@link QuarkusTransaction#requiringNew()}, the ci stance)
 * rather than {@code @Transactional} — partly for symmetry with the worker in {@link
 * DeployService}, which has no request context, and partly because the bracket then cannot be lost
 * to a self-invocation that never crosses the interceptor.
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

  @Inject CdEnvironmentRepository environments;
  @Inject CdApplicationRepository applications;
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
   * One application of a creation request.
   *
   * @deprecated applications are derived from the repository's {@code deployments.yml} on every
   *     green build; naming them at creation only pre-creates rows the next build would create
   *     anyway. Accepted so an older bootstrap keeps working.
   */
  @Deprecated
  public record ApplicationSpec(String repoId, String name, String healthPath) {}

  /** {@code apps} may be null — the shape every caller should send. */
  public CdEnvironment create(
      String name, String branch, String network, List<ApplicationSpec> apps) {
    List<ApplicationSpec> declared = apps == null ? List.of() : apps;
    CdIdentifiers.requireName(name, "environment name");
    String effectiveBranch =
        CdIdentifiers.requireBranch(isBlank(branch) ? BRANCH_PREFIX + name : branch);
    String effectiveNetwork =
        isBlank(network)
            ? CdNetworks.bundle(name)
            : CdIdentifiers.requireName(network, "network name");
    Set<String> seenNames = new HashSet<>();
    for (ApplicationSpec app : declared) {
      CdIdentifiers.requireRepoId(app.repoId());
      CdIdentifiers.requireName(app.name(), "application name");
      if (app.healthPath() != null) {
        CdIdentifiers.requireHealthPath(app.healthPath());
      }
      if (!seenNames.add(app.name())) {
        throw new ConflictException("Duplicate application name: " + app.name());
      }
    }

    CdEnvironment environment =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  if (environments.findByName(name).isPresent()) {
                    throw new ConflictException("Environment already exists: " + name);
                  }
                  CdEnvironment env = new CdEnvironment();
                  env.id = UUID.randomUUID().toString();
                  env.name = name;
                  env.branch = effectiveBranch;
                  env.network = effectiveNetwork;
                  env.createdAt = Instant.now();
                  environments.persist(env);
                  for (ApplicationSpec app : declared) {
                    CdApplication application = new CdApplication();
                    application.id = UUID.randomUUID().toString();
                    application.environment = env;
                    application.repoId = app.repoId();
                    application.name = app.name();
                    application.healthPath = app.healthPath();
                    application.createdAt = Instant.now();
                    applications.persist(application);
                  }
                  return env;
                });
    // After the commit, best-effort: a deployment re-ensures the network anyway, and an
    // environment must not fail to exist because docker is momentarily unreachable.
    driver.ensureNetwork(
        new DeploymentDriver.Network(
            environment.network,
            environment.id,
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
  public CdEnvironment update(String environmentId, String name, String branch) {
    String newName = isBlank(name) ? null : CdIdentifiers.requireName(name, "environment name");
    String newBranch = isBlank(branch) ? null : CdIdentifiers.requireBranch(branch);
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              CdEnvironment environment = require(environmentId);
              if (newName != null && !newName.equals(environment.name)) {
                if (environments.findByName(newName).isPresent()) {
                  throw new ConflictException("Environment already exists: " + newName);
                }
                environment.name = newName;
              }
              if (newBranch != null) {
                environment.branch = newBranch;
              }
              return environment;
            });
  }

  /**
   * Tear the environment down: rows first (the truth), then the containers and the networks,
   * best-effort — a teardown must succeed even when docker already lost the containers.
   *
   * <p>The order between the last two steps is load-bearing. Singletons live on this environment's
   * per-application networks without belonging to the environment, so they survive the container
   * reap and would then hold every network open — docker refuses to remove a network with an
   * endpoint on it. They are disconnected first, and only the networks THIS environment owns (its
   * bundle plus everything labelled with its id) are removed, so a singleton keeps every other
   * environment it serves.
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
    CdEnvironment environment = require(environmentId);
    String legacy = legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).orElse(null);
    Set<String> networks = new LinkedHashSet<>();
    networks.add(environment.network);
    for (DeploymentDriver.Network network : driver.networks()) {
      if (environmentId.equals(network.environmentId())) {
        networks.add(network.name());
      }
    }
    if (networks.remove(legacy)) {
      LOG.infof(
          "Environment %s was on the legacy network '%s' — left in place, it is the platform's",
          environment.name, legacy);
    }
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              deployments.delete("application.environment.id = ?1", environmentId);
              applications.delete("environment.id = ?1", environmentId);
              environments.deleteById(environmentId);
            });
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
  }

  public CdEnvironment require(String environmentId) {
    return environments
        .findByIdOptional(environmentId)
        .orElseThrow(() -> new NotFoundException("No such environment: " + environmentId));
  }

  public List<CdEnvironment> list() {
    return environments.listNewestFirst();
  }

  public List<CdApplication> applicationsOf(String environmentId) {
    return applications.listByEnvironment(environmentId);
  }

  /** Every application row: the environments' and the singletons', for the registry view. */
  public List<CdApplication> allApplications() {
    return new ArrayList<>(applications.listAll());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
