package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdApplication;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Panache DAO for the FROZEN {@link CdApplication} table.
 *
 * <p>One reader is left — the one-time export in {@code RegistryExport} — because qits-serviceregistry
 * owns applications now. The table is not dropped this release; a later cleanup migration takes it
 * and {@code cd_environment} together, once the export has proven itself on the live platform.
 */
@ApplicationScoped
public class CdApplicationRepository implements PanacheRepositoryBase<CdApplication, String> {

  /**
   * Every application row on this instance — environment-scoped and singleton alike, oldest first,
   * with the environment fetched so the export can read its id outside the transaction.
   */
  public List<CdApplication> listAll() {
    return list(
        "select a from CdApplication a left join fetch a.environment order by a.createdAt, a.id");
  }
}
