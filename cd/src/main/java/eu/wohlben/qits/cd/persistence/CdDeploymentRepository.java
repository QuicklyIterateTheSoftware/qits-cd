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

  /** The application's currently serving deployment(s) — by invariant at most one. */
  public List<CdDeployment> listActiveByApplication(String applicationId) {
    return list("application.id = ?1 and status = ?2", applicationId, CdDeploymentStatus.ACTIVE);
  }

  public List<CdDeployment> listByStatus(CdDeploymentStatus status) {
    return list("status = ?1", status);
  }
}
