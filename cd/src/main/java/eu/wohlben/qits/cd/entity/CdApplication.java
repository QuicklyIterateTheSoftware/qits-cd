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
 * One deployable application: a repository (a plain String id — cd holds no FK into any other
 * context's tables) and the name the deployed container answers to. The name is load-bearing three
 * times over: it is the image path in the registry ({@code <repository>/<name>:<sha>}), the network
 * alias peers resolve, and part of the container name.
 *
 * <p><b>Rows here are derived, not declared.</b> A green build carries cd to the repository's
 * {@code .config/qits/deployments.yml} at that commit, and the row is created or brought up to date
 * from it. Nothing has to register an application first.
 *
 * <p>{@link #environment} is null exactly when {@link #deploymentTarget} is {@code SINGLETON} — the
 * two say the same thing, one as a null and one as a word. A singleton is platform-plane: it has no
 * tier, so it carries its own {@link #branch}; an environment application takes its branch from its
 * environment.
 *
 * <p>The FK to {@link CdEnvironment} is inside cd's own DB, which is fine — the "string ids, never
 * FK" rule is about other contexts' tables.
 */
@Entity
@Table(name = "cd_application")
public class CdApplication extends PanacheEntityBase {

  @Id public String id;

  /** The environment this application belongs to, or null for a singleton. */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "environment_id")
  public CdEnvironment environment;

  /** The repository whose green builds produce this application's image. */
  @Column(name = "repo_id", nullable = false)
  public String repoId;

  /** dns-safe: the network alias, the image path segment, part of the container name. */
  @Column(nullable = false, length = 64)
  public String name;

  /** Environment-scoped or platform-plane. Never null; {@code ENVIRONMENT} is the default shape. */
  @Enumerated(EnumType.STRING)
  @Column(name = "deployment_target", nullable = false, length = 32)
  public CdDeploymentTarget deploymentTarget = CdDeploymentTarget.ENVIRONMENT;

  /**
   * The branch whose green builds deploy this application — <b>singletons only</b>, because they
   * have no environment to take one from. Null on every environment application; reading it there
   * instead of the environment's own branch would be a second, drifting answer.
   */
  @Column(length = 255)
  public String branch;

  /**
   * A public node of its environment: it joins the environment's bundle network and every one of
   * the environment's per-application networks, so it can reach every application and every
   * application can reach it. Today that is qits-gateway and nothing else — cross-application
   * traffic is meant to flow app → gateway → target app.
   */
  @Column(name = "available_on_env", nullable = false)
  public boolean availableOnEnv;

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
