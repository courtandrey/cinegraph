package com.github.courtandrey.cinegraph.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.courtandrey.cinegraph.api.config.GraphScoringProperties;
import com.github.courtandrey.cinegraph.api.domain.ComponentType;
import com.github.courtandrey.cinegraph.api.repo.RoleQueryRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Re-scores stored edges with custom weights, mirroring the exporter's model:
 * per shared person {@code sameRole ? w*1.5 : min(wA, wB)} capped per-person; the
 * genre/keyword/release correlations scale linearly from their stored score relative
 * to the default multiplier. An edge survives only while its crew contribution clears
 * the min-crew-score guard.
 */
@Service
public class GraphScoring {

    public record Scored(float total, float crew, ArrayNode components, boolean survives) {}

    private final ObjectMapper mapper;
    private final TopReasonResolver topReason;
    private final RoleQueryRepository roleRepo;
    private final GraphScoringProperties props;

    public GraphScoring(ObjectMapper mapper, TopReasonResolver topReason,
                        RoleQueryRepository roleRepo, GraphScoringProperties props) {
        this.mapper = mapper;
        this.topReason = topReason;
        this.roleRepo = roleRepo;
        this.props = props;
    }

    public Map<String, Double> effectiveWeights(Map<String, Double> overrides) {
        Map<String, Double> weights = new HashMap<>(roleRepo.defaultWeights());
        weights.putAll(ComponentType.correlationDefaults());
        if (overrides != null) {
            overrides.forEach((code, w) -> { if (w != null) weights.put(code, w); });
        }
        return weights;
    }

    public Scored rescore(JsonNode components, Map<String, Double> weights) {
        ArrayNode out = mapper.createArrayNode();
        double crew = 0;
        double others = 0;

        if (components != null && components.isArray()) {
            for (JsonNode c : components) {
                ComponentType type = ComponentType.of(c.path("type").asText("")).orElse(null);
                double score = rescoreComponent(type, c, weights);
                if (type == ComponentType.SHARED_PERSON) crew += score;
                else others += score;

                ObjectNode copy = c.deepCopy();
                if (type != null) copy.put("score", score);
                out.add(copy);
            }
        }

        float total = (float) (crew + others);
        return new Scored(total, (float) crew, out, crew >= props.getMinCrewScore());
    }

    public String topReason(ArrayNode components) {
        return topReason.resolve(components);
    }

    private double rescoreComponent(ComponentType type, JsonNode c, Map<String, Double> weights) {
        if (type == ComponentType.SHARED_PERSON) {
            return personContribution(c, weights);
        }
        if (type != null && type.isCorrelation()) {
            double weight = weights.getOrDefault(type.name(), type.defaultWeight());
            return c.path("score").asDouble(0) * (weight / type.defaultWeight());
        }
        return c.path("score").asDouble(0);
    }

    private double personContribution(JsonNode c, Map<String, Double> weights) {
        double wA = weights.getOrDefault(c.path("roleA").asText(""), 0.5);
        double wB = weights.getOrDefault(c.path("roleB").asText(""), 0.5);
        double raw = c.path("sameRole").asBoolean(false) ? wA * 1.5 : Math.min(wA, wB);
        return Math.min(props.getPerPersonCap(), raw);
    }
}
