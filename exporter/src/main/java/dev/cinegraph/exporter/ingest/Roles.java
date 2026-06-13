package dev.cinegraph.exporter.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vavr.control.Try;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Roles {

    public static final String CREW_OTHER = "CREW_OTHER";
    public static final double DEFAULT_WEIGHT = 0.5;

    private final Map<String, String> crewIndex;
    private final Map<String, Double> weights;

    private Roles(Map<String, String> crewIndex, Map<String, Double> weights) {
        this.crewIndex = Map.copyOf(crewIndex);
        this.weights = Map.copyOf(weights);
    }

    public static Roles fromYaml(InputStream yaml) {
        return Try.of(() -> new ObjectMapper(new YAMLFactory()).readValue(yaml, Config.class))
                .map(Roles::fromConfig)
                .getOrElseThrow(e -> new IllegalStateException("Cannot load roles.yml", e));
    }

    public String crewRole(String department, String job) {
        if (department == null || job == null) return CREW_OTHER;
        String mapped = crewIndex.get(department + "|" + job);
        return mapped != null ? mapped : departmentCode(department);
    }

    public String castRole(int castOrder) {
        if (castOrder <= 4) return "CAST_LEAD";
        if (castOrder <= 14) return "CAST_SUPPORT";
        return "CAST_MINOR";
    }

    public double weight(String code) {
        return weights.getOrDefault(code, DEFAULT_WEIGHT);
    }

    public Map<String, Double> seedWeights() {
        return weights;
    }

    static String departmentCode(String department) {
        String code = department.toUpperCase()
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return code.isEmpty() ? CREW_OTHER : code;
    }

    private static Roles fromConfig(Config config) {
        Map<String, String> crewIndex = new HashMap<>();
        Map<String, Double> weights = new HashMap<>();
        for (Config.Definition def : config.roles()) {
            weights.put(def.code(), def.baseWeight());
            if (def.departments() == null) continue;
            def.departments().forEach((dept, jobs) ->
                    jobs.jobs().forEach(job -> crewIndex.put(dept + "|" + job, def.code())));
        }
        return new Roles(crewIndex, weights);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Config(List<Definition> roles) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Definition(String code, double baseWeight, Map<String, Jobs> departments) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Jobs(List<String> jobs) {}
    }
}
