package eu.wohlben.qits.cd.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One deployment environment — a <b>tier</b>: dev, preprod, prod. A name, the branch whose green
 * builds deploy into it, and the docker network its public nodes share. Created deliberately over
 * the environment surface; a tier is not something a build invents.
 *
 * <p>The applications it holds are {@link CdApplication} rows, and those are <b>derived</b>: a
 * green build on {@link #branch} registers or updates the repository's application here. Nothing
 * declares them over the API.
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
