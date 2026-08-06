package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.control.EnvironmentService;
import eu.wohlben.qits.cd.dto.CdEnvironmentDto;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import eu.wohlben.qits.cd.mapper.CdMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The environment surface: creating a tier, renaming or retargeting it, tearing it down, and the
 * reads. The environment is the aggregate here — an environment's applications are read with it,
 * deployments are read via {@code CdDeploymentController} with the environment as a required
 * filter, and the flat application registry (singletons included) is {@code
 * CdApplicationController}.
 *
 * <p>A tier is created <b>deliberately</b>; what it holds is not. Application rows are derived from
 * each repository's {@code deployments.yml} on every green build, so this surface has no write for
 * them and gained none.
 *
 * <p>Deliberately <b>not</b> {@code @Operation(hidden = true)}: unlike the intake, this is the API
 * of this service — the thing a client (human or the epic orchestration) is written against — so it
 * belongs in the document one is generated from.
 *
 * <p><b>No {@code MachineAuth} guard, deliberately.</b> These writes are reachable by a person
 * through qits-gateway's session, so a bearer is not the only credential a caller could hold, and
 * demanding one would lock the humans out the day the gate flips on. The build-succeeded intake is
 * the opposite — machine-only — and carries the guard. When the epic orchestration becomes a real
 * machine sender, giving it a token and guarding these two writes is one change, not this one.
 */
@Path("/environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CdEnvironmentController {

  @Inject EnvironmentService environmentService;
  @Inject CdMapper mapper;

  /**
   * One tracked application. {@code healthPath} null means the shipped default.
   *
   * @deprecated applications are derived from each repository's {@code deployments.yml} on every
   *     green build — see {@link CreateEnvironmentRequest#applications()}.
   */
  @Deprecated
  public record ApplicationSpec(@NotBlank String repoId, @NotBlank String name, String healthPath) {}

  /**
   * The creation payload. {@code branch} and {@code network} are conventions when omitted: {@code
   * environment/<name>} and {@code qits-env-<name>}.
   *
   * <p>{@code applications} is <b>optional and deprecated</b>. Application rows are derived from
   * each repository's own {@code .config/qits/deployments.yml} on every green build, so naming them
   * here only pre-creates what the next build would create anyway — and pre-creating them states a
   * topology the repository has not agreed to. It is still accepted so an older bootstrap keeps
   * working; send nothing.
   */
  public record CreateEnvironmentRequest(
      @NotBlank String name,
      String branch,
      String network,
      @Deprecated List<@Valid ApplicationSpec> applications) {}

  /**
   * The rename/retarget payload — both fields optional, an omitted one is left alone. This is how
   * an environment moves onto the {@code environment/<name>} branch convention.
   */
  public record UpdateEnvironmentRequest(String name, String branch) {}

  public record EnvironmentResponse(CdEnvironmentDto environment) {}

  public record ListEnvironmentsResponse(List<CdEnvironmentDto> environments) {}

  @POST
  @Operation(summary = "Create an environment: a name, a branch to listen to, a docker network")
  @APIResponse(responseCode = "201", description = "Created; green builds on the branch now deploy")
  @APIResponse(responseCode = "400", description = "A name, branch or path failed validation")
  @APIResponse(responseCode = "409", description = "An environment of that name already exists")
  public Response create(@Valid CreateEnvironmentRequest request) {
    List<ApplicationSpec> declared =
        request.applications() == null ? List.of() : request.applications();
    CdEnvironment environment =
        environmentService.create(
            request.name(),
            request.branch(),
            request.network(),
            declared.stream()
                .map(a -> new EnvironmentService.ApplicationSpec(a.repoId(), a.name(), a.healthPath()))
                .toList());
    return Response.status(Response.Status.CREATED).entity(toResponse(environment)).build();
  }

  /**
   * Rename an environment or point it at another branch. <b>No docker side effects</b> — a rename
   * that tore containers down would be a delete in disguise, and delete is the one thing never to
   * reach for on a live environment. The next deployment of each application moves it onto the
   * networks the new name derives; what runs now keeps running.
   */
  @PATCH
  @Path("/{environmentId}")
  @Operation(summary = "Rename an environment or point it at another branch")
  @APIResponse(responseCode = "200", description = "The updated environment")
  @APIResponse(responseCode = "400", description = "A name or branch failed validation")
  @APIResponse(responseCode = "404", description = "No such environment")
  @APIResponse(responseCode = "409", description = "Another environment already has that name")
  public EnvironmentResponse update(
      @PathParam("environmentId") String environmentId, UpdateEnvironmentRequest request) {
    CdEnvironment environment =
        environmentService.update(
            environmentId,
            request == null ? null : request.name(),
            request == null ? null : request.branch());
    return toResponse(environment);
  }

  /** All environments, newest-first, without their applications (fetch one for the full shape). */
  @GET
  @Operation(summary = "List environments")
  public ListEnvironmentsResponse list() {
    return new ListEnvironmentsResponse(
        environmentService.list().stream().map(mapper::toDto).toList());
  }

  @GET
  @Path("/{environmentId}")
  @Operation(summary = "One environment with the applications it tracks")
  @APIResponse(responseCode = "200", description = "The environment")
  @APIResponse(responseCode = "404", description = "No such environment")
  public EnvironmentResponse get(@PathParam("environmentId") String environmentId) {
    return toResponse(environmentService.require(environmentId));
  }

  /**
   * Tear the environment down: its recorded deployments, its containers, its network. 204 — after
   * this the branch deploys nowhere.
   */
  @DELETE
  @Path("/{environmentId}")
  @Operation(summary = "Tear an environment down (rows, containers, network)")
  @APIResponse(responseCode = "204", description = "Torn down")
  @APIResponse(responseCode = "404", description = "No such environment")
  public Response delete(@PathParam("environmentId") String environmentId) {
    environmentService.delete(environmentId);
    return Response.noContent().build();
  }

  private EnvironmentResponse toResponse(CdEnvironment environment) {
    return new EnvironmentResponse(
        mapper.toDto(environment, environmentService.applicationsOf(environment.id)));
  }
}
