package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.control.EnvironmentService;
import eu.wohlben.qits.cd.dto.CdApplicationDto;
import eu.wohlben.qits.cd.mapper.CdMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * Every application cd deploys, in one flat list — the environments' and the platform's singletons
 * together.
 *
 * <p>It is flat because a singleton belongs to no environment: reading the registry through the
 * environments would leave qits-idp and qits-cd out of it, which are the two a reader most wants to
 * find. Each row says which plane it is on ({@code target}) and, for an environment application,
 * which tier ({@code environmentId}/{@code environmentName}).
 *
 * <p>Read-only, and that is the model rather than a phase: rows here are derived from each
 * repository's own {@code .config/qits/deployments.yml} on every green build.
 *
 * <p>Since the registry extraction this is a proxied read of qits-serviceregistry's services, one
 * row per environment link and one per singleton — the same flat shape, from the service that now
 * owns it. The {@code id} is derived from {@code (environmentId, name)} so a client can still join
 * it against a deployment's {@code applicationId}.
 */
@Path("/applications")
@Produces(MediaType.APPLICATION_JSON)
public class CdApplicationController {

  @Inject EnvironmentService environmentService;
  @Inject CdMapper mapper;

  public record ListApplicationsResponse(List<CdApplicationDto> applications) {}

  @GET
  @Operation(summary = "Every application cd deploys — environment applications and singletons")
  public ListApplicationsResponse list() {
    return new ListApplicationsResponse(
        environmentService.allApplications().stream().map(mapper::toDto).toList());
  }
}
