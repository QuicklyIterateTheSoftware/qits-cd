package eu.wohlben.qits.cd.control;

import java.time.Duration;

/**
 * The seam between cd's orchestration and the host's docker daemon — the {@code CiStepRunner}
 * arrangement: this module owns the interface and the state machine that calls it, {@code
 * service/dockerhost} owns the sole production implementation (shelling the docker CLI), and the
 * suites install a scripted fake so a clone's {@code mvn verify} needs no docker.
 *
 * <p>Everything crossing this seam is ids, names and references — never entities. The driver knows
 * nothing about environments or deployments; it starts, watches and removes containers.
 */
public interface DeploymentDriver {

  /** Best-effort ensure the named docker network exists — warn, never fail, when docker is absent. */
  void ensureNetwork(String network);

  /** Best-effort remove the named docker network (it may still hold containers; docker refuses). */
  void removeNetwork(String network);

  /** {@code docker pull} the reference so a missing image is its own recorded outcome. */
  PullResult pull(String imageRef);

  /** Start the container, detached, on the environment's network. The image's entrypoint runs. */
  StartResult start(StartSpec spec);

  /**
   * Park until the container's own health gate answers: healthy, unhealthy/dead (with the
   * container's log tail as the diagnosis), or the deadline.
   */
  HealthResult awaitHealthy(String containerName, Duration timeout);

  /** Remove the container, running or not. Every decommission and every failed cutover ends here. */
  void remove(String containerName);

  /** Remove every container labelled as belonging to the environment. Returns how many there were. */
  int removeEnvironmentContainers(String environmentId);

  /** Everything one container is started with. */
  record StartSpec(
      String environmentId,
      String environmentName,
      String applicationId,
      String applicationName,
      String deploymentId,
      String network,
      String imageRef,
      String containerName,
      String healthPath) {}

  enum PullOutcome {
    OK,
    /** The registry answered and has no such image — the deployment's {@code IMAGE_MISSING}. */
    IMAGE_MISSING,
    /** Docker failed some other way (daemon absent, registry unreachable, ...). */
    ERROR
  }

  record PullResult(PullOutcome outcome, String detail) {}

  record StartResult(boolean started, String detail) {}

  record HealthResult(boolean healthy, String detail) {}
}
