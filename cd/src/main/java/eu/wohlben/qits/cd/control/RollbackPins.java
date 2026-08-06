package eu.wohlben.qits.cd.control;

import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import eu.wohlben.qits.cd.persistence.CdDeploymentRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which image shas are rollback-relevant right now: per application, the sha it is serving and the
 * sha a rollback would put back.
 *
 * <p><b>Why this exists.</b> qits-artifacts' OCI garbage collector deletes an image tag only when
 * no pin names it, and cd is the only thing that knows what is running. The policy lives <em>here</em>,
 * beside {@link DeployService} — the code that performs the rollback — so the GC's keep-set and the
 * rollback target cannot drift: one definition, read by both. A keep-set computed in the service
 * that deletes rather than in the service that deploys is two definitions, and the day they
 * disagree the GC deletes the image a restart is about to pull.
 *
 * <p><b>The anchor to the real rollback.</b> {@link DeployService} decommissions the previously
 * {@code ACTIVE} row only when a fresh deployment passes its health gate, and a failed gate
 * <em>restarts</em> what the cutover stopped — leaving the previous deployment {@code ACTIVE} and
 * serving. So the deployment a rollback puts back is always the newest row that actually served:
 * {@code ACTIVE} now, or {@code DECOMMISSIONED} because a successor replaced it. That row's sha is
 * the second pin, and it is the sha a rollback pulls again.
 *
 * <p>The rules, each of which a case in {@code RollbackPinsTest} holds:
 *
 * <ol>
 *   <li><b>Per application row group, the serving sha.</b> The newest {@code ACTIVE} row's sha —
 *       the reference the container was created from, and the one a docker restart pulls again. An
 *       application with no {@code ACTIVE} row is serving nothing, so it pins nothing.
 *   <li><b>Then the previous <em>distinct</em> sha.</b> A redeploy of the same commit writes a
 *       second row at the same sha; reading that as the previous version would pin a duplicate of
 *       what is already running and drop the only thing a rollback could pull.
 *   <li><b>One rollback step, not the whole history.</b> Keeping every sha a row ever named
 *       reclaims nothing.
 *   <li><b>A row that never served is not a rollback target.</b> {@code FAILED},
 *       {@code IMAGE_MISSING}, {@code QUEUED} and {@code STARTING} rows are skipped rather than
 *       ending the search: an attempt that never passed a health gate is not a version to go back
 *       to, and going back to it is not what {@link DeployService} does. This is the one place cd's
 *       own data answers a question the artifacts-side implementation could not — its rule stopped
 *       at the first older row of any status, which on a history of
 *       {@code ACTIVE(A) / FAILED(C) / DECOMMISSIONED(B)} pins C and drops B, the sha that actually
 *       served.
 *   <li><b>Per application <em>name</em>, across every environment.</b> An application belongs to
 *       an environment, so one service in two environments is two application rows sharing one
 *       name — and the name is the image name, since every pull is
 *       {@code &lt;repository&gt;/&lt;name&gt;:&lt;sha&gt;} ({@link ImageRefs}). Both environments'
 *       shas pin that image; naming one environment would leave the other's next restart with no
 *       image.
 * </ol>
 *
 * <p><b>Ordering is deterministic and the shas are a set, not a sequence.</b> Pins come sorted by
 * application name; within one, the serving shas sorted, then the rollback shas sorted, each sha
 * once. A union over environments has no "most recent" to order by, so a reader must treat the
 * list as a set of shas to keep.
 */
@ApplicationScoped
public class RollbackPins {

  /** The states in which a deployment served traffic — the only rows a rollback can go back to. */
  private static final Set<CdDeploymentStatus> SERVED =
      Set.of(CdDeploymentStatus.ACTIVE, CdDeploymentStatus.DECOMMISSIONED);

  @Inject CdDeploymentRepository deployments;

  /** One application name and every sha that must survive for it. */
  public record Pin(String applicationName, List<String> shas) {}

  /**
   * One deployment row reduced to what the rule reads. Grouped by {@code applicationId} and
   * reported by {@code applicationName}: the id is what makes an environment's history its own, the
   * name is what a pin addresses. Since the extraction the id is derived from the row's own
   * {@code (environmentId, applicationName)} pair ({@link CdApplicationKeys}) rather than read off
   * an application row — same grouping, no registry involved.
   */
  public record Row(
      String applicationId, String applicationName, String commitSha, CdDeploymentStatus status) {}

  /**
   * The pins over every environment this instance knows, <b>from cd's own deployment rows alone</b>.
   *
   * <p>That independence is the point rather than an accident: qits-artifacts' image GC reads this
   * fail-closed and deletes nothing when cd cannot answer, so a pin that needed qits-serviceregistry
   * would make a registry outage stop garbage collection across the platform. Everything the rule
   * reads — the application name, the tier, the sha, the status — is on the deployment row.
   */
  public List<Pin> pins() {
    List<Row> rows = new ArrayList<>();
    for (CdDeployment deployment : deployments.listAllNewestFirst()) {
      rows.add(
          new Row(
              CdApplicationKeys.of(deployment.environmentId, deployment.applicationName),
              deployment.applicationName,
              deployment.commitSha,
              deployment.status));
    }
    return of(rows);
  }

  /**
   * The rule itself, over rows ordered newest-first. The order is load-bearing: "the previous
   * distinct sha" is read off it, and rows in another order name the wrong rollback target.
   */
  static List<Pin> of(List<Row> newestFirst) {
    Map<String, List<Row>> byApplication = new LinkedHashMap<>();
    for (Row row : newestFirst) {
      byApplication.computeIfAbsent(row.applicationId(), id -> new ArrayList<>()).add(row);
    }
    Map<String, Set<String>> serving = new TreeMap<>();
    Map<String, Set<String>> rollback = new TreeMap<>();
    for (List<Row> rows : byApplication.values()) {
      read(rows, serving, rollback);
    }
    List<Pin> pins = new ArrayList<>();
    for (Map.Entry<String, Set<String>> application : serving.entrySet()) {
      Set<String> shas = new LinkedHashSet<>(application.getValue());
      shas.addAll(rollback.getOrDefault(application.getKey(), Set.of()));
      pins.add(new Pin(application.getKey(), List.copyOf(shas)));
    }
    return List.copyOf(pins);
  }

  /** One application's rows, newest-first: its serving sha and the newest served sha under it. */
  private static void read(
      List<Row> rows, Map<String, Set<String>> serving, Map<String, Set<String>> rollback) {
    int at = -1;
    for (int i = 0; i < rows.size() && at < 0; i++) {
      if (rows.get(i).status() == CdDeploymentStatus.ACTIVE) {
        at = i;
      }
    }
    if (at < 0) {
      // Nothing is serving this application, so nothing is pinned and nothing is a rollback target.
      return;
    }
    Row active = rows.get(at);
    serving.computeIfAbsent(active.applicationName(), name -> new TreeSet<>()).add(active.commitSha());
    for (Row older : rows.subList(at + 1, rows.size())) {
      if (SERVED.contains(older.status()) && !older.commitSha().equals(active.commitSha())) {
        rollback
            .computeIfAbsent(active.applicationName(), name -> new TreeSet<>())
            .add(older.commitSha());
        return;
      }
    }
  }
}
