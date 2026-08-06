package eu.wohlben.qits.cd.dto;

import eu.wohlben.qits.cd.entity.CdDeploymentTarget;
import java.time.Instant;

/**
 * One deployable application. {@code healthPath} is null when the shipped default applies.
 *
 * <p>{@code environmentId} and {@code environmentName} are null exactly when {@code target} is
 * {@code SINGLETON} — a platform-plane application belongs to no tier — and {@code branch} is the
 * mirror image: only a singleton carries its own, because an environment application takes its
 * environment's.
 */
public record CdApplicationDto(
    String id,
    String repoId,
    String name,
    String environmentId,
    String environmentName,
    CdDeploymentTarget target,
    boolean availableOnEnv,
    String branch,
    String healthPath,
    Instant createdAt) {}
