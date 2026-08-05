package eu.wohlben.qits.cd.dto;

import java.util.List;

/**
 * One application's rollback-relevant image shas: the sha it serves, and the sha a rollback would
 * put back. Addressed by application <b>name</b> because that is the image name every pull is built
 * from — the union over every environment running an application of that name.
 *
 * <p>{@code shas} is a set to keep, not a sequence: it is ordered deterministically (serving shas
 * first, each group sorted) so the answer is stable across calls, and a reader must not read
 * position as recency.
 */
public record CdPinDto(String applicationName, List<String> shas) {}
