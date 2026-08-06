package eu.wohlben.qits.cd.control;

/**
 * The docker network names cd derives, in one place — the topology is hub-and-spoke and the names
 * are the whole of how it is addressed.
 *
 * <ul>
 *   <li><b>Per application</b> ({@link #application}): where an environment's application actually
 *       runs. Only its own containers are on it, so nothing in the environment can reach it without
 *       being joined to it deliberately.
 *   <li><b>Per environment bundle</b> (the environment row's own {@code network}): the
 *       environment's public nodes. One member today (qits-gateway) — kept because "the public
 *       nodes of this environment" is a set worth having a name for.
 *   <li><b>Platform</b> ({@link #PLATFORM}): where singletons run. They join every environment's
 *       per-application networks on top, which is what makes them locally reachable everywhere.
 * </ul>
 *
 * <p>Nothing here is persisted. A network's membership is read back from docker's labels, never
 * from a row — one bookkeeping, and it is the runtime's.
 */
public final class CdNetworks {

  /** The singleton's primary network, created on demand. Singletons belong to no environment. */
  public static final String PLATFORM = "qits-platform";

  /** The bundle network an environment gets when its creator names none. */
  public static final String BUNDLE_PREFIX = "qits-env-";

  private CdNetworks() {}

  /** The bundle network of an environment that named none: {@code qits-env-<env>}. */
  public static String bundle(String environmentName) {
    return BUNDLE_PREFIX + environmentName;
  }

  /** One application's own network inside an environment: {@code qits-env-<env>-<app>}. */
  public static String application(String environmentName, String applicationName) {
    return BUNDLE_PREFIX + environmentName + "-" + applicationName;
  }
}
