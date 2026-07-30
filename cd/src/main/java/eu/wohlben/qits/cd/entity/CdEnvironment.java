package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One deployment environment: a name (by convention the epic slug), the branch whose green builds
 * deploy into it, and the docker network its containers share. Created over the environment surface
 * by whatever orchestrates an epic (qits-projects), torn down the same way.
 *
 * <p>The environment does not know an epic id — cd lives in its own physical DB with NO FK into any
 * other context's tables, and the name/branch pair is the whole contract. The applications it
 * tracks are {@link CdApplication} rows; deployments hang off those.
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
   * branch equals this value — convention fills it as {@code epic/<name>} when the creator names
   * none.
   */
  @Column(nullable = false)
  public String branch;

  /**
   * The docker network this environment's containers join — one network per environment, so two
   * environments' stacks can never resolve each other's aliases (the documented
   * two-stacks-collide-on-qits-net failure, avoided by construction).
   */
  @Column(nullable = false)
  public String network;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
