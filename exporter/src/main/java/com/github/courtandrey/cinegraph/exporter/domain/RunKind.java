package com.github.courtandrey.cinegraph.exporter.domain;

import java.util.Arrays;

public enum RunKind {
    FULL, INCREMENTAL, RETRY, REPROJECT, EDGE_FULL, EDGE_INCREMENTAL;

    public boolean isLoad() {
        return this == FULL || this == INCREMENTAL;
    }

    public boolean isIngest() {
        return this == FULL || this == INCREMENTAL || this == RETRY || this == REPROJECT;
    }

    public boolean isEdgeBuild() {
        return this == EDGE_FULL || this == EDGE_INCREMENTAL;
    }

    public static RunKind[] ingests() {
        return Arrays.stream(values()).filter(RunKind::isIngest).toArray(RunKind[]::new);
    }

    public static RunKind[] edgeBuilds() {
        return Arrays.stream(values()).filter(RunKind::isEdgeBuild).toArray(RunKind[]::new);
    }
}
