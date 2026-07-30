package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import eu.wohlben.qits.cd.persistence.CdApplicationRepository;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The deployment orchestrator: a build-succeeded event → the applications listening to that (repo,
 * branch) → one recorded deployment each, driven pull → run → health gate → cutover on a
 * single-threaded daemon worker (the intake returns immediately; deployments across all
 * environments are serialized — parallelism is an explicit follow-up, and serial is what makes
 * "the previous ACTIVE deployment" an uncontended read).
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
 * being up mid-cutover. The predecessor is whatever holds the application's alias on the
 * environment's network — including containers cd did not start (a bootstrap's seeded originals),
 * which is how the platform's compose-managed first boot hands itself over to cd. The one
 * predecessor cd never stops in-process is its own container: deploying cd itself takes the
 * handoff path — start the successor, launch the detached referee that stops this instance and
 * arbitrates the gate, and let the surviving instance record the outcome (the successor's sweep
 * adopts the row; a rolled-back predecessor's sweep fails it).
 */
@ApplicationScoped
public class DeployService {

  private static final Logger LOG = Logger.getLogger(DeployService.class);

  @Inject CdApplicationRepository applications;
  @Inject CdDeploymentRepository deployments;
  @Inject DeploymentDriver driver;

  @ConfigProperty(name = "qits.artifacts.registry-host")
  String registryHost;

  @ConfigProperty(name = "qits.artifacts.image-repository")
  String imageRepository;

  @ConfigProperty(name = "qits.cd.default-health-path")
  String defaultHealthPath;

  @ConfigProperty(name = "qits.cd.health-timeout-seconds")
  long healthTimeoutSeconds;

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
   * The async entry the build-succeeded intake calls — returns immediately with how many
   * deployments were queued (zero when no environment listens to the branch, which is the normal
   * case for every push to a branch without an environment and not worth an error).
   */
  public int onBuildSucceeded(String repoId, String branch, String commitSha) {
    CdIdentifiers.requireRepoId(repoId);
    CdIdentifiers.requireBranch(branch);
    CdIdentifiers.requireSha(commitSha);

    List<String> queued =
        QuarkusTransaction.requiringNew()
            .call(
                () -> {
                  List<String> ids = new ArrayList<>();
                  for (CdApplication app : applications.listByRepoAndBranch(repoId, branch)) {
                    CdDeployment deployment = new CdDeployment();
                    deployment.id = UUID.randomUUID().toString();
                    deployment.application = app;
                    deployment.commitSha = commitSha;
                    deployment.status = CdDeploymentStatus.QUEUED;
                    deployment.createdAt = Instant.now();
                    deployments.persist(deployment);
                    ids.add(deployment.id);
                  }
                  return ids;
                });
    for (String deploymentId : queued) {
      worker.submit(
          () -> {
            try {
              execute(deploymentId);
            } catch (RuntimeException e) {
              LOG.errorf(e, "Deployment %s failed unexpectedly", deploymentId);
              finish(deploymentId, CdDeploymentStatus.FAILED, "[unexpected: " + e + "]");
            }
          });
    }
    return queued.size();
  }

  /** Everything a deployment needs off the worker thread — plain values, never entities. */
  private record Plan(
      String deploymentId,
      String environmentId,
      String environmentName,
      String network,
      String applicationId,
      String applicationName,
      String sha,
      String healthPath) {}

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
                  return new Plan(
                      deploymentId,
                      app.environment.id,
                      app.environment.name,
                      app.environment.network,
                      app.id,
                      app.name,
                      deployment.commitSha,
                      app.healthPath != null ? app.healthPath : defaultHealthPath);
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
    String containerName = containerName(plan.environmentName(), plan.applicationName(), deploymentId);
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              CdDeployment deployment = deployments.findById(deploymentId);
              if (deployment != null) {
                deployment.containerName = containerName;
              }
            });

    // The network is re-ensured on every deployment rather than trusted from creation time — an
    // environment created while docker was down must heal, not stay broken.
    driver.ensureNetwork(plan.network());

    // The replace cutover: whatever currently answers to the application's alias is STOPPED —
    // not removed — before the fresh container starts. Stopping first is what makes stateful
    // applications deployable at all (one process per H2 file, one binder per published host
    // port); keeping the stopped containers around is what preserves the rollback: a failed gate
    // restarts them. This also absorbs predecessors cd did not start (the bootstrap's seeded
    // originals) — holding the alias is what makes something the predecessor, not a row here.
    List<DeploymentDriver.Holder> predecessors =
        driver.aliasHolders(plan.network(), plan.applicationName());
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
          driver.start(
              new DeploymentDriver.StartSpec(
                  plan.environmentId(),
                  plan.environmentName(),
                  plan.applicationId(),
                  plan.applicationName(),
                  deploymentId,
                  plan.network(),
                  imageRef,
                  containerName,
                  plan.healthPath()));
      if (!successor.started()) {
        driver.remove(containerName);
        finish(deploymentId, CdDeploymentStatus.FAILED, safe(successor.detail()));
        return;
      }
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
        driver.start(
            new DeploymentDriver.StartSpec(
                plan.environmentId(),
                plan.environmentName(),
                plan.applicationId(),
                plan.applicationName(),
                deploymentId,
                plan.network(),
                imageRef,
                containerName,
                plan.healthPath()));
    if (!started.started()) {
      driver.remove(containerName); // in case docker created it and then failed
      rollback(predecessors);
      finish(deploymentId, CdDeploymentStatus.FAILED, safe(started.detail()));
      return;
    }

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
    for (String oldContainer : toRemove) {
      driver.remove(oldContainer);
    }
    LOG.infof(
        "Deployed %s@%s into %s (%s)",
        plan.applicationName(), plan.sha(), plan.environmentName(), containerName);
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

  /** One name shape for everything cd starts: qits-cd-<env>-<app>-<deployment-prefix>. */
  static String containerName(String environmentName, String applicationName, String deploymentId) {
    String shortId = deploymentId.length() > 8 ? deploymentId.substring(0, 8) : deploymentId;
    return "qits-cd-" + environmentName + "-" + applicationName + "-" + shortId;
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

  /** Test hook: waits for the work queued at this moment to drain. */
  void awaitIdle() throws Exception {
    worker.submit(() -> {}).get();
  }
}
