package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import java.time.Instant;
import java.util.List;

/**
 * The seam onto qits-serviceregistry, which owns the environment/application topology — the
 * {@link DeploymentDriver} and {@link CdSpecSource} arrangement a third time: this module owns the
 * port and everything that calls it, {@code service} owns the one implementation that speaks HTTP,
 * and the suites install a stub server so a clone's {@code mvn verify} reaches no network.
 *
 * <p><b>The registry is the system of record; cd is the agent door.</b> cd's own environment
 * endpoints stay the operational surface (they are what also drives docker), but every environment
 * and every service row they read or write lives over here. cd's remaining domain is deployments
 * in its own environment, and a deployment row names its application by <b>string</b> — no FK, the
 * {@code repo_id} stance applied across a service boundary.
 *
 * <p><b>A service is identified by its name.</b> The registry holds no repository id, and derived
 * registration has always named an application after its repository, so the two are one value here.
 *
 * <p>Every method throws {@link eu.wohlben.qits.cd.error.RegistryException} (502) when the registry
 * cannot be reached or answers something unusable, and the registry's own 400/404/409 come back as
 * cd's {@link eu.wohlben.qits.cd.error.BadRequestException}/{@code NotFoundException}/{@code
 * ConflictException} so the proxied surface keeps the status vocabulary it always had.
 */
public interface RegistryClient {

  /** One tier: the branch whose green builds deploy into it, and its bundle network. */
  record RegEnvironment(String id, String name, String branch, String network, Instant createdAt) {}

  /**
   * One deployable service as the registry holds it. {@code environmentIds} is the link set and is
   * empty for a singleton — a singleton is implicitly linked everywhere, which is what makes a new
   * environment pick it up.
   */
  record RegService(
      String name,
      CdDeploymentTarget target,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds,
      Instant createdAt) {}

  /** What {@code PUT services/{name}} sends: the whole row, link set included. */
  record ServiceUpsert(
      String name,
      CdDeploymentTarget target,
      String branch,
      boolean availableOnEnv,
      String healthPath,
      List<String> environmentIds) {}

  RegEnvironment createEnvironment(String name, String branch, String network);

  /** Both fields optional; an omitted one is left alone. */
  RegEnvironment updateEnvironment(String environmentId, String name, String branch);

  /** Rows only — the registry touches no docker. cd tears the containers down before calling it. */
  void deleteEnvironment(String environmentId);

  /**
   * @throws eu.wohlben.qits.cd.error.NotFoundException when the registry does not have it
   */
  RegEnvironment environment(String environmentId);

  List<RegEnvironment> environments();

  /**
   * Every environment listening to exactly this branch — what derived registration fans a green
   * build out over.
   *
   * <p>Filtered from {@link #environments()} rather than asked for: the pinned registry API has no
   * by-branch query, and inventing one would be a contract cd made up on its own.
   */
  List<RegEnvironment> environmentsOnBranch(String branch);

  /** Create or bring the service up to date, link set included. */
  RegService upsertService(ServiceUpsert upsert);

  /** Every service the registry holds, each with its links. */
  List<RegService> services();

  /** What must be linked into this environment: its own services plus every singleton. */
  List<RegService> linksOf(String environmentId);
}
