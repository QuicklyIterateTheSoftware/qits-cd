package eu.wohlben.qits.cd.mapper;

import eu.wohlben.qits.cd.dto.CdApplicationDto;
import eu.wohlben.qits.cd.dto.CdDeploymentDto;
import eu.wohlben.qits.cd.dto.CdEnvironmentDto;
import eu.wohlben.qits.cd.entity.CdApplication;
import eu.wohlben.qits.cd.entity.CdDeployment;
import eu.wohlben.qits.cd.entity.CdEnvironment;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jakarta")
public interface CdMapper {

  // Applications are attached explicitly by the boundary (single-environment endpoint only;
  // listings keep them null) — same shape as ci's run/steps split.
  @Mapping(target = "applications", ignore = true)
  CdEnvironmentDto toDto(CdEnvironment entity);

  CdApplicationDto toDto(CdApplication entity);

  @Mapping(target = "applicationId", source = "application.id")
  @Mapping(target = "applicationName", source = "application.name")
  CdDeploymentDto toDto(CdDeployment entity);

  default CdEnvironmentDto toDto(CdEnvironment entity, List<CdApplication> applications) {
    CdEnvironmentDto bare = toDto(entity);
    return new CdEnvironmentDto(
        bare.id(),
        bare.name(),
        bare.branch(),
        bare.network(),
        bare.createdAt(),
        applications.stream().map(this::toDto).toList());
  }
}
