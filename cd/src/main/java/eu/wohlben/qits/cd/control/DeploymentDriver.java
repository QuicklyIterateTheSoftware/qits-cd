package eu.wohlben.qits.cd.control;

import java.time.Duration;
import java.util.List;

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

  /**
   * The containers currently answering to the application's alias on the environment's network —
   * the predecessors a replace cutover stops, whoever started them: a prior deployment, or an
   * original this platform's bootstrap seeded outside cd.
   */
  List<Holder> aliasHolders(String network, String alias);

  /** Stop the container, leaving it restartable — the first half of the replace cutover. */
  void stop(String containerName);

  /** Start a container {@link #stop} left behind — the rollback of a failed gate. */
  void restart(String containerName);

  /**
   * This process's own container id ({@code /etc/hostname} in a container), blank when unknown —
   * what routes a deployment of cd itself onto the handoff path: cd must never stop the instance
   * performing the deployment.
   */
  String selfContainerId();

  /** The full docker id of the named container, blank when it does not exist. */
  String containerId(String containerName);

  /**
   * Launch the detached self-update referee: stop the old container (freeing the H2 lock the
   * successor is retrying on), await the successor's health gate, then remove the old container —
   * or, on a missed gate, remove the successor and restart the old. Detached because neither
   * instance can referee its own succession: the old is about to be stopped and the new cannot
   * boot until it is.
   */
  void handoff(HandoffSpec spec);

  /** Everything the referee needs: who retires, who succeeds, and how long the gate may take. */
  record HandoffSpec(
      String imageRef, String oldContainerId, String newContainerName, long timeoutSeconds) {}

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

  /** One container holding an application's alias: the full docker id and the container name. */
  record Holder(String id, String name) {}

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
