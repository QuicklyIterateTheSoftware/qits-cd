package eu.wohlben.qits.cd.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's stand-in for the docker seam — a scripted fake, not an honest one: it performs
 * nothing, records every call, and answers what the test told it to. {@code @Mock} makes it the
 * {@link DeploymentDriver} for every {@code @QuarkusTest} in this module, which is what keeps a
 * clone's {@code mvn verify} docker-free (the FakeCiStepRunner stance).
 *
 * <p>Application-scoped and therefore shared across tests: reset it in {@code @BeforeEach} and use
 * distinct environment names per test. State is exposed through <b>methods only</b> — the injected
 * reference is a CDI client proxy, and a field read on a proxy sees the proxy's own fields, never
 * the bean's.
 */
@Mock
@ApplicationScoped
public class FakeDeploymentDriver implements DeploymentDriver {

  private final List<String> ensuredNetworks = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedNetworks = Collections.synchronizedList(new ArrayList<>());
  private final List<String> pulledRefs = Collections.synchronizedList(new ArrayList<>());
  private final List<StartSpec> started = Collections.synchronizedList(new ArrayList<>());
  private final List<String> awaited = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedContainers = Collections.synchronizedList(new ArrayList<>());
  private final List<String> removedEnvironments = Collections.synchronizedList(new ArrayList<>());

  private volatile PullResult nextPull = new PullResult(PullOutcome.OK, null);
  private volatile StartResult nextStart = new StartResult(true, null);
  private volatile HealthResult nextHealth = new HealthResult(true, null);

  public void reset() {
    ensuredNetworks.clear();
    removedNetworks.clear();
    pulledRefs.clear();
    started.clear();
    awaited.clear();
    removedContainers.clear();
    removedEnvironments.clear();
    nextPull = new PullResult(PullOutcome.OK, null);
    nextStart = new StartResult(true, null);
    nextHealth = new HealthResult(true, null);
  }

  public void scriptPull(PullResult result) {
    nextPull = result;
  }

  public void scriptStart(StartResult result) {
    nextStart = result;
  }

  public void scriptHealth(HealthResult result) {
    nextHealth = result;
  }

  public List<String> ensuredNetworks() {
    return List.copyOf(ensuredNetworks);
  }

  public List<String> removedNetworks() {
    return List.copyOf(removedNetworks);
  }

  public List<String> pulledRefs() {
    return List.copyOf(pulledRefs);
  }

  public List<StartSpec> started() {
    return List.copyOf(started);
  }

  public List<String> awaited() {
    return List.copyOf(awaited);
  }

  public List<String> removedContainers() {
    return List.copyOf(removedContainers);
  }

  public List<String> removedEnvironments() {
    return List.copyOf(removedEnvironments);
  }

  @Override
  public void ensureNetwork(String network) {
    ensuredNetworks.add(network);
  }

  @Override
  public void removeNetwork(String network) {
    removedNetworks.add(network);
  }

  @Override
  public PullResult pull(String imageRef) {
    pulledRefs.add(imageRef);
    return nextPull;
  }

  @Override
  public StartResult start(StartSpec spec) {
    started.add(spec);
    return nextStart;
  }

  @Override
  public HealthResult awaitHealthy(String containerName, Duration timeout) {
    awaited.add(containerName);
    return nextHealth;
  }

  @Override
  public void remove(String containerName) {
    removedContainers.add(containerName);
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    removedEnvironments.add(environmentId);
    return 0;
  }
}
