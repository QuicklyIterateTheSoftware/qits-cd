package eu.wohlben.qits.cd.dockerhost;

import eu.wohlben.qits.cd.control.CdIdentifiers;
import eu.wohlben.qits.cd.control.CdProcess;
import eu.wohlben.qits.cd.control.DeploymentDriver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The sole production implementation of {@link DeploymentDriver}: shells the docker CLI via {@link
 * CdProcess} ({@code ProcessBuilder}, never a shell — nothing is ever re-split). cd's whole docker
 * vocabulary is here: {@code pull}, {@code run}, {@code inspect}, {@code logs}, {@code rm}, {@code
 * ps}, {@code network} create/inspect/ls/connect/disconnect/rm. {@code exec} is not in it and must
 * not enter it — what a deployed container runs is its image's own entrypoint, and cd's
 * relationship with it ends at lifecycle.
 *
 * <p><b>Labels are the membership bookkeeping.</b> Which containers sit on which networks is never
 * stored in cd's database; it is written as docker labels at {@code run}/{@code network create} and
 * read back with {@code --filter label=}. One record of the truth, and it is the runtime's.
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

  /**
   * The name a container carries its application by. Not in the pinned label set, and here because
   * a reconciliation joins a <i>running</i> container to a network and has to give it the right
   * alias there — the application id it already carried names the row, not the address peers use.
   */
  static final String APP_NAME_LABEL = DeploymentDriver.APP_NAME_LABEL;

  /** What {@code deployment.environment.name} says for a container that is in every environment. */
  static final String SINGLETON_ENVIRONMENT = "platform";

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

  /**
   * The prefix of the per-application run-argument family: {@code
   * qits.cd.run-args.<application-name>} holds extra {@code docker run} arguments (volumes, env,
   * ports — whatever the deployment decides its application needs), whitespace-split and appended
   * verbatim between cd's own flags and the image reference. Deployment config is the ONLY source
   * — never the API, never the intake — which is what keeps the trust domain the one that already
   * holds the docker socket. Package-private for the argv tests.
   */
  static final String RUN_ARGS_PREFIX = "qits.cd.run-args.";

  /** Looked up per key rather than {@code @ConfigProperty}: the key carries the application name. */
  @Inject Config config;

  /** Mounted into the handoff referee, which drives docker exactly like this process does. */
  @ConfigProperty(name = "qits.cd.docker-socket-path")
  String dockerSocketPath;

  @Override
  public boolean ensureNetwork(Network spec) {
    if (CdProcess.run(
                null,
                List.of(runtime, "network", "inspect", spec.name()),
                CLEANUP_TIMEOUT,
                8192)
            .exitCode()
        == 0) {
      // Already there, labels and all — including a network made outside cd. Adopting the
      // platform's own qits-net rather than insisting on labelling it is deliberate: the labels
      // are how cd FINDS the networks it made, not a claim of ownership over every network.
      return false;
    }
    CdProcess.Result create =
        CdProcess.run(null, buildNetworkCreateArgv(spec), CLEANUP_TIMEOUT, 8192);
    if (create.exitCode() != 0) {
      LOG.warnf("Could not ensure network '%s': %s", spec.name(), create.output());
      return false;
    }
    LOG.debugf("Created network %s (%s)", spec.name(), spec.kind());
    return true;
  }

  /** Package-private for the argv test: the labels are how every later lookup finds this network. */
  List<String> buildNetworkCreateArgv(Network spec) {
    List<String> argv = new ArrayList<>(List.of(runtime, "network", "create"));
    argv.add("--label");
    argv.add(NETWORK_LABEL + "=" + spec.kind().name().toLowerCase(Locale.ROOT));
    if (spec.environmentId() != null) {
      argv.add("--label");
      argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    if (spec.applicationName() != null) {
      argv.add("--label");
      argv.add(APP_NAME_LABEL + "=" + spec.applicationName());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  @Override
  public List<Network> networks() {
    CdProcess.Result listed =
        CdProcess.run(
            null,
            List.of(
                runtime,
                "network",
                "ls",
                "--filter",
                "label=" + NETWORK_LABEL,
                "--format",
                "{{.Name}}|{{.Labels}}"),
            CLEANUP_TIMEOUT,
            outputMaxChars);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list cd's networks: %s", listed.output());
      return List.of();
    }
    return parseNetworks(listed.output());
  }

  /** Package-private for the parsing test: one {@code name|k=v,k=v} line per network. */
  static List<Network> parseNetworks(String output) {
    List<Network> networks = new ArrayList<>();
    for (String line : (output == null ? "" : output).split("\\R")) {
      String[] parts = line.trim().split("\\|", 2);
      if (parts.length < 2 || parts[0].isEmpty()) {
        continue;
      }
      String environmentId = null;
      String applicationName = null;
      NetworkKind kind = null;
      for (String label : parts[1].split(",")) {
        int equals = label.indexOf('=');
        if (equals < 0) {
          continue;
        }
        String key = label.substring(0, equals).trim();
        String value = label.substring(equals + 1).trim();
        switch (key) {
          case ENVIRONMENT_LABEL -> environmentId = value;
          case APP_NAME_LABEL -> applicationName = value;
          case NETWORK_LABEL -> kind = kind(value);
          default -> {
            /* someone else's label */
          }
        }
      }
      if (kind != null) {
        networks.add(new Network(parts[0], environmentId, kind, applicationName));
      }
    }
    return List.copyOf(networks);
  }

  private static NetworkKind kind(String value) {
    for (NetworkKind candidate : NetworkKind.values()) {
      if (candidate.name().equalsIgnoreCase(value)) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public void connect(String network, String container, String alias) {
    List<String> argv = new ArrayList<>(List.of(runtime, "network", "connect"));
    if (alias != null && !alias.isBlank()) {
      argv.add("--alias");
      argv.add(alias);
    }
    argv.add(network);
    argv.add(container);
    CdProcess.Result result = CdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      // Already-joined answers non-zero and is the normal outcome of the pre-deploy self-heal.
      LOG.debugf("Could not join %s to '%s': %s", container, network, result.output());
    }
  }

  @Override
  public void disconnect(String network, String container) {
    CdProcess.Result result =
        CdProcess.run(
            null,
            List.of(runtime, "network", "disconnect", network, container),
            CLEANUP_TIMEOUT,
            8192);
    if (result.exitCode() != 0) {
      LOG.debugf("Could not remove %s from '%s': %s", container, network, result.output());
    }
  }

  @Override
  public List<Endpoint> hubContainers(String environmentId) {
    return endpoints(
        List.of(
            "label=" + AVAILABLE_ON_ENV_LABEL + "=true",
            "label=" + ENVIRONMENT_LABEL + "=" + environmentId));
  }

  @Override
  public List<Endpoint> singletonContainers() {
    return endpoints(List.of("label=" + TARGET_LABEL + "=singleton"));
  }

  /** Running containers matching every filter, each with the alias it must keep on a new network. */
  private List<Endpoint> endpoints(List<String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtime, "ps", "-q"));
    for (String filter : filters) {
      argv.add("--filter");
      argv.add(filter);
    }
    CdProcess.Result listed = CdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers %s: %s", filters, listed.output());
      return List.of();
    }
    List<String> ids = idLines(listed.output());
    if (ids.isEmpty()) {
      return List.of();
    }
    List<String> inspect =
        new ArrayList<>(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{.Id}}|{{index .Config.Labels \"" + APP_NAME_LABEL + "\"}}"));
    inspect.addAll(ids);
    CdProcess.Result inspected = CdProcess.run(null, inspect, CLEANUP_TIMEOUT, outputMaxChars);
    if (inspected.exitCode() != 0) {
      LOG.debugf("Could not inspect containers %s: %s", filters, inspected.output());
      return List.of();
    }
    return parseEndpoints(inspected.output());
  }

  /** Package-private for the parsing test: one {@code id|app-name} line per container. */
  static List<Endpoint> parseEndpoints(String output) {
    List<Endpoint> endpoints = new ArrayList<>();
    for (String line : (output == null ? "" : output).split("\\R")) {
      String[] parts = line.trim().split("\\|", 2);
      if (parts.length < 2 || parts[0].isEmpty() || parts[1].isBlank()) {
        continue;
      }
      endpoints.add(new Endpoint(parts[0], parts[1].trim()));
    }
    return List.copyOf(endpoints);
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
  public List<Holder> aliasHolders(List<String> networks, String alias) {
    // The UNION over every network the fresh container is about to be on, legacy one included.
    // A `docker ps --filter network=a --filter network=b` is an OR over networks, so one call
    // answers the whole question — and a container on two of them appears once.
    List<String> argv = new ArrayList<>(List.of(runtime, "ps", "-q"));
    for (String network : networks) {
      argv.add("--filter");
      argv.add("network=" + network);
    }
    CdProcess.Result listed = CdProcess.run(null, argv, CLEANUP_TIMEOUT, 8192);
    if (listed.exitCode() != 0) {
      LOG.debugf("Could not list containers on %s: %s", networks, listed.output());
      return List.of();
    }
    List<String> ids = idLines(listed.output());
    if (ids.isEmpty()) {
      return List.of();
    }
    // One inspect for all of them: id|name|every alias the container holds anywhere. A container's
    // own name always resolves on a user-defined network, so it counts as an alias here — that is
    // what lets a replace cutover absorb a predecessor the bootstrap started outside cd. The
    // aliases are read across all networks rather than one: the containers were already filtered
    // to the networks that matter, and a predecessor that holds the alias on the legacy network
    // alone is exactly the one this has to find.
    List<String> inspect =
        new ArrayList<>(
            List.of(
                runtime,
                "inspect",
                "--format",
                "{{.Id}}|{{.Name}}|{{range $net, $conf := .NetworkSettings.Networks}}"
                    + "{{range $conf.Aliases}}{{.}} {{end}}{{end}}"));
    inspect.addAll(ids);
    CdProcess.Result inspected = CdProcess.run(null, inspect, CLEANUP_TIMEOUT, outputMaxChars);
    if (inspected.exitCode() != 0) {
      LOG.debugf("Could not inspect containers on %s: %s", networks, inspected.output());
      return List.of();
    }
    return parseHolders(inspected.output(), alias);
  }

  private static List<String> idLines(String output) {
    return Arrays.stream((output == null ? "" : output).split("\\R"))
        .map(String::trim)
        .filter(id -> !id.isEmpty())
        .toList();
  }

  /** Package-private for the parsing test: one `id|/name|alias alias ...` line per container. */
  static List<Holder> parseHolders(String inspectOutput, String alias) {
    List<Holder> holders = new ArrayList<>();
    for (String line : (inspectOutput == null ? "" : inspectOutput).split("\\R")) {
      String[] parts = line.trim().split("\\|", 3);
      if (parts.length < 2) {
        continue;
      }
      String name = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
      boolean aliased =
          parts.length == 3 && Arrays.asList(parts[2].trim().split("\\s+")).contains(alias);
      if (name.equals(alias) || aliased) {
        holders.add(new Holder(parts[0], name));
      }
    }
    return List.copyOf(holders);
  }

  @Override
  public void stop(String containerName) {
    CdProcess.Result result =
        CdProcess.run(null, List.of(runtime, "stop", containerName), RUN_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not stop container %s: %s", containerName, result.output());
    }
  }

  @Override
  public void restart(String containerName) {
    CdProcess.Result result =
        CdProcess.run(null, List.of(runtime, "start", containerName), RUN_TIMEOUT, 8192);
    if (result.exitCode() != 0) {
      LOG.warnf("Could not restart container %s: %s", containerName, result.output());
    }
  }

  @Override
  public String selfContainerId() {
    try {
      return java.nio.file.Files.readString(java.nio.file.Path.of("/etc/hostname")).strip();
    } catch (Exception e) {
      return "";
    }
  }

  @Override
  public String containerId(String containerName) {
    CdProcess.Result result =
        CdProcess.run(
            null,
            List.of(runtime, "inspect", "--format", "{{.Id}}", containerName),
            INSPECT_TIMEOUT,
            8192);
    if (result.exitCode() != 0 || result.output() == null) {
      return "";
    }
    return result.output().strip();
  }

  @Override
  public void handoff(HandoffSpec spec) {
    // Everything interpolated into this script is cd's own: the old id came from docker, the new
    // name from containerName() (dns-label charset), the timeout from config. The referee runs
    // the deployment's own image — just pulled, guaranteed present — with its entrypoint swapped
    // for the shell, and --rm so a finished referee leaves nothing behind.
    String script =
        String.join(
            "\n",
            "docker stop " + spec.oldContainerId(),
            "t=0",
            "while [ \"$t\" -lt " + spec.timeoutSeconds() + " ]; do",
            "  s=$(docker inspect --format"
                + " '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'"
                + " " + spec.newContainerName() + " 2>/dev/null || echo gone)",
            "  case \"$s\" in running/healthy) docker rm -f " + spec.oldContainerId() + "; exit 0;; esac",
            "  sleep 2",
            "  t=$((t+2))",
            "done",
            "docker rm -f " + spec.newContainerName(),
            "docker start " + spec.oldContainerId());
    CdProcess.Result result =
        CdProcess.run(null, buildHandoffArgv(spec, script), RUN_TIMEOUT, outputMaxChars);
    if (result.exitCode() != 0) {
      LOG.errorf("Could not launch the handoff referee: %s", result.output());
    }
  }

  /** Package-private for the argv test. */
  List<String> buildHandoffArgv(HandoffSpec spec, String script) {
    String suffix =
        spec.newContainerName().length() > 8
            ? spec.newContainerName().substring(spec.newContainerName().length() - 8)
            : spec.newContainerName();
    List<String> argv = new ArrayList<>();
    argv.add(runtime);
    argv.add("run");
    argv.add("-d");
    argv.add("--rm");
    argv.add("--name");
    argv.add("qits-cd-handoff-" + suffix);
    argv.add("-v");
    argv.add(dockerSocketPath + ":" + dockerSocketPath);
    socketGid().ifPresent(gid -> {
      argv.add("--group-add");
      argv.add(gid);
    });
    argv.add("--entrypoint");
    argv.add("/bin/sh");
    argv.add(spec.imageRef());
    argv.add("-c");
    argv.add(script);
    return List.copyOf(argv);
  }

  /** The socket's owning group, so the referee (uid 1001 in the image) may use it. */
  private java.util.Optional<String> socketGid() {
    try {
      Object gid =
          java.nio.file.Files.getAttribute(java.nio.file.Path.of(dockerSocketPath), "unix:gid");
      return java.util.Optional.of(String.valueOf(gid));
    } catch (Exception e) {
      return java.util.Optional.empty();
    }
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
    List<String> ids = idLines(listed.output());
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
    // Docker takes ONE network at run time — the application's own for an environment application,
    // qits-platform for a singleton. Every further membership is a `network connect` after the
    // start (DeployService.desiredJoins). The alias is what peers resolve, and it stays stable
    // across deployments while container names do not.
    argv.add("--network");
    argv.add(spec.network());
    argv.add("--network-alias");
    argv.add(spec.applicationName());
    // A deployed application outlives its deployer and a daemon restart both. `unless-stopped`
    // rather than `always`: a decommissioned container is stopped before removal and must not race
    // its own restart.
    argv.add("--restart");
    argv.add("unless-stopped");
    // A singleton gets NO environment label, and the absence is the feature: an environment
    // teardown reaps every container carrying its id, and a platform-plane container must never
    // go down with a tier it merely serves.
    if (spec.environmentId() != null) {
      argv.add("--label");
      argv.add(ENVIRONMENT_LABEL + "=" + spec.environmentId());
    }
    argv.add("--label");
    argv.add(APPLICATION_LABEL + "=" + spec.applicationId());
    argv.add("--label");
    argv.add(DEPLOYMENT_LABEL + "=" + spec.deploymentId());
    // What a reconciliation looks this container up by when a network it belongs on is created
    // later: the plane it is on, whether it is a public node, and the alias it must keep.
    argv.add("--label");
    argv.add(TARGET_LABEL + "=" + spec.target().name().toLowerCase(Locale.ROOT));
    argv.add("--label");
    argv.add(AVAILABLE_ON_ENV_LABEL + "=" + spec.availableOnEnv());
    argv.add("--label");
    argv.add(APP_NAME_LABEL + "=" + spec.applicationName());
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
    // QITS_ENVIRONMENT is written for environment applications ONLY: a singleton serves every
    // environment, and telling it that it lives in one would be a statement that is not true.
    if (spec.environmentName() != null) {
      env(argv, "QITS_ENVIRONMENT", spec.environmentName());
    }
    env(argv, "QITS_APPLICATION", spec.applicationName());
    // The same identity again, in the vocabulary OpenTelemetry reads (see resourceAttributes).
    String resourceAttributes = resourceAttributes(spec);
    env(argv, "OTEL_RESOURCE_ATTRIBUTES", resourceAttributes);
    env(argv, "QUARKUS_OTEL_RESOURCE_ATTRIBUTES", resourceAttributes);
    // The deployment's own additions for this application — qits.cd.run-args.<name>, whitespace
    // split, no re-quoting (an argument that needs a space in it does not fit this seam). The
    // application name was already dns-label-validated at the boundary, so the assembled key
    // cannot escape the family.
    //
    // THEY GO LAST, AND THAT IS THE PRECEDENCE RULE: docker keeps the LAST assignment of a
    // repeated env key (measured: `docker run -e FOO=first -e FOO=second` leaves one FOO, and it
    // is `second`). So every variable cd sets above is a DEFAULT the operator can override by
    // naming the same key in run-args, and cd never overwrites what an operator wrote. The
    // injection composes with the operator's arguments rather than fighting them.
    config
        .getOptionalValue(RUN_ARGS_PREFIX + spec.applicationName(), String.class)
        .filter(raw -> !raw.isBlank())
        .ifPresent(raw -> argv.addAll(Arrays.asList(raw.trim().split("\\s+"))));
    argv.add(spec.imageRef());
    return List.copyOf(argv);
  }

  /**
   * The deployed container's OpenTelemetry resource identity, as the standard {@code k=v,k=v} list.
   * Three attributes, each a value cd genuinely holds at this point and none invented:
   *
   * <ul>
   *   <li>{@code service.version} — the deployment's commit sha. cd deploys sha-addressed images,
   *       so the sha IS the released identity; it is not a version number and is not dressed up as
   *       one.
   *   <li>{@code deployment.environment.name} — the environment this container belongs to, or
   *       {@code platform} for a singleton, which belongs to all of them.
   *   <li>{@code service.instance.id} — the container name cd assigned, which is unique per
   *       deployment and stable for the process' lifetime.
   * </ul>
   *
   * <p>Nothing else. In particular no {@code qits.workspace.id} or {@code qits.repository.id}: a
   * platform service has neither, and stamping a fake one to fit an old query model is what the
   * log-streaming plan forbids. {@code service.name} is left alone — each image sets it from its own
   * {@code quarkus.application.name}, which is what the observability source list buckets on.
   *
   * <p><b>Why two variables carry one value.</b> {@code OTEL_RESOURCE_ATTRIBUTES} is the
   * vendor-neutral spelling every OpenTelemetry SDK reads, and it is the contract; it is written
   * first and alone would be the whole of this method. But it does not win everywhere, and the one
   * place it loses is the one attribute that matters most here. Measured against the platform's
   * Quarkus 3.34.6, the SDK resource is assembled in this order (lowest precedence first):
   *
   * <ol>
   *   <li>the SDK's autoconfigured environment resource — where {@code OTEL_RESOURCE_ATTRIBUTES}
   *       lands;
   *   <li>Quarkus' own build-time attributes ({@code service.name}, {@code service.version} from
   *       the pom stamp, {@code webengine.*}), merged OVER the previous;
   *   <li>{@code quarkus.otel.resource.attributes} — i.e. {@code QUARKUS_OTEL_RESOURCE_ATTRIBUTES}
   *       — merged over everything.
   * </ol>
   *
   * <p>So the environment name and the instance id arrive from the neutral variable (Quarkus stamps
   * neither), while a {@code service.version} written only there is silently replaced by the pom
   * version baked into the image at build time — the stale identity this injection exists to
   * correct. The Quarkus-spelled variable is the layer that outranks the stamp. Both carry the same
   * string, built once here, so the two cannot disagree; a non-Quarkus image simply ignores the
   * second name.
   */
  private static String resourceAttributes(StartSpec spec) {
    // Belt at the argv, the health-path stance: a comma or an equals sign in any of these would
    // forge an extra attribute, and each value is already validated at the boundary as a sha or a
    // dns label. This is what makes loosening one of those checks a failed deployment instead.
    String version = CdIdentifiers.requireAttributeValue(spec.commitSha(), "commit sha");
    String environment =
        CdIdentifiers.requireAttributeValue(
            spec.environmentName() == null ? SINGLETON_ENVIRONMENT : spec.environmentName(),
            "environment name");
    String instance = CdIdentifiers.requireAttributeValue(spec.containerName(), "container name");
    return "service.version="
        + version
        + ",deployment.environment.name="
        + environment
        + ",service.instance.id="
        + instance;
  }

  private static void env(List<String> argv, String key, String value) {
    argv.add("--env");
    argv.add(key + "=" + (value == null ? "" : value));
  }
}
