package eu.wohlben.qits.cd.control;

/**
 * The id an application is addressed by on cd's read surface, derived rather than stored.
 *
 * <p>Applications no longer have a row here — they live in qits-serviceregistry, where one service
 * carries N environment links — while a deployment row names only {@code (application_name,
 * environment_id)}. The client joins the two listings on an id, so the id has to be computable from
 * both sides: {@code <environmentId>:<name>}, and {@code singleton:<name>} for a service that
 * belongs to no tier.
 *
 * <p>It is also the grouping key of {@link RollbackPins}: one application name in two environments
 * is two histories, and merging them would name the wrong rollback target.
 */
public final class CdApplicationKeys {

  /** Where a singleton's key stands in for an environment id — no environment can take the place. */
  private static final String SINGLETON = "singleton";

  private CdApplicationKeys() {}

  public static String of(String environmentId, String applicationName) {
    return (environmentId == null ? SINGLETON : environmentId) + ":" + applicationName;
  }
}
