package com.github.courtandrey.cinegraph.api.dto;

public record ClusterLink(long source, long target, double weight, double affinity, int edges) {}
