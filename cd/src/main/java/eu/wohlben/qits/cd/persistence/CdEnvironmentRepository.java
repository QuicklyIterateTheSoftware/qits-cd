package eu.wohlben.qits.cd.persistence;

import eu.wohlben.qits.cd.entity.CdEnvironment;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Panache DAO for the FROZEN {@link CdEnvironment} table.
 *
 * <p>One reader is left — the one-time export in {@code RegistryExport} — because
 * qits-serviceregistry owns environments now. The table is not dropped this release; a later cleanup
 * migration takes it and {@code cd_application} together, once the export has proven itself on the
 * live platform.
 */
@ApplicationScoped
public class CdEnvironmentRepository implements PanacheRepositoryBase<CdEnvironment, String> {

  /** All environments, newest-first — what the export walks. */
  public List<CdEnvironment> listNewestFirst() {
    return list("order by createdAt desc, id desc");
  }
}
