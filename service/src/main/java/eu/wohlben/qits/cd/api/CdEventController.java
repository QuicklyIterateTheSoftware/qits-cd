package eu.wohlben.qits.cd.api;

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
 * <p>Hidden from the OpenAPI document (a wire/system API). Carries no token guard: unlike ci's
 * intake this path is NOT on the gateway's token-free allowlist — the sender dials it directly on
 * qits-net, so the front door session-guards it and the network trusts it. If it is ever
 * allowlisted at the gateway, a write guard here must land in the same change.
 */
@Path("/events")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CdEventController {

  @Inject DeployService deployService;

  /**
   * One green pipeline for one commit. {@code runId} is recorded nowhere yet but travels for
   * traceability; the triple that matters is (repoId, branch, commitSha) — cd resolves the image
   * from it by convention and owns everything after that.
   */
  public record BuildSucceededEvent(
      String runId, @NotBlank String repoId, @NotBlank String branch, @NotBlank String commitSha) {}

  /**
   * Accepts the event and returns immediately — deployments execute on cd's worker. 202 also when
   * no environment listens to the branch: that is the normal case for every green build on a branch
   * without an environment, not an error the fire-and-forget sender could act on.
   */
  @POST
  @Path("/build-succeeded")
  @Operation(hidden = true)
  public Response buildSucceeded(@Valid BuildSucceededEvent event) {
    deployService.onBuildSucceeded(event.repoId(), event.branch(), event.commitSha());
    return Response.accepted().build();
  }
}
