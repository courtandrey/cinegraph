package com.github.courtandrey.cinegraph.exporter.domain;

public enum RunKind {
    FULL, INCREMENTAL, RETRY, REPROJECT, EDGE_FULL, EDGE_INCREMENTAL;

    public boolean isLoad() {
        return this == FULL || this == INCREMENTAL;
    }
}
