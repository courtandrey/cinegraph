package dev.cinegraph.api.domain;

import java.util.Map;
import java.util.Optional;

public enum ComponentType {

    SHARED_PERSON(null),
    SHARED_GENRES(2.0),
    SHARED_KEYWORDS(0.5),
    RELEASE_PROXIMITY(4.0);

    private final Double defaultWeight;

    ComponentType(Double defaultWeight) {
        this.defaultWeight = defaultWeight;
    }

    public boolean isCorrelation() {
        return defaultWeight != null;
    }

    public double defaultWeight() {
        return defaultWeight;
    }

    public static Optional<ComponentType> of(String code) {
        return Optional.ofNullable(BY_NAME.get(code));
    }

    public static Map<String, Double> correlationDefaults() {
        return Map.of(
                SHARED_GENRES.name(), SHARED_GENRES.defaultWeight,
                SHARED_KEYWORDS.name(), SHARED_KEYWORDS.defaultWeight,
                RELEASE_PROXIMITY.name(), RELEASE_PROXIMITY.defaultWeight);
    }

    private static final Map<String, ComponentType> BY_NAME = Map.of(
            SHARED_PERSON.name(), SHARED_PERSON,
            SHARED_GENRES.name(), SHARED_GENRES,
            SHARED_KEYWORDS.name(), SHARED_KEYWORDS,
            RELEASE_PROXIMITY.name(), RELEASE_PROXIMITY);
}
