package dev.cinegraph.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record EdgeBreakdown(
        MovieDetail movieA,
        MovieDetail movieB,
        float totalScore,
        float crewScore,
        JsonNode components
) {}
