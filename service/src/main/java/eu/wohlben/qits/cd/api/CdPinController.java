package eu.wohlben.qits.cd.api;

import eu.wohlben.qits.cd.control.RollbackPins;
import eu.wohlben.qits.cd.dto.CdPinDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The image shas that must survive a garbage collection, across every environment:
 * {@code {"pins":[{"applicationName","shas"}]}}.
 *
 * <p><b>Who asks.</b> qits-artifacts' OCI image GC reads this when it plans a sweep — a tag no pin
 * names is eligible, and an unreachable qits-cd aborts the plan with nothing deleted. It is the same
 * fail-closed shape qits-ci's {@code GET /ci/api/daemon} carries for the daemon binaries.
 *
 * <p><b>Why cd answers it rather than the collector computing it.</b> The keep-set is "which shas
 * would a restart or a rollback pull", and cd is the only service that knows. The rule is
 * {@link RollbackPins}, in this repo's control layer beside {@link
 * eu.wohlben.qits.cd.control.DeployService} — so the GC's keep-set and the rollback target are one
 * definition rather than two that drift, and drift here deletes an image a container is about to
 * pull.
 *
 * <p><b>Not a deployment listing.</b> {@code GET /cd/api/deployments} is history, scoped to one
 * environment and reporting every attempt. This answers the smaller question a collector asks:
 * across the whole instance, what is serving and what would come back.
 *
 * <p>In the OpenAPI document rather than hidden, on this repo's criterion. The one operation kept
 * out is {@code POST /cd/api/events/build-succeeded} — machine-only, guarded, and its wire contract
 * belongs to qits-ci. This one is none of those: the contract lives here, its consumer is another
 * first-party service reading it fail-closed, so a change to the shape belongs in a reviewable diff.
 *
 * <p>Read-only and unguarded, exactly like the environment and deployment listings. There is no
 * secret in it — every sha is already on a deployment row this service serves anonymously — and cd
 * authenticates no user anyway (the gateway does).
 *
 * <p>An ordinary JAX-RS resource under {@code quarkus.rest.path}, so it adds no literal route and
 * {@code quarkus.quinoa.ignored-path-prefixes} is unchanged — {@code /api} already covers it.
 */
@Path("/pins")
@Produces(MediaType.APPLICATION_JSON)
public class CdPinController {

  @Inject RollbackPins pins;

  public record ListPinsResponse(List<CdPinDto> pins) {}

  @GET
  @Operation(summary = "The image shas deployments pin: what serves, and what a rollback restores")
  @APIResponse(
      responseCode = "200",
      description =
          "One entry per application name serving somewhere; an application with no ACTIVE"
              + " deployment pins nothing and is absent")
  public ListPinsResponse list() {
    return new ListPinsResponse(
        pins.pins().stream().map(pin -> new CdPinDto(pin.applicationName(), pin.shas())).toList());
  }
}
