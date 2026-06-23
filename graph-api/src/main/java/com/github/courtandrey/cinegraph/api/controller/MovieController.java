package com.github.courtandrey.cinegraph.api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.api.config.GraphScoringProperties;
import com.github.courtandrey.cinegraph.api.dto.*;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository.NeighborEdge;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.api.service.GraphScoring;
import com.github.courtandrey.cinegraph.api.service.PathService;
import com.github.courtandrey.cinegraph.api.service.TopReasonResolver;
import io.vavr.control.Try;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/movies")
public class MovieController {
    private static final int   MAX_LIMIT           = 100;
    private static final int   DEFAULT_LIMIT       = 40;

    private final MovieQueryRepository movieRepo;
    private final EdgeQueryRepository  edgeRepo;
    private final TopReasonResolver    topReason;
    private final GraphScoring         graphScoring;
    private final GraphScoringProperties scoringProps;
    private final PathService          pathService;
    private final ObjectMapper         mapper;

    public MovieController(MovieQueryRepository movieRepo, EdgeQueryRepository edgeRepo,
                           TopReasonResolver topReason, GraphScoring graphScoring,
                           GraphScoringProperties scoringProps, PathService pathService,
                           ObjectMapper mapper) {
        this.movieRepo = movieRepo;
        this.edgeRepo  = edgeRepo;
        this.topReason = topReason;
        this.graphScoring = graphScoring;
        this.scoringProps = scoringProps;
        this.pathService = pathService;
        this.mapper    = mapper;
    }

    public record ReweightRequest(Integer limit, Map<String, Double> weights, Float minScore) {}

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String q,
                                    @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.isBlank() || q.length() < 2) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(movieRepo.search(q.trim(), Math.min(limit, 50)));
    }

    @GetMapping("/{from}/path/{to}")
    public ResponseEntity<?> path(@PathVariable long from, @PathVariable long to) {
        return ResponseEntity.ok(pathService.shortestPath(from, to));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> movieDetail(@PathVariable long id) {
        return movieRepo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound("movie", id));
    }

    @GetMapping("/{id}/graph")
    public ResponseEntity<?> graph(
            @PathVariable long id,
            @RequestParam(defaultValue = "12.0") float minScore,
            @RequestParam(defaultValue = "40")   int limit) {

        var center = movieRepo.findById(id);
        if (center.isEmpty()) return notFound("movie", id);

        int cap = Math.clamp(limit, 1, MAX_LIMIT);

        List<NeighborEdge> neighborEdges = edgeRepo.findNeighborEdges(id, minScore, cap);
        List<Long> neighborIds = neighborEdges.stream().map(NeighborEdge::neighborId).toList();
        List<GraphNode> nodes = movieRepo.findNodesByIds(neighborIds);
        List<NeighborEdge> interEdges = edgeRepo.findInterNeighborEdges(neighborIds, minScore);

        List<GraphEdge> edges = new ArrayList<>(neighborEdges.size() + interEdges.size());
        neighborEdges.forEach(e -> edges.add(toGraphEdge(e)));
        interEdges.forEach(e -> edges.add(toGraphEdge(e)));

        return ResponseEntity.ok(new GraphPayload(center.get(), nodes, edges));
    }

    /**
     * Recomputes the whole graph from custom weights. Unlike the stored-score path,
     * this scans all edges touching the centre (no persist-style threshold), re-scores
     * each from its components, keeps those clearing the crew guard, and returns the
     * top {@code limit} by the new score. Weights are merged over the role-table defaults,
     * so an obsolete or partial map from the client still yields a complete graph.
     */
    @PostMapping("/{id}/reweight")
    public ResponseEntity<?> reweight(@PathVariable long id, @RequestBody ReweightRequest req) {
        var center = movieRepo.findById(id);
        if (center.isEmpty()) return notFound("movie", id);

        Map<String, Double> weights = graphScoring.effectiveWeights(req.weights());
        int limit = Math.clamp(req.limit() == null ? DEFAULT_LIMIT : req.limit(), 1, MAX_LIMIT);
        float minScore = req.minScore() == null ? 0f : req.minScore();

        List<GraphEdge> centerEdges = edgeRepo.findNeighborEdges(id, 0f, scoringProps.getMaxEdgeCandidates())
                .stream()
                .map(e -> reweightEdge(e, weights))
                .filter(e -> e.score() >= minScore)
                .sorted(Comparator.comparingDouble(GraphEdge::score).reversed())
                .limit(limit)
                .toList();

        List<Long> neighborIds = centerEdges.stream()
                .map(e -> e.source() == id ? e.target() : e.source())
                .toList();
        List<GraphNode> nodes = movieRepo.findNodesByIds(neighborIds);

        List<GraphEdge> edges = new ArrayList<>(centerEdges);
        edgeRepo.findInterNeighborEdges(neighborIds, 0f).stream()
                .map(e -> reweightEdge(e, weights))
                .filter(e -> e.score() >= minScore)
                .forEach(edges::add);

        return ResponseEntity.ok(new GraphPayload(center.get(), nodes, edges));
    }

    private GraphEdge reweightEdge(NeighborEdge e, Map<String, Double> weights) {
        JsonNode components = parseComponents(e.componentsJson());
        GraphScoring.Scored scored = graphScoring.rescore(components, weights);
        return new GraphEdge(e.movieA(), e.movieB(), scored.total(),
                graphScoring.topReason(scored.components()), scored.components());
    }

    private GraphEdge toGraphEdge(NeighborEdge e) {
        JsonNode components = parseComponents(e.componentsJson());
        return new GraphEdge(e.movieA(), e.movieB(), e.score(),
                topReason.resolve(components), components);
    }

    private JsonNode parseComponents(String json) {
        return Try.of(() -> mapper.readTree(json)).getOrElse(mapper::createArrayNode);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private ResponseEntity<?> notFound(String entity, long id) {
        return ResponseEntity.status(404).body(
                Map.of("status", 404, "error", entity + " " + id + " not found"));
    }
}
