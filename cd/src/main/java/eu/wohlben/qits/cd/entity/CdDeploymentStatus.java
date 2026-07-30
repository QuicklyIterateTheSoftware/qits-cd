package eu.wohlben.qits.cd.entity;

/**
 * A deployment's lifecycle. {@code QUEUED} and {@code STARTING} are the only non-terminal states,
 * and neither survives a restart (the worker queue is in-memory; the startup sweep fails them).
 */
public enum CdDeploymentStatus {
  /** Recorded by the intake, waiting for the single-threaded deploy worker. */
  QUEUED,
  /** The worker is pulling the image, starting the container, or waiting on the health gate. */
  STARTING,
  /** Passed the health gate; its container serves the application on the environment's network. */
  ACTIVE,
  /**
   * The registry has no image for this (application, sha) — the honest name for "CI went green but
   * no image arrived", which stays a distinct state because it indicts the publishing convention
   * rather than the build. Publishing is a repository's own last pipeline step, so this state means
   * that pipeline publishes nothing or its tag broke the convention.
   */
  IMAGE_MISSING,
  /** Docker refused, the container died, or the health gate expired. The old container stays. */
  FAILED,
  /** Was ACTIVE; replaced by a newer deployment that passed the health gate. */
  DECOMMISSIONED
}
