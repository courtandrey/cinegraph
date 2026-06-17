package com.github.courtandrey.cinegraph.api.letterboxd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.api.config.GraphScoringProperties;
import com.github.courtandrey.cinegraph.api.config.LetterboxdProperties;
import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.GraphNode;
import com.github.courtandrey.cinegraph.api.dto.GraphPayload;
import com.github.courtandrey.cinegraph.api.dto.LetterboxdGraph;
import com.github.courtandrey.cinegraph.api.dto.LetterboxdUploadResponse;
import com.github.courtandrey.cinegraph.api.letterboxd.LetterboxdCsv.FilmRow;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository.NeighborEdge;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdSetRepository;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdSetRepository.MovieRating;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository.TitleYearMatch;
import com.github.courtandrey.cinegraph.api.service.GraphScoring;
import com.github.courtandrey.cinegraph.api.service.TopReasonResolver;
import io.vavr.control.Try;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class LetterboxdGraphService {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 40;

    private final LetterboxdClient client;
    private final MovieQueryRepository movieRepo;
    private final EdgeQueryRepository edgeRepo;
    private final LetterboxdSetRepository setRepo;
    private final TopReasonResolver topReason;
    private final GraphScoring graphScoring;
    private final GraphScoringProperties scoringProps;
    private final ObjectMapper mapper;
    private final LetterboxdProperties props;

    public LetterboxdGraphService(LetterboxdClient client, MovieQueryRepository movieRepo,
                                  EdgeQueryRepository edgeRepo, LetterboxdSetRepository setRepo,
                                  TopReasonResolver topReason, GraphScoring graphScoring,
                                  GraphScoringProperties scoringProps, ObjectMapper mapper,
                                  LetterboxdProperties props) {
        this.client = client;
        this.movieRepo = movieRepo;
        this.edgeRepo = edgeRepo;
        this.setRepo = setRepo;
        this.topReason = topReason;
        this.graphScoring = graphScoring;
        this.scoringProps = scoringProps;
        this.mapper = mapper;
        this.props = props;
    }

    public LetterboxdUploadResponse buildGraphs(String hash, String csv) {
        List<Long> movieIds = setRepo.exists(hash)
                ? setRepo.loadMovieIds(hash)
                : resolveAndStore(hash, csv);
        return new LetterboxdUploadResponse(hash, assembleGraphs(movieIds));
    }

    public List<LetterboxdGraph> overview(String hash) {
        return assembleGraphs(setRepo.loadMovieIds(hash));
    }

    public Optional<GraphPayload> recenter(String hash, long movieId, float minScore, int limit) {
        return movieRepo.findById(movieId).map(center -> {
            List<Long> subset = setRepo.loadMovieIds(hash);
            int cap = Math.clamp(limit, 1, MAX_LIMIT);

            List<NeighborEdge> neighborEdges = edgeRepo.findNeighborEdgesAmong(movieId, subset, minScore, cap);
            List<Long> neighborIds = neighborEdges.stream().map(NeighborEdge::neighborId).toList();
            List<GraphNode> nodes = movieRepo.findNodesByIds(neighborIds);

            List<GraphEdge> edges = new ArrayList<>();
            neighborEdges.forEach(e -> edges.add(toGraphEdge(e)));
            edgeRepo.findInterNeighborEdges(neighborIds, minScore).forEach(e -> edges.add(toGraphEdge(e)));

            return new GraphPayload(center, nodes, edges);
        });
    }

    public Optional<GraphPayload> reweight(String hash, long movieId, Integer limitReq,
                                           Map<String, Double> weightsReq, float minScore) {
        return movieRepo.findById(movieId).map(center -> {
            List<Long> subset = setRepo.loadMovieIds(hash);
            Map<String, Double> weights = graphScoring.effectiveWeights(weightsReq);
            int limit = Math.clamp(limitReq == null ? DEFAULT_LIMIT : limitReq, 1, MAX_LIMIT);

            List<GraphEdge> centerEdges = edgeRepo
                    .findNeighborEdgesAmong(movieId, subset, 0f, scoringProps.getMaxEdgeCandidates())
                    .stream()
                    .map(e -> reweightEdge(e, weights))
                    .filter(e -> e.score() >= minScore)
                    .sorted(Comparator.comparingDouble(GraphEdge::score).reversed())
                    .limit(limit)
                    .toList();

            List<Long> neighborIds = centerEdges.stream()
                    .map(e -> e.source() == movieId ? e.target() : e.source())
                    .toList();
            List<GraphNode> nodes = movieRepo.findNodesByIds(neighborIds);

            List<GraphEdge> edges = new ArrayList<>(centerEdges);
            edgeRepo.findInterNeighborEdges(neighborIds, 0f).stream()
                    .map(e -> reweightEdge(e, weights))
                    .filter(e -> e.score() >= minScore)
                    .forEach(edges::add);

            return new GraphPayload(center, nodes, edges);
        });
    }

    // ── resolution ───────────────────────────────────────────────────────────

    private List<Long> resolveAndStore(String hash, String csv) {
        List<MovieRating> resolved = resolveRows(LetterboxdCsv.parse(csv));
        setRepo.save(hash, resolved);
        return resolved.stream().map(MovieRating::movieId).toList();
    }

    private List<MovieRating> resolveRows(List<FilmRow> rows) {
        Map<TitleYear, Set<Long>> index = indexCandidates(rows);
        Map<Long, Double> ratingById = new LinkedHashMap<>();
        for (FilmRow row : rows) {
            uniqueFromDb(index, row)
                    .or(() -> resolveViaUri(row))
                    .ifPresent(id -> ratingById.putIfAbsent(id, row.rating()));
        }
        return ratingById.entrySet().stream()
                .map(e -> new MovieRating(e.getKey(), e.getValue()))
                .toList();
    }

    private Map<TitleYear, Set<Long>> indexCandidates(List<FilmRow> rows) {
        Set<String> names = rows.stream()
                .map(r -> norm(r.name()))
                .filter(Predicate.not(String::isBlank))
                .collect(Collectors.toSet());
        Set<Integer> years = rows.stream()
                .map(FilmRow::year)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<TitleYear, Set<Long>> index = new HashMap<>();
        for (TitleYearMatch m : movieRepo.findByTitlesAndYears(names, years)) {
            index.computeIfAbsent(new TitleYear(norm(m.title()), m.year()), k -> new HashSet<>())
                    .add(m.movieId());
            if (m.originalTitle() != null) {
                index.computeIfAbsent(new TitleYear(norm(m.originalTitle()), m.year()), k -> new HashSet<>())
                        .add(m.movieId());
            }
        }
        return index;
    }

    private Optional<Long> uniqueFromDb(Map<TitleYear, Set<Long>> index, FilmRow row) {
        if (row.year() == null) return Optional.empty();
        Set<Long> ids = index.getOrDefault(new TitleYear(norm(row.name()), row.year()), Set.of());
        return ids.size() == 1 ? Optional.of(ids.iterator().next()) : Optional.empty();
    }

    private Optional<Long> resolveViaUri(FilmRow row) {
        return Optional.ofNullable(row.uri())
                .filter(Predicate.not(String::isBlank))
                .flatMap(client::filmPage)
                .flatMap(LetterboxdParsing::parseTmdbId);
    }

    private List<LetterboxdGraph> assembleGraphs(List<Long> movieIds) {
        List<GraphEdge> edges = edgeRepo.findEdgesAmong(movieIds).stream()
                .map(this::toGraphEdge)
                .toList();

        List<Graphs.Component> components = Graphs.components(edges).stream()
                .map(c -> Graphs.capNodes(c, props.getMaxGraphNodes()))
                .filter(c -> c.nodeIds().size() >= props.getMinGraphNodes())
                .toList();

        Map<Long, GraphNode> nodeById = nodesById(components);

        return components.stream()
                .map(c -> toGraph(c, nodeById))
                .sorted(Comparator.comparingInt((LetterboxdGraph g) -> g.nodes().size()).reversed())
                .toList();
    }

    private LetterboxdGraph toGraph(Graphs.Component c, Map<Long, GraphNode> nodeById) {
        List<GraphNode> nodes = c.nodeIds().stream()
                .map(nodeById::get)
                .filter(Objects::nonNull)
                .toList();
        return new LetterboxdGraph(Graphs.centerId(c.edges()), nodes, c.edges());
    }

    private Map<Long, GraphNode> nodesById(List<Graphs.Component> components) {
        List<Long> ids = components.stream()
                .flatMap(c -> c.nodeIds().stream())
                .distinct()
                .toList();
        return movieRepo.findNodesByIds(ids).stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));
    }

    private GraphEdge toGraphEdge(NeighborEdge e) {
        JsonNode components = parseComponents(e.componentsJson());
        return new GraphEdge(e.movieA(), e.movieB(), e.score(),
                topReason.resolve(components), components);
    }

    private GraphEdge reweightEdge(NeighborEdge e, Map<String, Double> weights) {
        GraphScoring.Scored scored = graphScoring.rescore(parseComponents(e.componentsJson()), weights);
        return new GraphEdge(e.movieA(), e.movieB(), scored.total(),
                graphScoring.topReason(scored.components()), scored.components());
    }

    private JsonNode parseComponents(String json) {
        return Try.of(() -> mapper.readTree(json)).getOrElse(mapper::createArrayNode);
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private record TitleYear(String name, int year) {}
}
