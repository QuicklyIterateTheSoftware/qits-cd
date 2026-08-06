package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link CdDeployment}. */
@ApplicationScoped
public class CdDeploymentRepository implements PanacheRepositoryBase<CdDeployment, String> {

  /** An environment's deployments across all its applications, newest-first. */
  public List<CdDeployment> listByEnvironmentNewestFirst(String environmentId) {
    return list(
        "application.environment.id = ?1 order by createdAt desc, id desc", environmentId);
  }

  /**
   * Every deployment on this instance, newest-first, with its application attached — the whole
   * history the pin rule reads ({@code RollbackPins}). Unscoped on purpose: a pin is per application
   * name across all environments, and the fetch join is what keeps that one query rather than one
   * per row.
   */
  public List<CdDeployment> listAllNewestFirst() {
    return list(
        "select d from CdDeployment d join fetch d.application order by d.createdAt desc, d.id desc");
  }

  /** Every deployment of one application row — what a conversion moves onto the singleton. */
  public List<CdDeployment> listByApplication(String applicationId) {
    return list("application.id = ?1", applicationId);
  }

  /** The application's currently serving deployment(s) — by invariant at most one. */
  public List<CdDeployment> listActiveByApplication(String applicationId) {
    return list("application.id = ?1 and status = ?2", applicationId, CdDeploymentStatus.ACTIVE);
  }

  public List<CdDeployment> listByStatus(CdDeploymentStatus status) {
    return list("status = ?1", status);
  }
}
