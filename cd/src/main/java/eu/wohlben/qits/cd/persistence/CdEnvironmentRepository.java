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
}
