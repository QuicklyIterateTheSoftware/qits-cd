package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link CdApplication}. */
@ApplicationScoped
public class CdApplicationRepository implements PanacheRepositoryBase<CdApplication, String> {

  /** An environment's applications, in creation order. */
  public List<CdApplication> listByEnvironment(String environmentId) {
    return list(
        "select a from CdApplication a left join fetch a.environment"
            + " where a.environment.id = ?1 order by a.createdAt, a.id",
        environmentId);
  }

  /**
   * Every application row on this instance — environment-scoped and singleton alike, oldest first,
   * with the environment fetched so a mapper outside a transaction can read its name.
   */
  public List<CdApplication> listAll() {
    return list(
        "select a from CdApplication a left join fetch a.environment order by a.createdAt, a.id");
  }

  /** The repository's application inside one environment, if it was ever registered there. */
  public Optional<CdApplication> findByEnvironmentAndRepo(String environmentId, String repoId) {
    return find("environment.id = ?1 and repoId = ?2", environmentId, repoId).firstResultOptional();
  }

  /** Every environment-scoped row of a repository — what a conversion to a singleton absorbs. */
  public List<CdApplication> listEnvironmentScopedByRepo(String repoId) {
    return list(
        "repoId = ?1 and deploymentTarget = ?2", repoId, CdDeploymentTarget.ENVIRONMENT);
  }

  /** The repository's singleton row, if it has one. Uniqueness is this service's to keep. */
  public Optional<CdApplication> findSingletonByRepo(String repoId) {
    return find("repoId = ?1 and deploymentTarget = ?2", repoId, CdDeploymentTarget.SINGLETON)
        .firstResultOptional();
  }

  /** Every singleton, by name — what the "is this name taken" check reads. */
  public Optional<CdApplication> findSingletonByName(String name) {
    return find("name = ?1 and deploymentTarget = ?2", name, CdDeploymentTarget.SINGLETON)
        .firstResultOptional();
  }

  /**
   * Every application a build-succeeded event addresses through an environment: same repository,
   * and an environment listening to exactly that branch. Several environments may legitimately
   * track the same branch.
   */
  public List<CdApplication> listByRepoAndBranch(String repoId, String branch) {
    return list("repoId = ?1 and environment.branch = ?2", repoId, branch);
  }
}
