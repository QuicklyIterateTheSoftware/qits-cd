package eu.wohlben.qits.cd.dockerhost;

import eu.wohlben.qits.cd.control.CdIdentifiers;
import eu.wohlben.qits.cd.control.CdProcess;
import eu.wohlben.qits.cd.control.DeploymentDriver;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DeploymentDriver}: shells the docker CLI via {@link
 * CdProcess} ({@code ProcessBuilder}, never a shell — nothing is ever re-split). cd's whole docker
 * vocabulary is here: {@code pull}, {@code run}, {@code inspect}, {@code logs}, {@code rm}, {@code
 * ps}, {@code network} create/inspect/rm. {@code exec} is not in it and must not enter it — what a
 * deployed container runs is its image's own entrypoint, and cd's relationship with it ends at
 * lifecycle.
 *
 * <p><b>The health gate runs inside the container, on purpose.</b> cd never joins an environment's
 * network, so it cannot probe the fresh container itself; instead the {@code docker run} carries a
 * {@code --health-cmd} curl'ing localhost, and {@link #awaitHealthy} polls {@code docker inspect}
 * for docker's own verdict. The image contract that buys: the image carries {@code curl} and the
 * application listens on 8080 (both platform conventions). An image without curl fails the gate —
 * visibly, with the health log in the deployment's detail.
 *
 * <p><b>Containers run detached with {@code --restart unless-stopped} and are removed
 * explicitly.</b> A deployed application must survive a docker daemon restart and a qits-cd
 * restart both; every removal is a decision recorded on a deployment row (a decommission, a failed
 * cutover, a teardown), never a side effect.
 */
@ApplicationScoped
public class DockerDeploymentDriver implements DeploymentDriver {

  private static final Logger LOG = Logger.getLogger(DockerDeploymentDriver.class);

  private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration RUN_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(10);

  /** How often {@link #awaitHealthy} asks docker for its verdict. */
  private static final Duration HEALTH_POLL = Duration.ofMillis(500);

  /** Lines of container log kept as a failed gate's diagnosis. */
  private static final String LOG_TAIL_LINES = "200";

  /** The label every container cd starts carries — the teardown finds them by it. */
  static final String ENVIRONMENT_LABEL = "qits.cd.environment";

  static final String APPLICATION_LABEL = "qits.cd.application";
  static final String DEPLOYMENT_LABEL = "qits.cd.deployment";

  /**
   * What docker says when the registry answered "no such image". Matched case-insensitively over
   * the pull's combined output to tell {@code IMAGE_MISSING} from a docker that is down — brittle
   * by nature (docker's wording is not an API), so the match errs toward {@code ERROR}: an
   * unrecognized failure is a failed deployment, never a false "nothing published an image".
   */
  private static final List<String> IMAGE_MISSING_MARKERS =
      List.of(
          "manifest unknown",
          "not found",
          "name unknown",
          "repository does not exist",
          "pull access denied");

  @ConfigProperty(name = "qits.cd.container-runtime")
  String runtime;

  @ConfigProperty(name = "qits.cd.pull-timeout-seconds")
  long pullTimeoutSeconds;

  @ConfigProperty(name = "qits.cd.health-interval-seconds")
  long healthIntervalSeconds;

  @ConfigProperty(name = "qits.cd.health-retries")
  int healthRetries;

  @ConfigProperty(name = "qits.cd.health-start-period-seconds")
  long healthStartPeriodSeconds;

  @ConfigProperty(name = "qits.cd.output-max-chars")
  int outputMaxChars;

  @Override
  public void ensureNetwork(String network) {
    if (CdProcess.run(null, List.of(runtime, "network", "inspect", network), CLEANUP_TIMEOUT, 8192)
            .exitCode()
        == 0) {
      return;
    }
    CdProcess.Result create =
        CdProcess.run(null, List.of(runtime, "network", "create", network), CLEANUP_TIMEOUT, 8192);
    if (create.exitCode() != 0) {
      LOG.warnf("Could not ensure network '%s': %s", network, create.output());
    }
  }

  @Override
  public void removeNetwork(String network) {
    CdProcess.Result result =
        CdProcess.run(null, List.of(runtime, "network", "rm", network), CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove network '%s': %s", network, result.output());
    }
  }

  @Override
  public PullResult pull(String imageRef) {
    CdProcess.Result result =
        CdProcess.run(
            null,
            List.of(runtime, "pull", imageRef),
            Duration.ofSeconds(pullTimeoutSeconds),
            outputMaxChars);
    if (result.exitCode() == 0 && !result.timedOut()) {
      return new PullResult(PullOutcome.OK, null);
    }
    String output = result.output() == null ? "" : result.output();
    String lowered = output.toLowerCase(Locale.ROOT);
    boolean missing = IMAGE_MISSING_MARKERS.stream().anyMatch(lowered::contains);
    return new PullResult(missing ? PullOutcome.IMAGE_MISSING : PullOutcome.ERROR, output);
  }

  @Override
  public StartResult start(StartSpec spec) {
    CdProcess.Result result = CdProcess.run(null, buildArgv(spec), RUN_TIMEOUT, outputMaxChars);
    if (result.exitCode() != 0 || result.timedOut()) {
      LOG.warnf("Could not start container %s: %s", spec.containerName(), result.output());
      return new StartResult(false, result.output());
    }
    LOG.debugf("Started container %s (%s)", spec.containerName(), spec.imageRef());
    return new StartResult(true, null);
  }

  @Override
  public HealthResult awaitHealthy(String containerName, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      CdProcess.Result inspected =
          CdProcess.run(
              null,
              List.of(
                  runtime,
                  "inspect",
                  "--format",
                  // Status is `running`/`exited`/`dead`; Health.Status only exists because every
                  // run here carries a --health-cmd.
                  "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}",
                  containerName),
              INSPECT_TIMEOUT,
              8192);
      if (inspected.exitCode() != 0) {
        return new HealthResult(false, "container vanished: " + inspected.output());
      }
      String state = inspected.output() == null ? "" : inspected.output().strip();
      if (state.endsWith("/healthy")) {
        return new HealthResult(true, null);
      }
      boolean stillComing = state.startsWith("running/");
      if (!stillComing || state.endsWith("/unhealthy")) {
        return new HealthResult(false, "container " + state + "\n" + logs(containerName));
      }
      try {
        Thread.sleep(HEALTH_POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new HealthResult(false, "interrupted while waiting on the health gate");
      }
    }
    return new HealthResult(
        false, "health gate not passed within " + timeout.toSeconds() + "s\n" + logs(containerName));
  }

  @Override
  public void remove(String containerName) {
    CdProcess.Result result =
        CdProcess.run(null, List.of(runtime, "rm", "-f", containerName), CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove container %s: %s", containerName, result.output());
    }
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    CdProcess.Result listed =
        CdProcess.run(
            null,
            List.of(
                runtime, "ps", "-aq", "--filter", "label=" + ENVIRONMENT_LABEL + "=" + environmentId),
            CLEANUP_TIMEOUT,
            8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers of environment %s: %s", environmentId, listed.output());
      return 0;
    }
    List<String> ids =
        Arrays.stream((listed.output() == null ? "" : listed.output()).split("\\R"))
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .toList();
    if (ids.isEmpty()) {
      return 0;
    }
    List<String> argv = new ArrayList<>(List.of(runtime, "rm", "-f"));
    argv.addAll(ids);
    CdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    return ids.size();
  }

  /** A bounded tail of the container's own output — the diagnosis of a failed health gate. */
  private String logs(String containerName) {
    CdProcess.Result result =
        CdProcess.run(
            null,
            List.of(runtime, "logs", "--tail", LOG_TAIL_LINES, containerName),
            CLEANUP_TIMEOUT,
            outputMaxChars);
    return result.output() == null ? "" : result.output();
  }

  /** Package-private for argv assembly tests. */
  List<String> buildArgv(StartSpec spec) {
    // Everything reaching this argv was validated at the boundary; the health path is re-checked
    // here because it is the one value that lands inside a shell string the CONTAINER runs — the
    // allowlist is the guard, and this is the last line before it.
    CdIdentifiers.requireHealthPath(spec.healthPath());
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(spec.containerName());
    // One network per environment; the alias is what peers in the environment resolve, and it
    // stays stable across deployments while container names do not.
    argv.add("--network");
    argv.add(spec.network());
    argv.add("--network-alias");
    argv.add(spec.applicationName());
    // A deployed application outlives its deployer and a daemon restart both. `unless-stopped`
    // rather than `always`: a decommissioned container is stopped before removal and must not race
    // its own restart.
    argv.add("--restart");
    argv.add("unless-stopped");
    argv.add("--label");
    argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    argv.add("--label");
    argv.add(APPLICATION_LABEL + "=" + spec.applicationId());
    argv.add("--label");
    argv.add(DEPLOYMENT_LABEL + "=" + spec.deploymentId());
    // The health gate, enforced by docker inside the container (see the class javadoc). The path
    // is allowlist-validated; nothing else in this string varies.
    argv.add("--health-cmd");
    argv.add("curl -fsS http://localhost:8080" + spec.healthPath() + " || exit 1");
    argv.add("--health-interval");
    argv.add(healthIntervalSeconds + "s");
    argv.add("--health-retries");
    argv.add(String.valueOf(healthRetries));
    argv.add("--health-start-period");
    argv.add(healthStartPeriodSeconds + "s");
    // Who and where this container is, for its own logs/telemetry. Deliberately minimal —
    // application config (datasources, peers) is the image's and the environment's own story.
    env(argv, "QITS_ENVIRONMENT", spec.environmentName());
    env(argv, "QITS_APPLICATION", spec.applicationName());
    argv.add(spec.imageRef());
    return List.copyOf(argv);
  }

  private static void env(List<String> argv, String key, String value) {
    argv.add("--env");
    argv.add(key + "=" + (value == null ? "" : value));
  }
}
