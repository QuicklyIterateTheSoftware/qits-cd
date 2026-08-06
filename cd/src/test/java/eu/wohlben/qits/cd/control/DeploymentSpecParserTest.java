package eu.wohlben.qits.cd.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.CdSpecSource.DeploymentSpec;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import org.junit.jupiter.api.Test;

/**
 * The strict reader of {@code .config/qits/deployments.yml}, plain JUnit — the file decides where a
 * container runs and what may reach it, so what it rejects matters as much as what it accepts, and
 * every rejection has to name the file a person has to go and fix.
 */
class DeploymentSpecParserTest {

  private static final String SOURCE = ".config/qits/deployments.yml of qits-cd@abc1234";

  private DeploymentSpec parse(String yaml) {
    return DeploymentSpecParser.parse(yaml, SOURCE);
  }

  private String messageOf(String yaml) {
    CdSpecException thrown = assertThrows(CdSpecException.class, () -> parse(yaml));
    assertTrue(thrown.getMessage().startsWith(SOURCE), "every error names the file: " + thrown.getMessage());
    return thrown.getMessage();
  }

  @Test
  void anEmptyFileIsEveryDefault() {
    // The whole of backward compatibility: a repository that says nothing is an ordinary
    // environment application, exactly as every repository was before this file existed.
    assertEquals(DeploymentSpec.DEFAULTS, parse(""));
    assertEquals(DeploymentSpec.DEFAULTS, parse("# nothing to say yet\n"));
  }

  @Test
  void theFourKeysAreReadAndCommentsAndQuotesAreNot() {
    DeploymentSpec spec =
        parse(
            """
            ---
            deployment_target: singleton   # platform-plane

            branch: "release"
            health_path: /idp/q/health/ready
            """);
    assertEquals(CdDeploymentTarget.SINGLETON, spec.target());
    assertEquals("release", spec.branch());
    assertEquals("release", spec.singletonBranch());
    assertFalse(spec.availableOnEnv());
    assertEquals("/idp/q/health/ready", spec.healthPath());
  }

  @Test
  void aFileThatNamesNoHealthPathLeavesItToTheConvention() {
    // Null is the statement "this repository said nothing", and registration turns that into the
    // derived path. The parser must not invent one here — it does not know the service's name.
    assertNull(parse("available_on_env: true\n").healthPath());
  }

  @Test
  void aHealthPathThatIsNotAnAbsolutePathIsAnError() {
    // The value ends up in a container's --health-cmd, so it is checked as strictly as the API's.
    assertTrue(messageOf("health_path: q/health/ready\n").contains("health_path"));
    assertTrue(messageOf("health_path: /q/health/ready?x=1\n").contains("health_path"));
    assertTrue(messageOf("health_path:\n").contains("health_path"));
  }

  @Test
  void aSingletonThatNamesNoBranchDeploysFromMain() {
    DeploymentSpec spec = parse("deployment_target: singleton\n");
    assertNull(spec.branch(), "the file said nothing, and the record says so");
    assertEquals("main", spec.singletonBranch());
  }

  @Test
  void aPublicNodeSaysSoAndNothingElseDoes() {
    assertTrue(parse("available_on_env: true\n").availableOnEnv());
    assertFalse(parse("available_on_env: false\n").availableOnEnv());
  }

  @Test
  void anUnknownKeyIsAnError() {
    // A lenient parser answers a typo with a default, which deploys the wrong topology in silence.
    assertTrue(messageOf("deployment_targets: singleton\n").contains("unknown key"));
  }

  @Test
  void aDuplicateKeyIsAnError() {
    assertTrue(
        messageOf("deployment_target: environment\ndeployment_target: singleton\n")
            .contains("duplicate key"));
  }

  @Test
  void aValueOutsideTheEnumIsAnError() {
    assertTrue(messageOf("deployment_target: Singleton\n").contains("environment"));
    assertTrue(messageOf("available_on_env: yes\n").contains("true"));
  }

  @Test
  void aPublicSingletonIsAContradiction() {
    // It already runs on every environment's networks, and the bundle is environment-scoped.
    String message = messageOf("deployment_target: singleton\navailable_on_env: true\n");
    assertTrue(message.contains("available_on_env"), message);
  }

  @Test
  void nestingAndNonMappingLinesAreErrors() {
    assertTrue(
        messageOf("available_on_env: false\n  deployment_target: singleton\n").contains("nesting"));
    assertTrue(messageOf("just a sentence\n").contains("key: value"));
  }

  @Test
  void aBranchThatIsNotAPlainRefNameIsAnError() {
    assertTrue(messageOf("branch: ../../etc\n").contains("branch"));
  }
}
