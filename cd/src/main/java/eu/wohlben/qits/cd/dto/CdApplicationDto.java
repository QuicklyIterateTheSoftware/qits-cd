package eu.wohlben.qits.cd.dto;

import java.time.Instant;

/** One tracked application. {@code healthPath} is null when the shipped default applies. */
public record CdApplicationDto(
    String id, String repoId, String name, String healthPath, Instant createdAt) {}
