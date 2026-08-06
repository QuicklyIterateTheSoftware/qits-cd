package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdEnvironment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link CdEnvironment} (keyed by its String UUID row id). */
@ApplicationScoped
public class CdEnvironmentRepository implements PanacheRepositoryBase<CdEnvironment, String> {

  public Optional<CdEnvironment> findByName(String name) {
    return find("name = ?1", name).firstResultOptional();
  }

  /** All environments, newest-first. */
  public List<CdEnvironment> listNewestFirst() {
    return list("order by createdAt desc, id desc");
  }

  /**
   * Every environment listening to exactly this branch — what derived registration fans a green
   * build out over. Usually one; two tiers may legitimately track the same ref.
   */
  public List<CdEnvironment> listByBranch(String branch) {
    return list("branch = ?1 order by createdAt, id", branch);
  }
}
