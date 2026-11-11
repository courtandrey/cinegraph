package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.api.domain.ClusterKind;
import com.github.courtandrey.cinegraph.api.domain.FilmPlacement;
import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.MapCluster.Contributor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterMapsTest {

    private static final ClusterMaps.Settings SETTINGS = new ClusterMaps.Settings(1.0, 8, 0.4);

    @Test
    void communities_separateTwoCliquesJoinedByWeakLink() {
        List<ClusterMaps.Pair> pairs = new ArrayList<>(clique(1, 10, 10));
        pairs.addAll(clique(11, 20, 10));
        pairs.add(new ClusterMaps.Pair(10, 11, 1));
        WeightedGraph g = graphOf(pairs);

        int[] parts = Communities.detect(g, 1.0);

        assertThat(parts[0]).isEqualTo(parts[9]);
        assertThat(parts[10]).isEqualTo(parts[19]);
        assertThat(parts[0]).isNotEqualTo(parts[10]);
        assertThat(Communities.modularity(g, parts, 1.0)).isGreaterThan(0.4);
    }

    @Test
    void communities_neverSpanDisconnectedParts() {
        List<ClusterMaps.Pair> pairs = new ArrayList<>(clique(1, 4, 5));
        pairs.addAll(clique(5, 8, 5));
        int[] parts = Communities.detect(graphOf(pairs), 0.01);
        assertThat(parts[0]).isNotEqualTo(parts[4]);
    }

    @Test
    void build_isDeterministic() {
        List<ClusterMaps.Pair> pairs = threeClusters(10, 1, 1);
        assertThat(ClusterMaps.build(pairs, inScore(pairs), SETTINGS))
                .isEqualTo(ClusterMaps.build(pairs, inScore(pairs), SETTINGS));
    }

    @Test
    void build_strongerAffinityMeansSmallerGap() {
        List<ClusterMaps.Pair> pairs = threeClusters(12, 2, 0.5);
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        Map<Long, ClusterMaps.Disc> discOf = discByMember(map);
        ClusterMaps.Disc a = discOf.get(1L), b = discOf.get(101L), c = discOf.get(201L);
        assertThat(Set.of(a.id(), b.id(), c.id())).hasSize(3);
        assertThat(gap(a, b)).isLessThan(gap(a, c));
        assertThat(gap(a, b)).isLessThan(gap(b, c));
    }

    @Test
    void build_discsNeverOverlapAndPostersNeverCollide() {
        List<ClusterMaps.Pair> pairs = threeClusters(30, 1, 1);
        pairs.add(new ClusterMaps.Pair(900, 901, 4));
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        List<ClusterMaps.Disc> discs = map.discs();
        for (int i = 0; i < discs.size(); i++) {
            for (int j = i + 1; j < discs.size(); j++) {
                assertThat(discs.get(i).center().distance(discs.get(j).center()))
                        .isGreaterThanOrEqualTo(discs.get(i).radius() + discs.get(j).radius());
            }
        }
        List<Vec> posters = map.films().stream().map(ClusterMaps.PlacedFilm::position).toList();
        for (int i = 0; i < posters.size(); i++) {
            for (int j = i + 1; j < posters.size(); j++) {
                Vec p = posters.get(i), q = posters.get(j);
                assertThat(Math.abs(p.x() - q.x()) >= 64 || Math.abs(p.y() - q.y()) >= 96)
                        .as("posters %s and %s overlap", p, q).isTrue();
            }
        }
    }

    @Test
    void build_everyFilmBelongsToExactlyOneDisc() {
        List<ClusterMaps.Pair> pairs = threeClusters(10, 1, 1);
        pairs.addAll(clique(500, 503, 6));
        pairs.add(new ClusterMaps.Pair(500, 1, 1));
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        List<Long> members = map.discs().stream().flatMap(d -> d.members().stream()).toList();
        assertThat(members).doesNotHaveDuplicates();
        assertThat(new HashSet<>(members))
                .isEqualTo(map.films().stream().map(ClusterMaps.PlacedFilm::movieId).collect(Collectors.toSet()));
        Map<Long, Long> clusterOf = map.films().stream()
                .collect(Collectors.toMap(ClusterMaps.PlacedFilm::movieId, ClusterMaps.PlacedFilm::clusterId));
        map.discs().forEach(d -> d.members().forEach(m -> assertThat(clusterOf.get(m)).isEqualTo(d.id())));
    }

    @Test
    void build_evenlySplitFilmBecomesBridgeBetweenItsClusters() {
        List<ClusterMaps.Pair> pairs = new ArrayList<>(clique(1, 12, 10));
        pairs.addAll(clique(101, 112, 10));
        LongStream.rangeClosed(1, 3).forEach(i -> pairs.add(new ClusterMaps.Pair(i, 101 + i, 3)));
        LongStream.rangeClosed(1, 4).forEach(i -> pairs.add(new ClusterMaps.Pair(999, i, 3)));
        LongStream.rangeClosed(101, 104).forEach(i -> pairs.add(new ClusterMaps.Pair(999, i, 3)));
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        ClusterMaps.PlacedFilm bridge = filmById(map).get(999L);
        assertThat(bridge.placement()).isEqualTo(FilmPlacement.BRIDGE);
        assertThat(bridge.cohesion()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        Map<Long, ClusterMaps.Disc> discOf = discByMember(map);
        ClusterMaps.Disc a = discOf.get(1L), b = discOf.get(101L);
        double along = bridge.position().distance(a.center()) + bridge.position().distance(b.center());
        assertThat(along).isLessThan(a.center().distance(b.center()) * 1.05);
        assertThat(bridge.position().distance(a.center())).isGreaterThan(a.radius());
        assertThat(bridge.position().distance(b.center())).isGreaterThan(b.radius());
    }

    @Test
    void build_cohesionIsShareOfLinkWeightInsideOwnCluster() {
        List<ClusterMaps.Pair> pairs = threeClusters(10, 1, 1);
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        Map<Long, Long> clusterOf = map.films().stream()
                .collect(Collectors.toMap(ClusterMaps.PlacedFilm::movieId, ClusterMaps.PlacedFilm::clusterId));
        for (ClusterMaps.PlacedFilm f : map.films()) {
            double inside = 0, total = 0;
            int linksInside = 0, linksTotal = 0;
            for (ClusterMaps.Pair p : pairs) {
                if (p.a() != f.movieId() && p.b() != f.movieId()) continue;
                long other = p.a() == f.movieId() ? p.b() : p.a();
                total += p.weight();
                linksTotal++;
                if (clusterOf.get(other).equals(f.clusterId())) {
                    inside += p.weight();
                    linksInside++;
                }
            }
            assertThat(f.cohesion()).isCloseTo(inside / total, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(f.linksInside()).isEqualTo(linksInside);
            assertThat(f.linksTotal()).isEqualTo(linksTotal);
            assertThat(f.shares().stream().mapToDouble(ClusterMaps.Share::share).sum())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(f.shares().stream().mapToInt(ClusterMaps.Share::links).sum()).isEqualTo(linksTotal);
            assertThat(f.shares().stream().filter(s -> s.clusterId() == f.clusterId()).findFirst())
                    .hasValueSatisfying(s -> assertThat(s.share())
                            .isCloseTo(f.cohesion(), org.assertj.core.data.Offset.offset(1e-9)));
        }
    }

    @Test
    void build_unlinkedIslandIsPackedOutsideMainland() {
        List<ClusterMaps.Pair> pairs = threeClusters(10, 1, 1);
        pairs.add(new ClusterMaps.Pair(900, 901, 4));
        ClusterMaps.ClusterMap map = ClusterMaps.build(pairs, inScore(pairs), SETTINGS);

        ClusterMaps.Disc island = discByMember(map).get(900L);
        assertThat(island.kind()).isEqualTo(ClusterKind.GROUP);
        assertThat(island.members()).containsExactlyInAnyOrder(900L, 901L);
        map.discs().stream().filter(d -> d != island).forEach(d ->
                assertThat(island.center().distance(d.center())).isGreaterThan(island.radius() + d.radius()));
        assertThat(island.componentId()).isNotEqualTo(discByMember(map).get(1L).componentId());
    }

    @Test
    void contributors_rankPeopleByScoreSummedOverInternalEdges() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String director = "{\"type\":\"SHARED_PERSON\",\"personId\":7,\"name\":\"Ari\",\"roleA\":\"DIRECTOR\",\"roleB\":\"DIRECTOR\",\"score\":15}";
        String crew = "{\"type\":\"SHARED_PERSON\",\"personId\":8,\"name\":\"Bo\",\"roleA\":\"CREW\",\"roleB\":\"EDITOR\",\"score\":3}";
        String genre = "{\"type\":\"SHARED_GENRES\",\"score\":2}";
        List<GraphEdge> edges = List.of(
                new GraphEdge(1, 2, 20f, "", mapper.readTree("[" + director + "," + crew + "," + genre + "]")),
                new GraphEdge(2, 3, 18f, "", mapper.readTree("[" + director + "]")));

        List<Contributor> top = ClusterProfiles.contributors(edges, 10);

        assertThat(top).extracting(Contributor::personId).containsExactly(7L, 8L);
        assertThat(top.get(0).score()).isEqualTo(30.0);
        assertThat(top.get(0).films()).isEqualTo(3);
        assertThat(top.get(0).role()).isEqualTo("DIRECTOR");
        assertThat(top.get(1).role()).isEqualTo("EDITOR");
    }

    private static List<ClusterMaps.Pair> threeClusters(int size, double abLink, double weakLink) {
        List<ClusterMaps.Pair> pairs = new ArrayList<>(clique(1, size, 10));
        pairs.addAll(clique(101, 100 + size, 10));
        pairs.addAll(clique(201, 200 + size, 10));
        LongStream.rangeClosed(1, 4).forEach(i -> pairs.add(new ClusterMaps.Pair(i, 100 + i, abLink)));
        pairs.add(new ClusterMaps.Pair(size, 200 + size, weakLink));
        pairs.add(new ClusterMaps.Pair(100 + size, 201, weakLink));
        return pairs;
    }

    private static List<ClusterMaps.Pair> clique(long from, long to, double weight) {
        List<ClusterMaps.Pair> out = new ArrayList<>();
        for (long a = from; a <= to; a++) {
            for (long b = a + 1; b <= to; b++) out.add(new ClusterMaps.Pair(a, b, weight));
        }
        return out;
    }

    private static WeightedGraph graphOf(List<ClusterMaps.Pair> pairs) {
        List<Long> ids = pairs.stream().flatMap(p -> java.util.stream.Stream.of(p.a(), p.b()))
                .distinct().sorted().toList();
        Map<Long, Integer> index = new HashMap<>();
        ids.forEach(id -> index.put(id, index.size()));
        return WeightedGraph.of(ids.size(), pairs.stream()
                .map(p -> new WeightedGraph.Link(index.get(p.a()), index.get(p.b()), p.weight()))
                .toList());
    }

    private static Map<Long, Double> inScore(List<ClusterMaps.Pair> pairs) {
        Map<Long, Double> out = new HashMap<>();
        pairs.forEach(p -> {
            out.merge(p.a(), p.weight(), Double::sum);
            out.merge(p.b(), p.weight(), Double::sum);
        });
        return out;
    }

    private static Map<Long, ClusterMaps.Disc> discByMember(ClusterMaps.ClusterMap map) {
        Map<Long, ClusterMaps.Disc> out = new HashMap<>();
        map.discs().forEach(d -> d.members().forEach(m -> out.put(m, d)));
        return out;
    }

    private static Map<Long, ClusterMaps.PlacedFilm> filmById(ClusterMaps.ClusterMap map) {
        return map.films().stream().collect(Collectors.toMap(ClusterMaps.PlacedFilm::movieId, Function.identity()));
    }

    private static double gap(ClusterMaps.Disc a, ClusterMaps.Disc b) {
        return a.center().distance(b.center()) - a.radius() - b.radius();
    }
}
