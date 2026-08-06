package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One attempt to put one commit of one application live. Created {@code QUEUED} by the
 * build-succeeded intake, driven to a terminal state by the deploy worker; the previously {@code
 * ACTIVE} deployment of the same application becomes {@code DECOMMISSIONED} the moment its
 * replacement passes the health gate — never before.
 *
 * <p><b>This is the whole of cd's own domain now.</b> The application and the environment it names
 * live in qits-serviceregistry, so they are plain strings here with no FK — the {@code repo_id}
 * stance, applied across a service boundary. That is what lets the deployment history, and the
 * rollback pins read off it, stay up while the registry is down.
 */
@Entity
@Table(name = "cd_deployment")
public class CdDeployment extends PanacheEntityBase {

  @Id public String id;

  /** The service this deployed, by name — the registry's own identity for it. */
  @Column(name = "application_name", nullable = false, length = 64)
  public String applicationName;

  /** The tier it was deployed into, or null for a platform singleton. */
  @Column(name = "environment_id")
  public String environmentId;

  @Column(name = "commit_sha", nullable = false, length = 64)
  public String commitSha;

  /**
   * The qits-ci run whose green build caused this deployment, as the intake received it — cd's one
   * pointer back into the pipeline that produced the image, and nothing it ever resolves itself (no
   * FK, the repo_id stance). Null on every row recorded before V2, on a sender that omits it, and on
   * anything cd queued for itself while running an older build; a reader must render that absence
   * rather than invent a link.
   */
  @Column(name = "run_id")
  public String runId;

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

  /**
   * The listing tiebreak, assigned by the database (V5's identity column) and never written here —
   * which is why it reads null on a freshly persisted instance.
   *
   * <p>It exists because {@code createdAt} is not unique: two rows recorded in the same tick tied,
   * and the secondary sort was the random-UUID id, so a listing swapped them arbitrarily between
   * calls. This is monotonic, so "newest first" is one answer rather than a coin flip.
   */
  @Column(name = "seq", insertable = false, updatable = false)
  public Long seq;
}
