package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One attempt to put one commit of one application live. Created {@code QUEUED} by the
 * build-succeeded intake, driven to a terminal state by the deploy worker; the previously {@code
 * ACTIVE} deployment of the same application becomes {@code DECOMMISSIONED} the moment its
 * replacement passes the health gate — never before.
 */
@Entity
@Table(name = "cd_deployment")
public class CdDeployment extends PanacheEntityBase {

  @Id public String id;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "application_id", nullable = false)
  public CdApplication application;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  public CdDeploymentStatus status;

  /**
   * The container this deployment started (named after the deployment, not the sha, so re-deploying
   * the same commit never collides). Null until the worker actually ran {@code docker run}, and on
   * every deployment that failed before one existed.
   */
  @Column(name = "container_name")
  public String containerName;

  /** What went wrong (docker's own output, bounded), or null on the happy path. */
  @Column(columnDefinition = "clob")
  public String detail;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "finished_at")
  public Instant finishedAt;
}
