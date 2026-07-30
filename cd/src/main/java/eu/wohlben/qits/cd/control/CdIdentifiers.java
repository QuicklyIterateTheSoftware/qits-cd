package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.error.BadRequestException;

/**
 * Validates the untrusted strings that reach an argv or a container's health command. They arrive
 * from the environment surface and the build-succeeded intake — both machine surfaces whose token
 * is blank in dev, so both attacker-reachable by design — and several of them are used in places
 * where looseness would be expensive: the environment name and application name become a docker
 * network name, a network alias, an image reference and a container name; the health path is
 * interpolated into the container's own {@code --health-cmd} shell string.
 *
 * <p>Defence in depth, not the only guard: argvs are assembled for {@link ProcessBuilder}, which
 * never re-splits — but the health command IS a shell string the container runs, so {@link
 * #requireHealthPath} is deliberately the strictest check here rather than a formality.
 */
public final class CdIdentifiers {

  /** Same slug the git host accepts for a repo id — no separators, no leading dash. */
  private static final String REPO_ID = "[A-Za-z0-9][A-Za-z0-9-]{0,63}";

  /** A hex object id (abbreviated ids are accepted; the registry resolves the tag either way). */
  private static final String SHA = "[0-9a-f]{7,64}";

  /** Conservative subset of valid ref names — enough for real branches, hostile to nothing else. */
  private static final String BRANCH = "[A-Za-z0-9._][A-Za-z0-9._/-]{0,254}";

  /**
   * dns-safe lowercase slug: environment and application names become docker network names, network
   * aliases and image path segments, and one day hostname labels ({@code <app>.<env>.qits-dev.eu})
   * — so the charset is the hostname-label one from the start.
   */
  private static final String NAME = "[a-z0-9][a-z0-9-]{0,62}";

  /**
   * An absolute http path with no room for shell metacharacters — this value lands inside the
   * container's {@code --health-cmd} string, so the allowlist is the guard.
   */
  private static final String HEALTH_PATH = "/[A-Za-z0-9._/-]{0,254}";

  private CdIdentifiers() {}

  /**
   * @throws BadRequestException if the repo id could escape an argv
   */
  public static String requireRepoId(String repoId) {
    if (repoId == null || !repoId.matches(REPO_ID)) {
      throw new BadRequestException("Invalid repository id");
    }
    return repoId;
  }

  /**
   * @throws BadRequestException if the sha is not a plain hex object id
   */
  public static String requireSha(String sha) {
    if (sha == null || !sha.matches(SHA)) {
      throw new BadRequestException("Invalid commit sha");
    }
    return sha;
  }

  /**
   * @throws BadRequestException if the branch is not a plain, non-tricky ref name
   */
  public static String requireBranch(String branch) {
    if (branch == null
        || !branch.matches(BRANCH)
        || branch.contains("..")
        || branch.contains("//")
        || branch.endsWith("/")
        || branch.endsWith(".lock")) {
      throw new BadRequestException("Invalid branch name");
    }
    return branch;
  }

  /**
   * An environment or application name — the dns-label charset, because these become network names,
   * aliases, image path segments and (eventually) hostname labels.
   *
   * @throws BadRequestException if the name is not a lowercase dns-safe slug
   */
  public static String requireName(String name, String what) {
    if (name == null || !name.matches(NAME) || name.endsWith("-")) {
      throw new BadRequestException(
          "Invalid " + what + " — lowercase letters, digits and inner dashes, max 63 chars");
    }
    return name;
  }

  /**
   * The health gate's probe path. This is the one validated value that ends up inside a shell
   * string (the container's own {@code --health-cmd}), so it gets an allowlist rather than a
   * denylist and no exceptions.
   *
   * @throws BadRequestException if the path is not an absolute, metacharacter-free http path
   */
  public static String requireHealthPath(String healthPath) {
    if (healthPath == null || !healthPath.matches(HEALTH_PATH)) {
      throw new BadRequestException(
          "Invalid health path — an absolute path of letters, digits, dots, dashes and slashes");
    }
    return healthPath;
  }
}
