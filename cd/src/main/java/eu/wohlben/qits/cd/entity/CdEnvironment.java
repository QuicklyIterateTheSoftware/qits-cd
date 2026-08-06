package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One deployment environment — a <b>tier</b>: dev, preprod, prod.
 *
 * <p><b>FROZEN.</b> qits-serviceregistry owns environments since the extraction; the environment
 * surface on this service proxies to it. These rows are cd v1's, kept for one release so the
 * one-time export ({@code RegistryExport}) can be repeated or audited, and read by nothing else.
 * A later cleanup migration drops this table and {@code cd_application} together.
 */
@Entity
@Table(name = "cd_environment")
public class CdEnvironment extends PanacheEntityBase {

  @Id public String id;

  /** Unique, git-and-dns-safe slug — the environment's identity everywhere a human sees it. */
  @Column(nullable = false, unique = true, length = 64)
  public String name;

  /**
   * The branch this environment listens to. A build-succeeded event deploys here exactly when its
   * branch equals this value — convention fills it as {@code environment/<name>} when the creator
   * names none.
   */
  @Column(nullable = false)
  public String branch;

  /**
   * This environment's <b>bundle</b> network: the one its public nodes ({@code availableOnEnv})
   * share. It is not where an ordinary application runs — each application gets its own derived
   * {@code qits-env-<env>-<app>} network, and the public nodes join all of them. Derived networks
   * are never persisted; docker's own labels are the bookkeeping.
   */
  @Column(nullable = false)
  public String network;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
