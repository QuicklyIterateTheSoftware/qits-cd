package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One application an environment tracks: a repository (a plain String id — cd holds no FK into any
 * other context's tables) and the name the deployed container answers to. The name is load-bearing
 * three times over: it is the image path in the registry ({@code <repository>/<name>:<sha>}), the
 * network alias peers on the environment's network resolve, and part of the container name.
 *
 * <p>The FK to {@link CdEnvironment} is inside cd's own DB, which is fine — the "string ids, never
 * FK" rule is about other contexts' tables.
 */
@Entity
@Table(name = "cd_application")
public class CdApplication extends PanacheEntityBase {

  @Id public String id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id", nullable = false)
  public CdEnvironment environment;

  /** The repository whose green builds produce this application's image. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** dns-safe: the network alias, the image path segment, part of the container name. */
  @Column(nullable = false, length = 64)
  public String name;

  /**
   * The path the health gate probes on the fresh container, at port 8080 (the platform's one
   * exposed port). Null means the shipped default ({@code qits.cd.default-health-path}) — note the
   * platform's own services serve health under their segment ({@code /cd/q/health/ready}), so a
   * qits service tracked here names its own.
   */
  @Column(name = "health_path")
  public String healthPath;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
