package com.github.courtandrey.cinegraph.api.letterboxd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.api.config.GraphScoringProperties;
import com.github.courtandrey.cinegraph.api.config.LetterboxdProperties;
import com.github.courtandrey.cinegraph.api.dto.*;
import com.github.courtandrey.cinegraph.api.letterboxd.LetterboxdCsv.FilmRow;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository.NeighborEdge;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdSetRepository;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdSetRepository.MovieRating;
import com.github.courtandrey.cinegraph.api.repo.LetterboxdSetRepository.SetFilm;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository.TitleYearMatch;
import com.github.courtandrey.cinegraph.api.service.EngineRecommender;
import com.github.courtandrey.cinegraph.api.service.GraphScoring;
import com.github.courtandrey.cinegraph.api.service.PathService;
import com.github.courtandrey.cinegraph.api.service.TopReasonResolver;
import io.vavr.control.Try;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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
    private final PathService pathService;
    private final EngineRecommender engineRecommender;

    public LetterboxdGraphService(LetterboxdClient client, MovieQueryRepository movieRepo,
                                  EdgeQueryRepository edgeRepo, LetterboxdSetRepository setRepo,
                                  TopReasonResolver topReason, GraphScoring graphScoring,
                                  GraphScoringProperties scoringProps, ObjectMapper mapper,
                                  LetterboxdProperties props, PathService pathService,
                                  EngineRecommender engineRecommender) {
        this.client = client;
        this.movieRepo = movieRepo;
        this.edgeRepo = edgeRepo;
        this.setRepo = setRepo;
        this.topReason = topReason;
        this.graphScoring = graphScoring;
        this.scoringProps = scoringProps;
        this.mapper = mapper;
        this.props = props;
        this.pathService = pathService;
        this.engineRecommender = engineRecommender;
    }

    public PathResult pathWithinSet(String hash, long from, long to) {
        return pathService.shortestPathWithin(from, to, setRepo.loadMovieIds(hash));
    }

    public LetterboxdUploadResponse buildGraphs(String hash, String csv) {
        List<Long> movieIds = setRepo.exists(hash)
                ? setRepo.loadMovieIds(hash)
                : resolveAndStore(hash, csv);
        Assembled a = assemble(movieIds);
        bakeGraphIds(hash, a);
        return new LetterboxdUploadResponse(hash, a.graphs());
    }

    public List<LetterboxdGraph> overview(String hash) {
        Assembled a = assemble(setRepo.loadMovieIds(hash));
        bakeGraphIds(hash, a);
        return a.graphs();
    }

    public List<LetterboxdSearchResult> search(String hash, String q, int limit) {
        return movieRepo.searchInSet(q, limit, hash);
    }

    public List<GraphNode> recommendations(String hash, Long graphId, boolean invert, int limitReq) {
        int limit = Math.clamp(limitReq, 1, MAX_LIMIT);
        List<SetFilm> films = setRepo.loadSetFilms(hash);
        if (films.isEmpty()) return List.of();

        List<EdgeQueryRepository.Recommendation> recs =
                engineRecommendations(films, graphId, invert, limit)
                        .orElseGet(() -> edgeRepo.topRecommendations(hash, graphId, invert, limit));

        Map<Long, GraphNode> nodeById = movieRepo
                .findNodesByIds(recs.stream().map(EdgeQueryRepository.Recommendation::movieId).toList())
                .stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));
        return recs.stream()
                .map(r -> Optional.ofNullable(nodeById.get(r.movieId()))
                        .map(n -> n.withInScore(r.score())))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<List<EdgeQueryRepository.Recommendation>> engineRecommendations(
            List<SetFilm> films, Long graphId, boolean invert, int limit) {
        List<EngineRecommender.Seed> seeds = films.stream()
                .filter(f -> graphId == null || Objects.equals(f.graphId(), graphId))
                .map(f -> new EngineRecommender.Seed(f.movieId(),
                        ratingCoef(f.rating() == null ? null : f.rating().doubleValue())))
                .toList();
        List<Long> exclude = films.stream().map(SetFilm::movieId).toList();
        return Try.of(() -> engineRecommender.recommend(seeds, exclude, limit, invert))
                .onFailure(e -> log.warn("[recs] engine unavailable, using SQL fallback: {}",
                        e.getMessage()))
                .getOrElse(Optional.empty());
    }

    public Optional<RecommendationBreakdown> recommendationBreakdown(String hash, long movieId) {
        if (setRepo.contains(hash, movieId)) return Optional.empty();
        List<EdgeQueryRepository.SetEdge> edges = edgeRepo.recommendationContributions(hash, movieId);
        if (edges.isEmpty()) return Optional.empty();

        List<RecommendationBreakdown.Contribution> contributions = edges.stream()
                .map(e -> {
                    double coef = ratingCoef(e.rating());
                    return new RecommendationBreakdown.Contribution(
                            e.setMovieId(), e.inScore(), e.rating(), coef, e.inScore() * coef);
                })
                .sorted(Comparator.comparingDouble(
                        RecommendationBreakdown.Contribution::contribution).reversed())
                .toList();
        double total = contributions.stream()
                .mapToDouble(RecommendationBreakdown.Contribution::contribution).sum();
        return Optional.of(new RecommendationBreakdown(movieId, total, contributions));
    }

    /** Mirrors the SQL {@code ratingCoef} in EdgeQueryRepository — keep the two in sync. */
    private static double ratingCoef(Double rating) {
        return rating == null ? 1.0 : 1.0 + (rating - 2.5) * 4.0;
    }

    private double recommendationTotal(String hash, long movieId) {
        return edgeRepo.recommendationContributions(hash, movieId).stream()
                .mapToDouble(e -> e.inScore() * ratingCoef(e.rating()))
                .sum();
    }

    public Optional<LetterboxdAttachment> attachNode(
            String hash, long movieId, java.util.Collection<Long> visibleIds) {
        List<Long> subset = setRepo.loadMovieIds(hash);
        if (subset.isEmpty()) return Optional.empty();

        List<NeighborEdge> incident =
                edgeRepo.findNeighborEdgesAmong(movieId, subset, 0f, scoringProps.getMaxEdgeCandidates());
        return movieRepo.findNodesByIds(List.of(movieId)).stream().findFirst().map(base -> {
            double inScore = incident.stream().mapToDouble(NeighborEdge::score).sum();
            Set<Long> visible = new HashSet<>(visibleIds);
            List<GraphEdge> edges = incident.stream()
                    .filter(e -> visible.contains(e.neighborId()))
                    .map(this::toGraphEdge)
                    .toList();
            return new LetterboxdAttachment(
                    base.withInScore(inScore), edges);
        });
    }

    private void bakeGraphIds(String hash, Assembled a) {
        setRepo.updateGraphIds(hash, a.graphIdByMovie());
    }

    public Optional<GraphPayload> recenter(String hash, long movieId, float minScore, int limit) {
        return movieRepo.findById(movieId).map(center -> {
            int cap = Math.clamp(limit, 1, MAX_LIMIT);

            boolean recommendation = !setRepo.contains(hash, movieId);
            boolean leastFirst = recommendation && recommendationTotal(hash, movieId) < 0;
            List<NeighborEdge> neighborEdges = recommendation
                    ? edgeRepo.findNeighborEdgesByContribution(movieId, hash, leastFirst, cap)
                    : edgeRepo.findNeighborEdgesAmong(movieId, setRepo.loadMovieIds(hash), minScore, cap);
            float interMinScore = recommendation ? 0f : minScore;

            List<Long> neighborIds = neighborEdges.stream().map(NeighborEdge::neighborId).toList();
            List<GraphNode> nodes = movieRepo.findNodesByIds(neighborIds);

            List<GraphEdge> edges = new ArrayList<>();
            neighborEdges.forEach(e -> edges.add(toGraphEdge(e)));
            edgeRepo.findInterNeighborEdges(neighborIds, interMinScore).forEach(e -> edges.add(toGraphEdge(e)));

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
                .flatMap(LetterboxdParsing::parseTmdbId)
                .or(() -> {
                    log.error("Could not parse tmdb id from {}", row.uri());
                    return Optional.empty();
                });
    }

    private Assembled assemble(List<Long> movieIds) {
        List<GraphEdge> edges = edgeRepo.findEdgesAmong(movieIds).stream()
                .map(this::toGraphEdge)
                .toList();

        List<Built> built = new ArrayList<>();
        Map<Long, Long> graphIdByMovie = new HashMap<>();
        for (Graphs.Component comp : Graphs.components(edges)) {
            Map<Long, Double> inScore = Graphs.edgeSums(comp.edges());
            Graphs.Component withNodes = Graphs.capNodes(comp, props.getMaxGraphNodes());
            Graphs.Component view = Graphs.capEdgesPerNode(
                    withNodes, props.getMinEdgesPerNode(), props.getMinScoreForNonEssential());
            if (view.nodeIds().size() >= props.getMinGraphNodes()) {
                long centerId = inScore.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(0L);
                built.add(new Built(view, inScore, centerId));
                comp.nodeIds().forEach(m -> graphIdByMovie.put(m, centerId));
            }
        }
        movieIds.forEach(m -> graphIdByMovie.putIfAbsent(m, 0L));

        List<Long> ids = built.stream().flatMap(b -> b.view().nodeIds().stream()).distinct().toList();
        Map<Long, GraphNode> nodeById = movieRepo.findNodesByIds(ids).stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));

        List<LetterboxdGraph> graphs = built.stream()
                .map(b -> toGraph(b, nodeById))
                .sorted(Comparator.comparingInt((LetterboxdGraph g) -> g.nodes().size()).reversed())
                .toList();
        return new Assembled(graphs, graphIdByMovie);
    }

    private record Assembled(List<LetterboxdGraph> graphs, Map<Long, Long> graphIdByMovie) {}

    private record Built(Graphs.Component view, Map<Long, Double> inScore, long centerId) {}

    private LetterboxdGraph toGraph(Built b, Map<Long, GraphNode> nodeById) {
        List<GraphNode> nodes = b.view().nodeIds().stream()
                .map(nodeById::get)
                .filter(Objects::nonNull)
                .map(n -> n.withInScore(b.inScore().getOrDefault(n.id(), 0.0)))
                .toList();
        return new LetterboxdGraph(b.centerId(), nodes, b.view().edges());
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
