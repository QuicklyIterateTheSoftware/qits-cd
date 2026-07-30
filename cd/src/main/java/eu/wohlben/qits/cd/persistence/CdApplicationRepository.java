package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdApplication;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link CdApplication}. */
@ApplicationScoped
public class CdApplicationRepository implements PanacheRepositoryBase<CdApplication, String> {

  /** An environment's applications, in creation order. */
  public List<CdApplication> listByEnvironment(String environmentId) {
    return list("environment.id = ?1 order by createdAt, id", environmentId);
  }

  /**
   * Every application a build-succeeded event addresses: same repository, and an environment
   * listening to exactly that branch. Usually zero or one; several environments may legitimately
   * track the same branch.
   */
  public List<CdApplication> listByRepoAndBranch(String repoId, String branch) {
    return list("repoId = ?1 and environment.branch = ?2", repoId, branch);
  }
}
