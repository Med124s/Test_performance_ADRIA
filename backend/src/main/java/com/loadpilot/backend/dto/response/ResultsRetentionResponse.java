package com.loadpilot.backend.dto.response;

import java.time.Instant;

/** Conservation du detail d'une execution : option "Conserver" et dates de purge. */
public record ResultsRetentionResponse(
        boolean keep,
        Instant resultsPurgedAt,
        Instant requestDetailsPurgedAt,
        boolean fromSnapshot,
        String notice
) {
}
