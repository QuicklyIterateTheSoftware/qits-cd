package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import java.time.Duration;
import java.util.List;

/**
 * The seam between cd's orchestration and the host's docker daemon — the {@code CiStepRunner}
 * arrangement: this module owns the interface and the state machine that calls it, {@code
 * service/dockerhost} owns the sole production implementation (shelling the docker CLI), and the
 * suites install a scripted fake so a clone's {@code mvn verify} needs no docker.
 *
 * <p>Everything crossing this seam is ids, names and references — never entities. The driver knows
 * nothing about environments or deployments; it starts, watches and removes containers, and it
 * makes and joins networks.
 *
 * <p><b>Docker is the membership bookkeeping.</b> Which container sits on which network is never
 * stored in cd's database — it is read back from the labels below. One record of the truth, and it
 * is the runtime's, so a row cannot describe a topology docker does not have.
 */
public interface DeploymentDriver {

  /** The environment a container belongs to. Absent on singletons: they belong to no tier. */
  String ENVIRONMENT_LABEL = "qits.cd.environment";

  String APPLICATION_LABEL = "qits.cd.application";
  String DEPLOYMENT_LABEL = "qits.cd.deployment";

  /** {@code environment} or {@code singleton} — what a reconciliation looks a container up by. */
  String TARGET_LABEL = "qits.cd.target";

  /** {@code true} on an environment's public nodes — the other half of the reconciliation lookup. */
  String AVAILABLE_ON_ENV_LABEL = "qits.cd.available-on-env";

  /** On networks: {@code bundle}, {@code application} or {@code platform}. */
  String NETWORK_LABEL = "qits.cd.network";

  /** On per-application networks: whose it is. */
  String APP_NAME_LABEL = "qits.cd.app-name";

  /** What a network cd made is for. */
  enum NetworkKind {
    /** An environment's public nodes ({@code availableOnEnv}). */
    BUNDLE,
    /** One application of one environment — its own containers and its joined hub. */
    APPLICATION,
    /** Where singletons run. Belongs to no environment. */
    PLATFORM
  }

  /** A network cd made, as its labels describe it. {@code environmentId} is null on PLATFORM. */
  record Network(String name, String environmentId, NetworkKind kind, String applicationName) {}

  /**
   * Best-effort ensure the network exists, labelled — warn, never fail, when docker is absent.
   *
   * <p>Returns whether this call <b>created</b> it. The reconciliation deliberately does not hang
   * off that answer — a network outlives the deployment that made it, so who belongs on it is
   * recomputed every time rather than joined once. An already-existing network keeps whatever
   * labels it has: adopting an unlabelled network made outside cd (the platform's own {@code
   * qits-net}) stays supported, deliberately.
   */
  boolean ensureNetwork(Network spec);

  /** Best-effort remove the named docker network (it may still hold containers; docker refuses). */
  void removeNetwork(String network);

  /** Every network cd labelled — the membership bookkeeping, read back from the runtime. */
  List<Network> networks();

  /**
   * Join the container to the network under the alias.
   *
   * <p><b>Already joined counts as joined.</b> Docker answers an existing endpoint with a non-zero
   * exit and a message naming it, and telling that apart from a refusal is this seam's job — the
   * wording is docker's, so it is matched where docker's other wordings are matched. Everything
   * else is a real failure and is reported as one: a membership the caller asked for and did not
   * get leaves a container nobody can address, which no health gate can see.
   */
  ConnectResult connect(String network, String container, String alias);

  /** Whether the container is on the network now, and what docker said when it is not. */
  record ConnectResult(boolean joined, String detail) {}

  /** Leave the network. Not being on it is not an error. */
  void disconnect(String network, String container);

  /** The running containers of an environment's public nodes — one half of a reconciliation. */
  List<Endpoint> hubContainers(String environmentId);

  /** Every running singleton container — the other half; singletons are everywhere by design. */
  List<Endpoint> singletonContainers();

  /**
   * A container a reconciliation joins to a new network, and the alias it must keep there. The
   * alias is the application name, and joining without it would put the container on the network
   * under nothing but its own deployment-suffixed container name — reachable by an address no peer
   * has ever been told.
   */
  record Endpoint(String id, String alias) {}

  /** {@code docker pull} the reference so a missing image is its own recorded outcome. */
  PullResult pull(String imageRef);

  /**
   * The containers currently answering to the application's alias anywhere in the given networks —
   * the predecessors a replace cutover stops, whoever started them: a prior deployment, or an
   * original this platform's bootstrap seeded outside cd.
   *
   * <p>The list is the <b>union</b> of everything the fresh container is about to be on, legacy
   * network included. That breadth is what finds a predecessor still living on the old topology: a
   * container started before per-application networks existed holds its alias on {@code qits-net}
   * and nowhere else, and a search of the new networks alone would start a second copy beside it.
   *
   * <p>The breadth is also why each holder reports the environment it belongs to: the legacy
   * network is shared by every tier, so the union sees another environment's copy of the same
   * application under the same alias. Deciding which of them is a predecessor is the caller's, and
   * {@link Holder#environmentId()} is what it decides on.
   */
  List<Holder> aliasHolders(List<String> networks, String alias);

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

  /** Start the container, detached, on its primary network. The image's entrypoint runs. */
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

  /**
   * One container holding an application's alias: the full docker id, the container name, and the
   * environment it was started for.
   *
   * <p>{@code environmentId} is the container's {@value #ENVIRONMENT_LABEL} label and is <b>null
   * for two very different containers</b>: a singleton, which belongs to no tier by design, and a
   * container from before cd labelled anything — a compose original, or one the previous cd
   * started. Both are adoptable by whoever is deploying, which is why they share the null: a
   * predecessor nobody has claimed is claimed by the deployment that finds it.
   */
  record Holder(String id, String name, String environmentId) {}

  /**
   * Everything one container is started with.
   *
   * <p>{@code commitSha} is carried beside {@code imageRef} rather than parsed back out of it: it
   * is the deployment's own identity — the sha the row was created with and the image was
   * addressed by — and it becomes the container's {@code service.version} resource attribute.
   *
   * <p>{@code network} is the <b>primary</b> one, the only one {@code docker run} can take: the
   * application's own network for an environment application, {@code qits-platform} for a
   * singleton. Every further membership is a join after the start, because docker allows exactly
   * one network at run time.
   *
   * <p>{@code environmentId} and {@code environmentName} are null on a singleton, which is what
   * leaves it without an environment label — an environment teardown reaps by that label, and it
   * must never take a platform-plane container with it.
   */
  record StartSpec(
      String environmentId,
      String environmentName,
      String applicationId,
      String applicationName,
      String deploymentId,
      String commitSha,
      String network,
      String imageRef,
      String containerName,
      String healthPath,
      CdDeploymentTarget target,
      boolean availableOnEnv) {}

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
