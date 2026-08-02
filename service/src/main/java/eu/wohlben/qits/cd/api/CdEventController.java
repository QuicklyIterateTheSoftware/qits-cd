package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.auth.MachineAuth;
import eu.wohlben.qits.cd.control.DeployService;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The CD event intake — the wire contract between qits-ci and cd. {@code POST
 * /cd/api/events/build-succeeded} is a cross-repo contract: qits-ci's {@code CdBuildNotifier}
 * POSTs exactly this path via its {@code qits.cd.intake-url}, and it is fire-and-forget — a
 * delivery failure is logged at debug and nothing else. A mismatch here therefore raises no error
 * anywhere; deployments just stop happening. The path carries no {@code cd} segment of its own
 * because {@code quarkus.rest.path=/cd/api} already says it.
 *
 * <p>Hidden from the OpenAPI document (a wire/system API).
 *
 * <p><b>The one machine-only path in this service</b>, and therefore the one that carries {@link
 * MachineAuth#require()}. Nothing human reaches it: qits-ci is its only sender. The environment
 * surface next door is the opposite case — a person drives it through the gateway's session — so it
 * stays on forward-auth and gains no guard. That split is the rule here, not a phasing: {@code
 * require()} belongs where a bearer is the only credential a caller could ever hold.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CdEventController {

  @Inject DeployService deployService;
  @Inject MachineAuth machineAuth;

  /**
   * One green pipeline for one commit. The triple that matters is (repoId, branch, commitSha) — cd
   * resolves the image from it by convention and owns everything after that. {@code runId} is
   * optional and drives nothing: it is recorded on each deployment this queues so a reader can walk
   * from a deployment row to {@code /ci/runs/<runId>}, the only edge between the two services'
   * histories. A sender that omits it still deploys; the row simply names no build.
   */
  public record BuildSucceededEvent(
      String runId, @NotBlank String repoId, @NotBlank String branch, @NotBlank String commitSha) {}

  /**
   * Accepts the event and returns immediately — deployments execute on cd's worker. 202 also when
   * no environment listens to the branch: that is the normal case for every green build on a branch
   * without an environment, not an error the fire-and-forget sender could act on.
   *
   * <p>{@code require()} and not {@code requireProject(...)}: the event names a {@code repoId}, and
   * a repository is not a project. Holding a token minted for qits-cd is the whole claim this
   * intake needs — it queues a deployment onto whichever environment already listens to the branch,
   * and which environments exist is cd's own configuration, not the caller's to name.
   *
   * <p>With the gate off this line returns at once and the endpoint accepts credential-free calls
   * from qits-net exactly as it did before.
   */
  @POST
  @Path("/build-succeeded")
  @Operation(hidden = true)
  public Response buildSucceeded(@Valid BuildSucceededEvent event) {
    machineAuth.require();
    deployService.onBuildSucceeded(
        event.runId(), event.repoId(), event.branch(), event.commitSha());
    return Response.accepted().build();
  }
}
