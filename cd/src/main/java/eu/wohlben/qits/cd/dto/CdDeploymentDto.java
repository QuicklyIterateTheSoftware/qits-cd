package eu.wohlben.qits.cd.dto;

import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import java.time.Instant;

/** One recorded deployment attempt; {@code applicationName} denormalized for legible listings. */
public record CdDeploymentDto(
    String id,
    String applicationId,
    String applicationName,
    String commitSha,
    CdDeploymentStatus status,
    String containerName,
    String detail,
    Instant createdAt,
    Instant finishedAt) {}
