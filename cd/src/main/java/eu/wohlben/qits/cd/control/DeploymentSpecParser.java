package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.control.CdSpecSource.DeploymentSpec;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import java.util.HashSet;
import java.util.Set;

/**
 * The strict reader of {@code .config/qits/deployments.yml}. Four scalar keys, no nesting, no
 * lists — so this is a line reader rather than a YAML library, and being one is what makes every
 * rejection a sentence naming the file and the line.
 *
 * <pre>
 * deployment_target: environment   # default when the key or the file is absent | singleton
 * available_on_env: false          # default; true = public node (bundle + hub joins)
 * branch: main                     # singleton only: the branch that deploys it (default main)
 * health_path: /q/health/ready     # default: /&lt;name without the qits- prefix&gt;/q/health/ready
 * </pre>
 *
 * <p><b>Strict on purpose.</b> A typo in this file decides where a container runs and what can
 * reach it, and a lenient parser answers a typo with a default — silently deploying the wrong
 * topology and leaving nothing to read. So an unknown key, a repeated key, a value outside the
 * enum and a line that is not {@code key: value} are all errors, and the deployment fails on them.
 * The one thing that is <em>not</em> an error is the file's absence: no file means every default,
 * which is what every repository already behaves like.
 */
public final class DeploymentSpecParser {

  private static final String TARGET = "deployment_target";
  private static final String AVAILABLE_ON_ENV = "available_on_env";
  private static final String BRANCH = "branch";
  private static final String HEALTH_PATH = "health_path";

  private DeploymentSpecParser() {}

  /**
   * @param source how the file is named back to a reader — the whole point of the error messages
   * @throws CdSpecException on anything this schema does not describe
   */
  public static DeploymentSpec parse(String yaml, String source) {
    CdDeploymentTarget target = CdDeploymentTarget.ENVIRONMENT;
    boolean availableOnEnv = false;
    String branch = null;
    String healthPath = null;
    Set<String> seen = new HashSet<>();

    String[] lines = (yaml == null ? "" : yaml).split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      String raw = lines[i];
      int lineNumber = i + 1;
      String line = stripComment(raw);
      if (line.isBlank() || line.strip().equals("---")) {
        continue;
      }
      if (Character.isWhitespace(line.charAt(0))) {
        throw error(source, lineNumber, "indented lines — this file has no nesting");
      }
      int colon = line.indexOf(':');
      if (colon < 1) {
        throw error(source, lineNumber, "expected `key: value`, got: " + line.strip());
      }
      String key = line.substring(0, colon).strip();
      String value = unquote(line.substring(colon + 1).strip());
      if (!seen.add(key)) {
        throw error(source, lineNumber, "duplicate key `" + key + "`");
      }
      switch (key) {
        case TARGET -> target = target(value, source, lineNumber);
        case AVAILABLE_ON_ENV -> availableOnEnv = bool(key, value, source, lineNumber);
        case BRANCH -> branch = branch(value, source, lineNumber);
        case HEALTH_PATH -> healthPath = healthPath(value, source, lineNumber);
        default ->
            throw error(
                source,
                lineNumber,
                "unknown key `"
                    + key
                    + "` — this file knows "
                    + TARGET
                    + ", "
                    + AVAILABLE_ON_ENV
                    + ", "
                    + BRANCH
                    + " and "
                    + HEALTH_PATH);
      }
    }

    if (availableOnEnv && target == CdDeploymentTarget.SINGLETON) {
      throw new CdSpecException(
          source
              + ": `"
              + AVAILABLE_ON_ENV
              + ": true` is not something a singleton can be — it already runs on every"
              + " environment's networks, and the bundle is environment-scoped");
    }
    return new DeploymentSpec(target, availableOnEnv, branch, healthPath);
  }

  private static CdDeploymentTarget target(String value, String source, int line) {
    // Lowercase in the file, uppercase in the enum: the yaml is written by a person and the column
    // is read by a machine, and neither should have to spell the other's convention.
    for (CdDeploymentTarget candidate : CdDeploymentTarget.values()) {
      if (candidate.name().toLowerCase(java.util.Locale.ROOT).equals(value)) {
        return candidate;
      }
    }
    throw error(source, line, "`" + TARGET + "` must be `environment` or `singleton`, got: " + value);
  }

  private static boolean bool(String key, String value, String source, int line) {
    if ("true".equals(value)) {
      return true;
    }
    if ("false".equals(value)) {
      return false;
    }
    throw error(source, line, "`" + key + "` must be `true` or `false`, got: " + value);
  }

  private static String branch(String value, String source, int line) {
    try {
      return CdIdentifiers.requireBranch(value);
    } catch (RuntimeException e) {
      throw error(source, line, "`" + BRANCH + "` is not a plain ref name: " + value);
    }
  }

  /**
   * The health gate's URL path inside the container, checked here with the same rule the API uses —
   * an absolute path and nothing a shell or an argv would read as punctuation, because this value
   * ends up in a {@code --health-cmd}.
   */
  private static String healthPath(String value, String source, int line) {
    try {
      return CdIdentifiers.requireHealthPath(value);
    } catch (RuntimeException e) {
      throw error(source, line, "`" + HEALTH_PATH + "` is not an absolute URL path: " + value);
    }
  }

  /**
   * Drops a {@code #} comment. A {@code #} only starts one at the beginning of the line or after
   * whitespace, which is YAML's own rule and the reason {@code branch: fix#123} keeps its hash.
   */
  private static String stripComment(String line) {
    for (int i = 0; i < line.length(); i++) {
      if (line.charAt(i) == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && (value.charAt(0) == '"' || value.charAt(0) == '\'')
        && value.charAt(value.length() - 1) == value.charAt(0)) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static CdSpecException error(String source, int line, String what) {
    return new CdSpecException(source + " line " + line + ": " + what);
  }
}
