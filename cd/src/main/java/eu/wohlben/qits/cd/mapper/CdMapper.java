package eu.wohlben.qits.cd.mapper;

import eu.wohlben.qits.cd.control.CdApplicationKeys;
import eu.wohlben.qits.cd.control.EnvironmentService.ApplicationView;
import eu.wohlben.qits.cd.control.RegistryClient.RegEnvironment;
import eu.wohlben.qits.cd.control.RegistryClient.RegService;
import eu.wohlben.qits.cd.dto.CdApplicationDto;
import eu.wohlben.qits.cd.dto.CdDeploymentDto;
import eu.wohlben.qits.cd.dto.CdEnvironmentDto;
import eu.wohlben.qits.cd.entity.CdDeployment;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The wire shapes, unchanged across the registry extraction — which is why this is hand-written
 * rather than a MapStruct interface now: nothing here is a field-for-field copy any more.
 *
 * <p>Environments and applications arrive as {@link eu.wohlben.qits.cd.control.RegistryClient}
 * records instead of entities, and the two ids a client joins on ({@code CdApplicationDto.id} and
 * {@code CdDeploymentDto.applicationId}) are <b>derived</b> from {@code (environmentId,
 * applicationName)} on both sides — there is no application row left to take an id from, and the
 * client's "the first row per applicationId is the current one" pass has to keep working.
 * {@link CdApplicationKeys} is the one definition of that id.
 */
@ApplicationScoped
public class CdMapper {

  /** Applications are attached explicitly by the boundary; listings leave them null. */
  public CdEnvironmentDto toDto(RegEnvironment environment) {
    return toDto(environment, null);
  }

  public CdEnvironmentDto toDto(RegEnvironment environment, List<ApplicationView> applications) {
    return new CdEnvironmentDto(
        environment.id(),
        environment.name(),
        environment.branch(),
        environment.network(),
        environment.createdAt(),
        applications == null ? null : applications.stream().map(this::toDto).toList());
  }

  /**
   * The environment is flattened rather than nested: a singleton has none, and a listing that mixes
   * both wants one shape. {@code repoId} is the service name — the registry holds one identity for
   * a service, and derived registration has always named an application after its repository.
   */
  public CdApplicationDto toDto(ApplicationView view) {
    RegService service = view.service();
    return new CdApplicationDto(
        CdApplicationKeys.of(view.environmentId(), service.name()),
        service.name(),
        service.name(),
        view.environmentId(),
        view.environmentName(),
        service.target(),
        service.availableOnEnv(),
        service.branch(),
        service.healthPath(),
        service.createdAt());
  }

  public CdDeploymentDto toDto(CdDeployment deployment) {
    return new CdDeploymentDto(
        deployment.id,
        CdApplicationKeys.of(deployment.environmentId, deployment.applicationName),
        deployment.applicationName,
        deployment.commitSha,
        deployment.runId,
        deployment.status,
        deployment.containerName,
        deployment.detail,
        deployment.createdAt,
        deployment.finishedAt);
  }
}
