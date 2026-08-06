package eu.wohlben.qits.cd.control;

import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the git host — {@code @Mock}, so no {@code @QuarkusTest} in this module
 * ever makes cd's one outbound HTTP call.
 *
 * <p><b>Its default answer is {@link DeploymentSpec#DEFAULTS}</b>, and that is the backward
 * compatibility contract in one line: a repository that declares nothing behaves exactly as every
 * repository did before the file existed. A test that wants a spec scripts one for its repository
 * by name.
 *
 * <p>Application-scoped and therefore shared: reset it in {@code @BeforeEach} and use distinct
 * repository ids per test. State is read through methods only — the injected reference is a CDI
 * client proxy.
 */
@Mock
@ApplicationScoped
public class FakeSpecSource implements CdSpecSource {

  private final Map<String, DeploymentSpec> specs = new ConcurrentHashMap<>();
  private final Map<String, String> failures = new ConcurrentHashMap<>();

  public void reset() {
    specs.clear();
    failures.clear();
  }

  /** What {@code repoId} declares, whatever sha is asked for. */
  public void script(String repoId, DeploymentSpec spec) {
    specs.put(repoId, spec);
  }

  /** Script the git host being unreachable, or the file being unreadable, for this repository. */
  public void scriptFailure(String repoId, String message) {
    failures.put(repoId, message);
  }

  @Override
  public DeploymentSpec read(String repoId, String sha) {
    String failure = failures.get(repoId);
    if (failure != null) {
      throw new CdSpecException(failure);
    }
    return specs.getOrDefault(repoId, DeploymentSpec.DEFAULTS);
  }
}
