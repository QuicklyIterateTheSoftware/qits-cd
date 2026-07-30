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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Environment lifecycle: creation (from the epic orchestration's payload, with the conventions
 * filled in — branch {@code epic/<name>}, network {@code qits-env-<name>}) and teardown.
 *
 * <p>The docker side of both is best-effort and happens <b>after</b> the transaction: an
 * environment whose network could not be created yet is still an environment (the driver re-ensures
 * the network before every deployment), and a teardown must not roll back the delete because a
 * container was already gone. The rows are the truth; docker is made to match it.
 *
 * <p>Transactions are programmatic ({@link QuarkusTransaction#requiringNew()}, the ci stance)
 * rather than {@code @Transactional} — partly for symmetry with the worker in {@link
 * DeployService}, which has no request context, and partly because the bracket then cannot be lost
 * to a self-invocation that never crosses the interceptor.
 */
@ApplicationScoped
public class EnvironmentService {

  private static final Logger LOG = Logger.getLogger(EnvironmentService.class);

  /** The branch an environment listens to when its creator names none: the epic branch. */
  public static final String BRANCH_PREFIX = "epic/";

  /** The per-environment docker network when the creator names none. */
  public static final String NETWORK_PREFIX = "qits-env-";

  @Inject CdEnvironmentRepository environments;
  @Inject CdApplicationRepository applications;
  @Inject CdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;

  /** One application of a creation request — validated here, not before. */
  public record ApplicationSpec(String repoId, String name, String healthPath) {}

  public CdEnvironment create(
      String name, String branch, String network, List<ApplicationSpec> apps) {
    CdIdentifiers.requireName(name, "environment name");
    String effectiveBranch =
        CdIdentifiers.requireBranch(isBlank(branch) ? BRANCH_PREFIX + name : branch);
    String effectiveNetwork =
        isBlank(network)
            ? NETWORK_PREFIX + name
            : CdIdentifiers.requireName(network, "network name");
    Set<String> seenNames = new HashSet<>();
    for (ApplicationSpec app : apps) {
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
                  for (ApplicationSpec app : apps) {
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
    driver.ensureNetwork(environment.network);
    return environment;
  }

  /**
   * Tear the environment down: rows first (the truth), then the containers and the network,
   * best-effort — a teardown must succeed even when docker already lost the containers.
   */
  public void delete(String environmentId) {
    CdEnvironment environment = require(environmentId);
    String network = environment.network;
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
    driver.removeNetwork(network);
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

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
