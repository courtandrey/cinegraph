package com.github.courtandrey.cinegraph.engine.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record GraphEdge(long source, long target, float score, String topReason, JsonNode components) {}
