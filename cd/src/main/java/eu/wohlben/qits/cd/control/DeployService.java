package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.control.CdSpecSource.DeploymentSpec;
import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import eu.wohlben.qits.cd.persistence.CdApplicationRepository;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import eu.wohlben.qits.cd.persistence.CdEnvironmentRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deployment orchestrator: a build-succeeded event → the repository's deployment spec at that
 * commit → the applications that spec addresses → one recorded deployment each, driven pull → run →
 * join → health gate → cutover on a single-threaded daemon worker (the intake returns immediately;
 * deployments across all environments are serialized — parallelism is an explicit follow-up, and
 * serial is what makes "the previous ACTIVE deployment" an uncontended read).
 *
 * <p><b>Registration is derived.</b> Nothing declares an application over the API. A green build
 * carries cd to {@code .config/qits/deployments.yml} in the repository at that sha, and the row is
 * created or brought up to date from it: an {@code environment} target registers into every
 * environment whose branch matches, a {@code singleton} target registers once for the whole
 * platform if the build is on the singleton's own branch. A repository with no such file gets the
 * defaults and behaves exactly as it did before the file existed.
 *
 * <p>Each DB transition sits in its own {@link QuarkusTransaction#requiringNew()} bracket so the
 * slow docker work never holds a transaction, and everything the docker calls need is copied out
 * of the entities first — the worker thread has no request context and no open session.
 *
 * <p><b>The cutover invariant:</b> the previous container is only <i>stopped</i> during the gate
 * and is removed only after the new one passed it; a failed deployment — image missing, docker
 * refused, health gate expired — removes the fresh container and restarts what was stopped, so
 * the previous deployment stays {@code ACTIVE} and serving. Stop-before-start (rather than the
 * overlapping cutover this service first shipped) is what makes stateful applications deployable
 * at all: one process per H2 file, one binder per published host port. The pull still happens
 * before the stop, so replacing the registry's own application does not depend on the registry
 * being up mid-cutover. The predecessor is whatever holds the application's alias on any of the
 * networks the fresh container is about to be on — including containers cd did not start (a
 * bootstrap's seeded originals) and containers still living on the legacy network alone, which is
 * how the platform migrates onto per-application networks without ever running two copies. The one
 * predecessor cd never stops in-process is its own container: deploying cd itself takes the
 * handoff path — start the successor, launch the detached referee that stops this instance and
 * arbitrates the gate, and let the surviving instance record the outcome (the successor's sweep
 * adopts the row; a rolled-back predecessor's sweep fails it).
 */
@ApplicationScoped
public class DeployService {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  @Inject CdApplicationRepository applications;
  @Inject CdEnvironmentRepository environments;
  @Inject CdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;
  @Inject CdSpecSource specs;

  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String imageRepository;

  @ConfigProperty(name = "qits.cd.default-health-path")
  String defaultHealthPath;

  @ConfigProperty(name = "qits.cd.health-timeout-seconds")
  long healthTimeoutSeconds;

  /**
   * The network every fresh container additionally joins while the platform still holds direct
   * cross-application URLs. Emptying it is the enforcement flip: from then on an application can
   * only be reached through the gateway route or a hub join, and a URL nobody migrated fails
   * loudly instead of resolving on a flat network.
   *
   * <p>{@code Optional} because SmallRye reads an empty value as ABSENT, not as an empty string —
   * so the flip's own spelling ({@code QITS_CD_LEGACY_NETWORK=}) would fail this bean's injection
   * if the field were a plain String.
   */
  @ConfigProperty(name = "qits.cd.legacy-network")
  Optional<String> legacyNetwork;

  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "cd-deploy-worker");
            t.setDaemon(true);
            return t;
          });

  @PreDestroy
  void shutdown() {
    worker.shutdownNow();
  }

  /**
   * A deployment left {@code QUEUED} or {@code STARTING} by a crash can never make progress — the
   * worker queue does not survive the JVM — so it would show as forever-deploying. Fail those once
   * at startup, with one exception: a {@code STARTING} row whose container is <b>this very
   * process</b> is a self-update handoff that succeeded — the predecessor recorded the row, the
   * referee retired it, and this instance is the successor booting for the first time. That row
   * is ADOPTED (ACTIVE, prior ACTIVE rows decommissioned): the instance that survived the
   * handoff records its outcome. The containers are deliberately NOT reaped: a deployed
   * application outlives its deployer, and whatever was {@code ACTIVE} before the restart is
   * still serving.
   */
  void onStart(@Observes StartupEvent event) {
    if (LaunchMode.current() == LaunchMode.TEST) {
      return;
    }
    try {
      sweepInFlight();
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not sweep interrupted deployments at startup");
    }
  }

  /** Package-private so the suite drives the sweep without a real StartupEvent. */
  void sweepInFlight() {
    String self = driver.selfContainerId();
    int swept =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<CdDeployment> orphans =
                      new ArrayList<>(deployments.listByStatus(CdDeploymentStatus.QUEUED));
                  orphans.addAll(deployments.listByStatus(CdDeploymentStatus.STARTING));
                  int failed = 0;
                  for (CdDeployment orphan : orphans) {
                    if (orphan.status == CdDeploymentStatus.STARTING
                        && orphan.containerName != null
                        && !self.isBlank()) {
                      String id = driver.containerId(orphan.containerName);
                      if (!id.isBlank() && (id.startsWith(self) || self.startsWith(id))) {
                        for (CdDeployment previous :
                            deployments.listActiveByApplication(orphan.application.id)) {
                          previous.status = CdDeploymentStatus.DECOMMISSIONED;
                          previous.finishedAt = Instant.now();
                        }
                        orphan.status = CdDeploymentStatus.ACTIVE;
                        orphan.detail = "[adopted at startup: this instance is the successor of a self-update handoff]";
                        orphan.finishedAt = Instant.now();
                        LOG.infof(
                            "Adopted deployment %s: this instance (%s) is its container",
                            orphan.id, orphan.containerName);
                        continue;
                      }
                    }
                    orphan.status = CdDeploymentStatus.FAILED;
                    orphan.detail = "[interrupted by a qits-cd restart]";
                    orphan.finishedAt = Instant.now();
                    failed++;
                  }
                  return failed;
                });
    if (swept > 0) {
      LOG.infof("Marked %d deployment(s) left in flight by a previous shutdown as FAILED", swept);
    }
  }

  /**
   * The async entry the build-succeeded intake calls. It validates, hands the event to the worker
   * and returns — the sender is fire-and-forget and has nothing to do with the answer.
   *
   * <p><b>The whole event runs on the worker, registration included</b>, and that placement is the
   * concurrency contract rather than a detail. Derived registration is a read-then-write — "is
   * there a row for this repository yet, and if not, make one" — and a singleton row is the one
   * shape no database constraint can guard, because its {@code environment_id} is null and a
   * composite unique index treats nulls as distinct. Two green builds of one repository arriving
   * together would each read "no singleton" and each write one. The worker is single-threaded, so
   * putting the read and the write on it is what makes the pair atomic against every other event —
   * the same reason the cutover lives there, applied to the rows instead of the containers.
   *
   * <p>{@code runId} is optional and is recorded on every row this queues, verbatim: it is the only
   * pointer from a deployment back to the build that caused it, and cd resolves it against nothing —
   * a reader takes it to qits-ci. The triple that actually drives the deployment is still (repoId,
   * branch, commitSha).
   */
  public void onBuildSucceeded(String runId, String repoId, String branch, String commitSha) {
    CdIdentifiers.requireRunId(runId);
    CdIdentifiers.requireRepoId(repoId);
    CdIdentifiers.requireBranch(branch);
    CdIdentifiers.requireSha(commitSha);
    worker.submit(
        () -> {
          try {
            deploy(runId, repoId, branch, commitSha);
          } catch (RuntimeException e) {
            LOG.errorf(e, "The build-succeeded event for %s@%s could not be handled", repoId, commitSha);
          }
        });
  }

  /**
   * One build-succeeded event, start to finish, on the worker thread: read what the repository
   * declares, bring the rows it addresses up to date, and deploy each.
   *
   * <p>The spec read comes first because it decides <b>which rows exist</b> — there is nothing to
   * queue until it has answered. A read that fails (the git host is down, the file does not parse)
   * does not guess: the applications already registered for this (repo, branch) each get a recorded
   * {@code FAILED} deployment naming the cause, and a repository with no rows yet gets nothing,
   * exactly as an unknown repository always has.
   */
  private void deploy(String runId, String repoId, String branch, String commitSha) {
    DeploymentSpec spec = null;
    String specFailure = null;
    try {
      spec = specs.read(repoId, commitSha);
    } catch (RuntimeException e) {
      specFailure = "[deployment spec unreadable: " + e.getMessage() + "]";
      LOG.warnf("Could not read the deployment spec of %s@%s: %s", repoId, commitSha, e.getMessage());
    }

    List<String> applicationIds =
        spec == null
            ? alreadyRegistered(repoId, branch)
            : register(runId, repoId, branch, commitSha, spec);
    List<String> queued = queue(runId, commitSha, applicationIds);

    if (specFailure != null) {
      for (String deploymentId : queued) {
        finish(deploymentId, CdDeploymentStatus.FAILED, specFailure);
      }
      return;
    }
    for (String deploymentId : queued) {
      try {
        execute(deploymentId);
      } catch (RuntimeException e) {
        LOG.errorf(e, "Deployment %s failed unexpectedly", deploymentId);
        finish(deploymentId, CdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
      }
    }
  }

  /**
   * Bring the rows this build addresses up to date with what the repository declares, and answer
   * which applications to deploy. The whole of derived registration.
   */
  private List<String> register(
      String runId, String repoId, String branch, String commitSha, DeploymentSpec spec) {
    if (!isDeployableName(repoId)) {
      // The application name is the image path segment and the network alias, so it has to be a
      // dns label. A repository whose id is not one cannot be deployed by convention at all, and
      // the intake is fire-and-forget — saying so in the log beats a 400 nobody reads.
      LOG.warnf("Repository %s cannot be an application name, so nothing was registered", repoId);
      return List.of();
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                spec.target() == CdDeploymentTarget.SINGLETON
                    ? registerSingleton(repoId, branch, spec)
                    : registerInEnvironments(runId, repoId, branch, commitSha, spec));
  }

  /**
   * The environment half. A repository that is <b>already a singleton</b> is refused here rather
   * than registered: the two planes are not symmetric, and going back is not a conversion.
   *
   * <p>Coming the other way, environment rows become the singleton because there is exactly one
   * destination to move them to. A singleton going back has as many destinations as there are
   * environments tracking the branch, no answer to which of them inherits the deployment history,
   * and a running container on {@code qits-platform} that the environment deployment would find
   * through the legacy network and remove — leaving a singleton row that says {@code ACTIVE} about
   * a container that no longer exists. So this refuses, loudly and on the record: an operator
   * deletes the singleton row's plane deliberately, or the file goes back to what it said.
   */
  private List<String> registerInEnvironments(
      String runId, String repoId, String branch, String commitSha, DeploymentSpec spec) {
    Optional<CdApplication> singleton = applications.findSingletonByRepo(repoId);
    if (singleton.isPresent()) {
      LOG.errorf(
          "%s is registered as a platform singleton and its deployments.yml now asks for"
              + " deployment_target: environment. Going back is not a conversion and was refused —"
              + " remediate deliberately (retire the singleton, then push again).",
          repoId);
      recordRejection(
          singleton.get(),
          runId,
          commitSha,
          "[refused: "
              + repoId
              + " is a platform singleton and this commit asks for deployment_target:"
              + " environment. cd converts an environment application into a singleton, never the"
              + " reverse — there is no one environment to inherit the history and the running"
              + " singleton would be removed by the first environment deployment. Retire the"
              + " singleton deliberately, then push again.]");
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (CdEnvironment environment : environments.listByBranch(branch)) {
      CdApplication application =
          applications
              .findByEnvironmentAndRepo(environment.id, repoId)
              .orElseGet(
                  () -> {
                    CdApplication fresh = new CdApplication();
                    fresh.id = UUID.randomUUID().toString();
                    fresh.environment = environment;
                    fresh.repoId = repoId;
                    fresh.name = repoId;
                    fresh.createdAt = Instant.now();
                    applications.persist(fresh);
                    LOG.infof("Registered %s in environment %s", repoId, environment.name);
                    return fresh;
                  });
      application.deploymentTarget = CdDeploymentTarget.ENVIRONMENT;
      application.availableOnEnv = spec.availableOnEnv();
      application.branch = null; // an environment application takes its branch from its tier
      ids.add(application.id);
    }
    return ids;
  }

  /**
   * The singleton half, including the conversion the live migration depends on: a repository that
   * was an environment application until this commit has rows in every environment it was in, and
   * those rows have to become the one singleton row rather than sit beside it. Their deployment
   * history is <b>moved onto the singleton</b> — the active ones decommissioned, since the
   * application they described is about to be replaced from a different plane — and only then are
   * the old rows removed. Moving rather than deleting is what keeps an in-flight self-update row
   * alive across cd's own conversion; the containers those rows started are absorbed by the next
   * cutover, which finds them on the legacy network exactly as it finds any other predecessor.
   */
  private List<String> registerSingleton(String repoId, String branch, DeploymentSpec spec) {
    if (!branch.equals(spec.singletonBranch())) {
      return List.of();
    }
    Optional<CdApplication> nameTaken = applications.findSingletonByName(repoId);
    if (nameTaken.isPresent() && !nameTaken.get().repoId.equals(repoId)) {
      LOG.errorf(
          "Singleton name %s already belongs to repository %s — %s was not registered",
          repoId, nameTaken.get().repoId, repoId);
      return List.of();
    }
    CdApplication singleton =
        applications
            .findSingletonByRepo(repoId)
            .orElseGet(
                () -> {
                  CdApplication fresh = new CdApplication();
                  fresh.id = UUID.randomUUID().toString();
                  fresh.repoId = repoId;
                  fresh.name = repoId;
                  fresh.createdAt = Instant.now();
                  applications.persist(fresh);
                  LOG.infof("Registered %s as a platform singleton", repoId);
                  return fresh;
                });
    singleton.environment = null;
    singleton.deploymentTarget = CdDeploymentTarget.SINGLETON;
    singleton.availableOnEnv = false;
    singleton.branch = spec.singletonBranch();

    for (CdApplication scoped : applications.listEnvironmentScopedByRepo(repoId)) {
      for (CdDeployment deployment : deployments.listByApplication(scoped.id)) {
        if (deployment.status == CdDeploymentStatus.ACTIVE) {
          deployment.status = CdDeploymentStatus.DECOMMISSIONED;
          deployment.finishedAt = Instant.now();
        }
        deployment.application = singleton;
      }
      deployments.flush();
      applications.delete(scoped);
      LOG.infof("Converted %s from an environment application to the platform singleton", repoId);
    }
    return List.of(singleton.id);
  }

  /**
   * A refused registration, written down where the operator will look for it: one {@code FAILED}
   * deployment on the row that already exists. A log line alone would say the same thing to nobody
   * — the intake is fire-and-forget, so the row is the only surface a refusal can surface on.
   * Called inside the registration transaction.
   */
  private void recordRejection(
      CdApplication application, String runId, String commitSha, String detail) {
    CdDeployment rejected = new CdDeployment();
    rejected.id = UUID.randomUUID().toString();
    rejected.application = application;
    rejected.commitSha = commitSha;
    rejected.runId = runId;
    rejected.status = CdDeploymentStatus.FAILED;
    rejected.detail = detail;
    rejected.createdAt = Instant.now();
    rejected.finishedAt = Instant.now();
    deployments.persist(rejected);
  }

  /** What a failed spec read falls back to: the rows this (repo, branch) already had. */
  private List<String> alreadyRegistered(String repoId, String branch) {
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<String> ids = new ArrayList<>();
              for (CdApplication application : applications.listByRepoAndBranch(repoId, branch)) {
                ids.add(application.id);
              }
              applications
                  .findSingletonByRepo(repoId)
                  .filter(a -> branch.equals(a.branch))
                  .ifPresent(a -> ids.add(a.id));
              return ids;
            });
  }

  private List<String> queue(String runId, String commitSha, List<String> applicationIds) {
    if (applicationIds.isEmpty()) {
      return List.of();
    }
    return QuarkusTransaction.requiringNew()
        .call(
            () -> {
              List<String> ids = new ArrayList<>();
              for (String applicationId : applicationIds) {
                CdApplication application = applications.findById(applicationId);
                if (application == null) {
                  continue;
                }
                CdDeployment deployment = new CdDeployment();
                deployment.id = UUID.randomUUID().toString();
                deployment.application = application;
                deployment.commitSha = commitSha;
                deployment.runId = runId;
                deployment.status = CdDeploymentStatus.QUEUED;
                deployment.createdAt = Instant.now();
                deployments.persist(deployment);
                ids.add(deployment.id);
              }
              return ids;
            });
  }

  /** Everything a deployment needs off the worker thread — plain values, never entities. */
  private record Plan(
      String deploymentId,
      String environmentId,
      String environmentName,
      String bundleNetwork,
      String applicationId,
      String applicationName,
      String sha,
      String healthPath,
      CdDeploymentTarget target,
      boolean availableOnEnv) {

    boolean singleton() {
      return target == CdDeploymentTarget.SINGLETON;
    }

    /** The one network {@code docker run} can take; every other membership is a join. */
    String primaryNetwork() {
      return singleton()
          ? CdNetworks.PLATFORM
          : CdNetworks.application(environmentName, applicationName);
    }
  }

  /** The synchronous deployment — package-private so tests drive it without the worker. */
  void execute(String deploymentId) {
    Plan plan =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  CdDeployment deployment = deployments.findById(deploymentId);
                  if (deployment == null || deployment.status != CdDeploymentStatus.QUEUED) {
                    return null; // torn down or swept while queued — nothing to do
                  }
                  deployment.status = CdDeploymentStatus.STARTING;
                  CdApplication app = deployment.application;
                  boolean singleton = app.deploymentTarget == CdDeploymentTarget.SINGLETON;
                  return new Plan(
                      deploymentId,
                      singleton ? null : app.environment.id,
                      singleton ? null : app.environment.name,
                      singleton ? null : app.environment.network,
                      app.id,
                      app.name,
                      deployment.commitSha,
                      app.healthPath != null ? app.healthPath : defaultHealthPath,
                      app.deploymentTarget,
                      app.availableOnEnv);
                });
    if (plan == null) {
      return;
    }

    String imageRef = ImageRefs.imageRef(registryHost, imageRepository, plan.applicationName(), plan.sha());

    // The registry having no image for a green build is an expected outcome (nothing may publish
    // this application yet) and gets its own state rather than a generic failure.
    DeploymentDriver.PullResult pulled = driver.pull(imageRef);
    switch (pulled.outcome()) {
      case IMAGE_MISSING -> {
        finish(
            deploymentId,
            CdDeploymentStatus.IMAGE_MISSING,
            "no image " + imageRef + "\n" + safe(pulled.detail()));
        return;
      }
      case ERROR -> {
        finish(deploymentId, CdDeploymentStatus.FAILED, safe(pulled.detail()));
        return;
      }
      case OK -> {
        /* fall through */
      }
    }

    // Named after the deployment, not the sha: re-deploying the same commit must never collide
    // with the container it is about to replace.
    String containerName =
        containerName(plan.environmentName(), plan.applicationName(), deploymentId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = containerName;
              }
            });

    // Networks are re-ensured on every deployment rather than trusted from creation time — an
    // environment created while docker was down must heal, not stay broken.
    String primaryNetwork = plan.primaryNetwork();
    driver.ensureNetwork(primaryNetworkSpec(plan));
    if (!plan.singleton() && plan.availableOnEnv()) {
      driver.ensureNetwork(
          new DeploymentDriver.Network(
              plan.bundleNetwork(),
              plan.environmentId(),
              DeploymentDriver.NetworkKind.BUNDLE,
              null));
    }
    List<String> joins = desiredJoins(plan, primaryNetwork);

    // The replace cutover: whatever currently answers to the application's alias is STOPPED —
    // not removed — before the fresh container starts. Stopping first is what makes stateful
    // applications deployable at all (one process per H2 file, one binder per published host
    // port); keeping the stopped containers around is what preserves the rollback: a failed gate
    // restarts them. The search covers every network the fresh container will be on, so it also
    // absorbs predecessors cd did not start (the bootstrap's seeded originals) and predecessors
    // still living on the legacy network alone (the migration onto per-application networks) —
    // holding the alias is what makes something the predecessor, not a row here.
    List<String> searchNetworks = new ArrayList<>();
    searchNetworks.add(primaryNetwork);
    searchNetworks.addAll(joins);
    List<DeploymentDriver.Holder> predecessors =
        predecessorsOf(
            driver.aliasHolders(List.copyOf(searchNetworks), plan.applicationName()), plan);
    String self = driver.selfContainerId();
    DeploymentDriver.Holder selfHolder =
        self.isBlank()
            ? null
            : predecessors.stream()
                .filter(p -> p.id().startsWith(self) || self.startsWith(p.id()))
                .findFirst()
                .orElse(null);
    if (selfHolder != null) {
      // The self-update handoff. This process cannot stop itself and then finish the cutover, so
      // the roles split three ways: this instance starts the successor (which retries on the H2
      // lock under its restart policy) and launches a detached referee; the referee stops this
      // container — freeing the lock — awaits the successor's health gate, and removes whichever
      // side lost; the successor's startup sweep adopts the row it finds itself named on. The row
      // is left STARTING on purpose: adoption marks it ACTIVE, and after a referee rollback this
      // instance's own sweep marks it FAILED — each outcome recorded by the instance that
      // survived it.
      DeploymentDriver.StartResult successor =
          driver.start(startSpec(plan, primaryNetwork, imageRef, containerName));
      if (!successor.started()) {
        driver.remove(containerName);
        finish(deploymentId, CdDeploymentStatus.FAILED, safe(successor.detail()));
        return;
      }
      String unjoined = join(containerName, plan.applicationName(), joins);
      if (unjoined != null) {
        // No handoff: the referee would promote a successor no caller can address, and it would do
        // it by removing the instance that still works. Nothing was stopped yet, so dropping the
        // successor puts everything back.
        driver.remove(containerName);
        finish(deploymentId, CdDeploymentStatus.FAILED, unjoined);
        return;
      }
      reconcile(plan, primaryNetwork);
      driver.handoff(
          new DeploymentDriver.HandoffSpec(
              imageRef, selfHolder.id(), containerName, healthTimeoutSeconds));
      LOG.infof(
          "Self-update handoff initiated: %s succeeds this instance (%s); the referee arbitrates",
          containerName, selfHolder.name());
      return;
    }
    for (DeploymentDriver.Holder predecessor : predecessors) {
      driver.stop(predecessor.name());
    }

    DeploymentDriver.StartResult started =
        driver.start(startSpec(plan, primaryNetwork, imageRef, containerName));
    if (!started.started()) {
      driver.remove(containerName); // in case docker created it and then failed
      rollback(predecessors);
      finish(deploymentId, CdDeploymentStatus.FAILED, safe(started.detail()));
      return;
    }
    // Docker takes one network at `run`; everything else is a join, and the set is recomputed from
    // docker on every deployment rather than remembered — which makes this the self-heal too: a
    // membership lost to a manual `network disconnect` or to a network that did not exist last
    // time is simply back on the replacement.
    //
    // A membership the deployment asked for and did not get is a FAILED deployment, not a warning.
    // The health gate cannot catch it — it curls localhost inside the container, which answers
    // perfectly well from a network nobody else is on — so an unreachable container would go ACTIVE
    // and the predecessor would be removed under it. This is the same rollback a failed gate takes.
    String unjoined = join(containerName, plan.applicationName(), joins);
    if (unjoined != null) {
      driver.remove(containerName);
      rollback(predecessors);
      finish(deploymentId, CdDeploymentStatus.FAILED, unjoined);
      return;
    }
    reconcile(plan, primaryNetwork);

    DeploymentDriver.HealthResult health =
        driver.awaitHealthy(containerName, Duration.ofSeconds(healthTimeoutSeconds));
    if (!health.healthy()) {
      // The fresh container failed the gate: remove IT and restart what the cutover stopped —
      // the previous deployment goes back to serving.
      driver.remove(containerName);
      rollback(predecessors);
      finish(deploymentId, CdDeploymentStatus.FAILED, safe(health.detail()));
      return;
    }

    // Cutover: the new deployment is the application's ACTIVE one, whatever was ACTIVE before is
    // decommissioned — rows first, then the stopped containers (rows' and alias-holders' alike;
    // a set, since the healthy path sees most containers from both angles).
    List<String> oldContainers =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<String> old = new ArrayList<>();
                  for (CdDeployment previous : deployments.listActiveByApplication(plan.applicationId())) {
                    previous.status = CdDeploymentStatus.DECOMMISSIONED;
                    previous.finishedAt = Instant.now();
                    if (previous.containerName != null) {
                      old.add(previous.containerName);
                    }
                  }
                  CdDeployment deployment = deployments.findById(deploymentId);
                  deployment.status = CdDeploymentStatus.ACTIVE;
                  deployment.finishedAt = Instant.now();
                  return old;
                });
    Set<String> toRemove = new LinkedHashSet<>(oldContainers);
    for (DeploymentDriver.Holder predecessor : predecessors) {
      toRemove.add(predecessor.name());
    }
    toRemove.remove(containerName);
    for (String oldContainer : toRemove) {
      driver.remove(oldContainer);
    }
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(),
        plan.sha(),
        plan.singleton() ? "the platform" : plan.environmentName(),
        containerName);
  }

  private DeploymentDriver.Network primaryNetworkSpec(Plan plan) {
    return plan.singleton()
        ? new DeploymentDriver.Network(
            CdNetworks.PLATFORM, null, DeploymentDriver.NetworkKind.PLATFORM, null)
        : new DeploymentDriver.Network(
            plan.primaryNetwork(),
            plan.environmentId(),
            DeploymentDriver.NetworkKind.APPLICATION,
            plan.applicationName());
  }

  private DeploymentDriver.StartSpec startSpec(
      Plan plan, String primaryNetwork, String imageRef, String containerName) {
    return new DeploymentDriver.StartSpec(
        plan.environmentId(),
        plan.environmentName(),
        plan.applicationId(),
        plan.applicationName(),
        plan.deploymentId(),
        plan.sha(),
        primaryNetwork,
        imageRef,
        containerName,
        plan.healthPath(),
        plan.target(),
        plan.availableOnEnv());
  }

  /**
   * Every network the fresh container joins after it started, primary excluded.
   *
   * <ul>
   *   <li>the legacy network, while {@code qits.cd.legacy-network} names one — the transition
   *       membership that keeps today's direct cross-application URLs resolving;
   *   <li>a public node ({@code availableOnEnv}) additionally joins its environment's bundle and
   *       <b>every</b> per-application network of that environment: that is the hub, and it is how
   *       an application reaches the gateway and how the gateway proxies every application;
   *   <li>a singleton joins every per-application network of every environment — being locally
   *       reachable everywhere is what makes it a singleton rather than a shared service that needs
   *       a route.
   * </ul>
   */
  private List<String> desiredJoins(Plan plan, String primaryNetwork) {
    Set<String> joins = new LinkedHashSet<>();
    legacyNetwork.map(String::strip).filter(n -> !n.isEmpty()).ifPresent(joins::add);
    if (plan.singleton()) {
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION) {
          joins.add(network.name());
        }
      }
    } else if (plan.availableOnEnv()) {
      joins.add(plan.bundleNetwork());
      for (DeploymentDriver.Network network : driver.networks()) {
        if (network.kind() == DeploymentDriver.NetworkKind.APPLICATION
            && plan.environmentId().equals(network.environmentId())) {
          joins.add(network.name());
        }
      }
    }
    joins.remove(primaryNetwork);
    return List.copyOf(joins);
  }

  /**
   * Put the fresh container on every network it needs beyond its primary one.
   *
   * @return null when it is on all of them, or the failure to record on the deployment row —
   *     these joins are what makes the container addressable, so a refused one is not a warning
   */
  private String join(String containerName, String alias, List<String> networks) {
    for (String network : networks) {
      DeploymentDriver.ConnectResult joined = driver.connect(network, containerName, alias);
      if (!joined.joined()) {
        return "could not join " + containerName + " to '" + network + "'\n" + safe(joined.detail());
      }
    }
    return null;
  }

  /**
   * Which of the containers answering to this alias this deployment may replace.
   *
   * <p>The alias search is a union that includes the legacy network, and the legacy network is
   * shared by every tier — so it also returns another environment's copy of the same application,
   * holding the same alias, perfectly healthy. Stopping that one would be a deployment of one tier
   * silently taking a container out of another, which is what the environment label prevents:
   *
   * <ul>
   *   <li>a holder labelled with <b>this</b> environment is this deployment's own predecessor;
   *   <li>a holder labelled with <b>another</b> environment belongs to that tier and is left alone;
   *   <li>a holder with <b>no</b> label is unclaimed — a compose original, a container the previous
   *       cd started, or a singleton — and stays adoptable, because that is the whole of how this
   *       platform migrates onto per-application networks.
   * </ul>
   *
   * <p>A singleton keeps only the unlabelled ones, which by the same rule means singletons and
   * unclaimed originals: a container that carries an environment id belongs to a tier, and no tier's
   * container is the platform plane's predecessor.
   */
  private static List<DeploymentDriver.Holder> predecessorsOf(
      List<DeploymentDriver.Holder> holders, Plan plan) {
    List<DeploymentDriver.Holder> mine = new ArrayList<>();
    for (DeploymentDriver.Holder holder : holders) {
      if (holder.environmentId() == null || holder.environmentId().equals(plan.environmentId())) {
        mine.add(holder);
      } else {
        LOG.debugf(
            "%s holds the alias %s for environment %s — not this deployment's predecessor",
            holder.name(), plan.applicationName(), holder.environmentId());
      }
    }
    return List.copyOf(mine);
  }

  /**
   * Put the environment's public nodes and every singleton on this application's network, both
   * found by their container labels — docker is the membership bookkeeping, so this asks the
   * runtime rather than a table.
   *
   * <p>It runs on <b>every</b> deployment, not only on the one that made the network, for the same
   * reason the container's own joins are recomputed: the network outlives the deployment that
   * created it. A deployment that made the network and then failed to start leaves it behind with
   * nobody on it, and the application would stay unreachable from the gateway and from every
   * singleton until some hub happened to redeploy. Joining is idempotent — docker refuses an
   * already-joined container and changes nothing — so recomputing it is the self-heal.
   */
  private void reconcile(Plan plan, String primaryNetwork) {
    if (plan.singleton()) {
      return;
    }
    for (DeploymentDriver.Endpoint hub : driver.hubContainers(plan.environmentId())) {
      driver.connect(primaryNetwork, hub.id(), hub.alias());
    }
    for (DeploymentDriver.Endpoint singleton : driver.singletonContainers()) {
      driver.connect(primaryNetwork, singleton.id(), singleton.alias());
    }
  }

  /** A failed cutover restarts every container it stopped — the previous deployment serves again. */
  private void rollback(List<DeploymentDriver.Holder> predecessors) {
    for (DeploymentDriver.Holder predecessor : predecessors) {
      driver.restart(predecessor.name());
    }
  }

  private void finish(String deploymentId, CdDeploymentStatus status, String detail) {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdDeployment deployment = deployments.findById(deploymentId);
              if (deployment == null) {
                return; // environment torn down mid-deploy
              }
              deployment.status = status;
              deployment.detail = detail;
              deployment.finishedAt = Instant.now();
            });
    if (status != CdDeploymentStatus.ACTIVE) {
      LOG.warnf("Deployment %s ended %s: %s", deploymentId, status, firstLine(detail));
    }
  }

  /** An environment's deployments across all its applications, newest-first. */
  public List<CdDeployment> deploymentsFor(String environmentId) {
    return deployments.listByEnvironmentNewestFirst(environmentId);
  }

  /**
   * One name shape for everything cd starts: {@code qits-cd-<env>-<app>-<deployment-prefix>}, and
   * {@code qits-cd-singleton-<app>-<deployment-prefix>} for a singleton, which has no environment
   * to be named after. {@code singleton} sits where an environment name would, and no environment
   * can take that place: an environment named `singleton` would produce a container name shaped
   * exactly like a singleton's, which is a collision only in the name and not in what is deployed.
   */
  static String containerName(String environmentName, String applicationName, String deploymentId) {
    String shortId = deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    return "qits-cd-" + (environmentName == null ? "singleton" : environmentName) + "-"
        + applicationName + "-" + shortId;
  }

  private static boolean isDeployableName(String repoId) {
    try {
      CdIdentifiers.requireName(repoId, "application name");
      return true;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static String safe(String detail) {
    return detail == null ? "" : detail;
  }

  private static String firstLine(String output) {
    if (output == null || output.isBlank()) {
      return "(no detail)";
    }
    String trimmed = output.strip();
    int newline = trimmed.indexOf('\n');
    return newline < 0 ? trimmed : trimmed.substring(0, newline);
  }

  /**
   * Test hook: waits for the work queued at this moment to drain. Public because the whole of an
   * event now runs on the worker — registration included — so a suite that asserts "nothing was
   * registered" has to be able to wait for the worker rather than for a row that never appears.
   */
  public void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
