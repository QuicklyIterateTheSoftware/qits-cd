package eu.wohlben.qits.cd.dto;

import java.time.Instant;
import java.util.List;

/** An environment with the applications it tracks (null on listings — fetch one for the full shape). */
public record CdEnvironmentDto(
    String id,
    String name,
    String branch,
    String network,
    Instant createdAt,
    List<CdApplicationDto> applications) {}
