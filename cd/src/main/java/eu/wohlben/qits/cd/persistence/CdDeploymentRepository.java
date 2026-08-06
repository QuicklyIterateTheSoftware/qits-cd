package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Panache DAO for {@link CdDeployment}.
 *
 * <p>Every listing orders by {@code seq desc} — the identity column V5 added — rather than by
 * {@code createdAt desc, id desc}. The id is a random UUID, so the old tiebreak swapped two rows
 * recorded in the same tick at random, which is exactly what the deployments of one build-succeeded
 * event are.
 */
@ApplicationScoped
public class CdDeploymentRepository implements PanacheRepositoryBase<CdDeployment, String> {

  /** An environment's deployments across all its applications, newest-first. */
  public List<CdDeployment> listByEnvironmentNewestFirst(String environmentId) {
    return list("environmentId = ?1 order by seq desc", environmentId);
  }

  /**
   * Every deployment on this instance, newest-first — the whole history the pin rule reads ({@code
   * RollbackPins}). Unscoped on purpose: a pin is per application name across all environments.
   */
  public List<CdDeployment> listAllNewestFirst() {
    return list("order by seq desc");
  }

  /** Every deployment of one application in one tier ({@code null} environment = the singleton). */
  public List<CdDeployment> listByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list("applicationName = ?1 and environmentId is null", applicationName)
        : list("applicationName = ?1 and environmentId = ?2", applicationName, environmentId);
  }

  /** Every environment-scoped deployment of one application — what a singleton conversion absorbs. */
  public List<CdDeployment> listEnvironmentScoped(String applicationName) {
    return list("applicationName = ?1 and environmentId is not null", applicationName);
  }

  /** The application's currently serving deployment(s) in one tier — by invariant at most one. */
  public List<CdDeployment> listActiveByApplication(String applicationName, String environmentId) {
    return environmentId == null
        ? list(
            "applicationName = ?1 and environmentId is null and status = ?2",
            applicationName,
            CdDeploymentStatus.ACTIVE)
        : list(
            "applicationName = ?1 and environmentId = ?2 and status = ?3",
            applicationName,
            environmentId,
            CdDeploymentStatus.ACTIVE);
  }

  /**
   * One application's whole history, newest-first — what a build falls back to when the registry
   * cannot say which tiers track the branch.
   */
  public List<CdDeployment> listByApplicationNewestFirst(String applicationName) {
    return list("applicationName = ?1 order by seq desc", applicationName);
  }

  public List<CdDeployment> listByStatus(CdDeploymentStatus status) {
    return list("status = ?1", status);
  }
}
