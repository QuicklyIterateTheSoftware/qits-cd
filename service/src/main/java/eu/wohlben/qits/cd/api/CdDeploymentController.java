package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.control.DeployService;
import eu.wohlben.qits.cd.control.EnvironmentService;
import eu.wohlben.qits.cd.dto.CdDeploymentDto;
import eu.wohlben.qits.cd.error.BadRequestException;
import eu.wohlben.qits.cd.mapper.CdMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The deployment read surface. The deployment is the entity and the environment is a required
 * <b>filter</b> ({@code ?environmentId=}), the ci-runs shape: an unscoped listing would return
 * every deployment on the instance, and a missing environment must say so (404) rather than answer
 * with an empty list.
 */
@Path("/deployments")
@Produces(MediaType.APPLICATION_JSON)
public class CdDeploymentController {

  @Inject DeployService deployService;
  @Inject EnvironmentService environmentService;
  @Inject CdMapper mapper;

  public record ListDeploymentsResponse(List<CdDeploymentDto> deployments) {}

  @GET
  @Operation(summary = "An environment's recorded deployments, newest-first")
  @APIResponse(responseCode = "200", description = "The deployments")
  @APIResponse(responseCode = "404", description = "No such environment")
  public ListDeploymentsResponse list(@QueryParam("environmentId") String environmentId) {
    if (environmentId == null || environmentId.isBlank()) {
      throw new BadRequestException("environmentId is required");
    }
    environmentService.require(environmentId);
    return new ListDeploymentsResponse(
        deployService.deploymentsFor(environmentId).stream().map(mapper::toDto).toList());
  }
}
