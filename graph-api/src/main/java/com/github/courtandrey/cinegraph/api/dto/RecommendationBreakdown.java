package com.github.courtandrey.cinegraph.api.dto;

import java.util.List;

public record RecommendationBreakdown(long movieId, double total, List<Contribution> contributions) {

    public record Contribution(long movieId, double inScore, Double rating, double coef, double contribution) {}
}
