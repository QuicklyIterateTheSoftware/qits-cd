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
  private final List<String> stoppedContainers = Collections.synchronizedList(new ArrayList<>());
  private final List<String> restartedContainers = Collections.synchronizedList(new ArrayList<>());

  /** Every driver call in arrival order, tagged `kind:target` — the cutover ORDER assertions. */
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());

  private final List<HandoffSpec> handoffs = Collections.synchronizedList(new ArrayList<>());
  private final java.util.Map<String, String> containerIds = new java.util.concurrent.ConcurrentHashMap<>();

  private volatile PullResult nextPull = new PullResult(PullOutcome.OK, null);
  private volatile StartResult nextStart = new StartResult(true, null);
  private volatile HealthResult nextHealth = new HealthResult(true, null);
  private volatile List<Holder> nextHolders = List.of();
  private volatile String selfId = "";

  public void reset() {
    ensuredNetworks.clear();
    removedNetworks.clear();
    pulledRefs.clear();
    started.clear();
    awaited.clear();
    removedContainers.clear();
    removedEnvironments.clear();
    stoppedContainers.clear();
    restartedContainers.clear();
    calls.clear();
    handoffs.clear();
    containerIds.clear();
    nextPull = new PullResult(PullOutcome.OK, null);
    nextStart = new StartResult(true, null);
    nextHealth = new HealthResult(true, null);
    nextHolders = List.of();
    selfId = "";
  }

  public void scriptContainerId(String containerName, String id) {
    containerIds.put(containerName, id);
  }

  public List<HandoffSpec> handoffs() {
    return List.copyOf(handoffs);
  }

  public void scriptAliasHolders(List<Holder> holders) {
    nextHolders = holders;
  }

  public void scriptSelfId(String id) {
    selfId = id;
  }

  public List<String> stoppedContainers() {
    return List.copyOf(stoppedContainers);
  }

  public List<String> restartedContainers() {
    return List.copyOf(restartedContainers);
  }

  public List<String> calls() {
    return List.copyOf(calls);
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
  public List<Holder> aliasHolders(String network, String alias) {
    calls.add("aliasHolders:" + alias);
    return nextHolders;
  }

  @Override
  public void stop(String containerName) {
    stoppedContainers.add(containerName);
    calls.add("stop:" + containerName);
  }

  @Override
  public void restart(String containerName) {
    restartedContainers.add(containerName);
    calls.add("restart:" + containerName);
  }

  @Override
  public String selfContainerId() {
    return selfId;
  }

  @Override
  public String containerId(String containerName) {
    return containerIds.getOrDefault(containerName, "");
  }

  @Override
  public void handoff(HandoffSpec spec) {
    handoffs.add(spec);
    calls.add("handoff:" + spec.newContainerName());
  }

  @Override
  public StartResult start(StartSpec spec) {
    started.add(spec);
    calls.add("start:" + spec.containerName());
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
    calls.add("remove:" + containerName);
  }

  @Override
  public int removeEnvironmentContainers(String environmentId) {
    removedEnvironments.add(environmentId);
    return 0;
  }
}
