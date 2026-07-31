package eu.wohlben.qits.cd.dto;

import eu.wohlben.qits.cd.entity.CdDeploymentStatus;
import java.time.Instant;

/**
 * One recorded deployment attempt; {@code applicationName} denormalized for legible listings.
 *
 * <p>{@code runId} is the qits-ci run that caused this deployment, and it is <b>null for all
 * history</b> — the column arrived in V2 and nothing could derive it for the rows already there. A
 * client renders the commit as a link to {@code /ci/runs/<runId>} when it is set and as plain text
 * when it is not; there is no other way to reach the build from here.
 */
public record CdDeploymentDto(
    String id,
    String applicationId,
    String applicationName,
    String commitSha,
    String runId,
    CdDeploymentStatus status,
    String containerName,
    String detail,
    Instant createdAt,
    Instant finishedAt) {}
