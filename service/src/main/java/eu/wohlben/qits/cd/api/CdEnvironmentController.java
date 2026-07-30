package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.control.EnvironmentService;
import eu.wohlben.qits.cd.dto.CdEnvironmentDto;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import eu.wohlben.qits.cd.mapper.CdMapper;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
 * The environment surface: creation and teardown (machine calls from the epic orchestration, on
 * qits-net where callers are trusted; the front door session-guards the paths) and the reads. The
 * environment is the aggregate here — applications live inside it, deployments are read via
 * {@code CdDeploymentController} with the environment as a required filter.
 *
 * <p>Deliberately <b>not</b> {@code @Operation(hidden = true)}: unlike the intake, this is the API
 * of this service — the thing a client (human or the epic orchestration) is written against — so it
 * belongs in the document one is generated from.
 */
@Path("/environments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CdEnvironmentController {

  @Inject EnvironmentService environmentService;
  @Inject CdMapper mapper;

  /** One tracked application. {@code healthPath} null means the shipped default. */
  public record ApplicationSpec(@NotBlank String repoId, @NotBlank String name, String healthPath) {}

  /**
   * The creation payload. {@code branch} and {@code network} are conventions when omitted: {@code
   * epic/<name>} and {@code qits-env-<name>}.
   */
  public record CreateEnvironmentRequest(
      @NotBlank String name,
      String branch,
      String network,
      @NotNull List<@Valid ApplicationSpec> applications) {}

  public record EnvironmentResponse(CdEnvironmentDto environment) {}

  public record ListEnvironmentsResponse(List<CdEnvironmentDto> environments) {}

  @POST
  @Operation(summary = "Create an environment: a name, a branch to listen to, a docker network")
  @APIResponse(responseCode = "201", description = "Created; green builds on the branch now deploy")
  @APIResponse(responseCode = "400", description = "A name, branch or path failed validation")
  @APIResponse(responseCode = "409", description = "An environment of that name already exists")
  public Response create(@Valid CreateEnvironmentRequest request) {
    CdEnvironment environment =
        environmentService.create(
            request.name(),
            request.branch(),
            request.network(),
            request.applications().stream()
                .map(a -> new EnvironmentService.ApplicationSpec(a.repoId(), a.name(), a.healthPath()))
                .toList());
    return Response.status(Response.Status.CREATED).entity(toResponse(environment)).build();
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
