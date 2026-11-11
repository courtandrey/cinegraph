package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import com.github.courtandrey.cinegraph.api.domain.ClusterKind;
import com.github.courtandrey.cinegraph.api.domain.FilmPlacement;
import io.vavr.control.Option;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Builds the cluster map of a film set from its crew-weighted links.
 * <ol>
 *   <li>Louvain communities; those of at least {@code minClusterSize} films are clusters, the
 *       rest are groups.</li>
 *   <li>Per connected component, cluster discs are positioned by stress layout over target
 *       gaps that grow as affinity falls along the maximum spanning tree of cross-cluster
 *       affinity (see {@link Distances}), so related clusters sit closer than unrelated ones;
 *       overlaps are then pushed apart.</li>
 *   <li>Each group tries to sit in the free space between the two clusters it is most linked
 *       to (or beside its only one). Inconclusive films (share of link weight inside their
 *       cluster below {@code floaterShare}, or a runner-up cluster pulling at least
 *       {@value #TIE_RATIO} of the home cluster's weight) try the same as single-film bridges. Whatever finds
 *       no free spot is merged into its strongest cluster and laid out on that cluster's rim,
 *       facing where its outside links lead.</li>
 *   <li>Components without links to each other are packed around the largest one.</li>
 * </ol>
 * Output is deterministic for equal input. Disc and film coordinates share one model space.
 */
public final class ClusterMaps {

    public record Settings(double resolution, int minClusterSize, double floaterShare) {}

    public record Pair(long a, long b, double weight) {}

    public record PlacedFilm(long movieId, long clusterId, long componentId, FilmPlacement placement,
                             double cohesion, int linksInside, int linksTotal, List<Share> shares,
                             Vec position) {}

    public record Share(long clusterId, double share, int links) {}

    public record Disc(long id, ClusterKind kind, long componentId, List<Long> members,
                       Vec center, double radius) {}

    public record Link(long source, long target, double weight, double affinity, int edges) {}

    public record ClusterMap(List<PlacedFilm> films, List<Disc> discs, List<Link> links) {}

    static final double GAP_BASE = 240;
    static final double GAP_UNIT = 480;
    static final double MIN_DISC_GAP = 120;
    static final double MARGIN = 16;
    static final double BAND_GAP = 400;
    static final double PREFERENCE_THRESHOLD = 0.15;
    static final double TIE_RATIO = 0.8;

    private record Obstacle(Vec center, double radius) {}

    private record DiscDraft(int key, ClusterKind kind, List<Integer> core, Vec center, double radius) {}

    private record ComponentLayout(List<DiscDraft> discs, Map<Integer, Vec> positions,
                                   Map<Integer, Integer> clusterOf, Set<Integer> bridges) {}

    private record Context(WeightedGraph graph, int[] community, Settings settings) {
        ClusterKind kindOf(int key, Map<Integer, List<Integer>> communities) {
            return communities.get(key).size() >= settings.minClusterSize() ? ClusterKind.CLUSTER : ClusterKind.GROUP;
        }
    }

    private ClusterMaps() {}

    public static ClusterMap build(List<Pair> pairs, Map<Long, Double> inScore, Settings settings) {
        List<Long> ids = pairs.stream().flatMap(p -> Stream.of(p.a(), p.b())).distinct().sorted().toList();
        if (ids.isEmpty()) return new ClusterMap(List.of(), List.of(), List.of());
        Map<Long, Integer> index = IntStream.range(0, ids.size()).boxed()
                .collect(Collectors.toMap(ids::get, i -> i));
        WeightedGraph graph = WeightedGraph.of(ids.size(), pairs.stream()
                .map(p -> new WeightedGraph.Link(index.get(p.a()), index.get(p.b()), p.weight()))
                .toList());
        Context ctx = new Context(graph, Communities.detect(graph, settings.resolution()), settings);

        Map<Integer, List<Integer>> nodesByComponent = groupBy(graph.components(), graph.size());
        List<ComponentLayout> layouts = nodesByComponent.values().stream()
                .sorted(Comparator.<List<Integer>>comparingInt(c -> -c.size()).thenComparingInt(List::getFirst))
                .map(nodes -> layoutComponent(ctx, nodes))
                .toList();
        List<ComponentLayout> packed = pack(layouts);
        return toMap(ctx, ids, packed, inScore);
    }

    private static ComponentLayout layoutComponent(Context ctx, List<Integer> nodes) {
        Map<Integer, List<Integer>> communities = new TreeMap<>();
        nodes.forEach(n -> communities.computeIfAbsent(ctx.community()[n], k -> new ArrayList<>()).add(n));
        List<Integer> clusters = communities.keySet().stream()
                .filter(k -> ctx.kindOf(k, communities) == ClusterKind.CLUSTER)
                .toList();
        List<Integer> anchors = clusters.isEmpty() ? List.copyOf(communities.keySet()) : clusters;
        List<Integer> floating = communities.keySet().stream()
                .filter(k -> !anchors.contains(k))
                .sorted(Comparator.<Integer>comparingInt(k -> -communities.get(k).size()).thenComparing(k -> k))
                .toList();

        Map<Integer, Integer> host = hosts(ctx, communities, anchors, floating);
        Map<Integer, Integer> assignment = new HashMap<>();
        nodes.forEach(n -> assignment.put(n, host.getOrDefault(ctx.community()[n], ctx.community()[n])));

        Map<Integer, ClusterKind> kinds = new HashMap<>();
        communities.keySet().forEach(k -> kinds.put(k, ctx.kindOf(k, communities)));
        Map<Integer, Double> tentativeRadius = new HashMap<>();
        anchors.forEach(a -> tentativeRadius.put(a, RingLayout.discRadius(kinds.get(a), countAssigned(assignment, a))));
        Map<Integer, Vec> centers = positionAnchors(ctx.graph(), anchors, assignment, tentativeRadius);

        List<Obstacle> obstacles = new ArrayList<>();
        anchors.forEach(a -> obstacles.add(new Obstacle(centers.get(a), tentativeRadius.get(a))));
        Vec centroid = anchors.stream().map(centers::get).reduce(Vec.ZERO, Vec::plus).times(1.0 / anchors.size());

        List<Integer> keptGroups = new ArrayList<>();
        for (int group : floating) {
            List<Integer> members = communities.get(group);
            double radius = RingLayout.discRadius(ClusterKind.GROUP, members.size());
            Map<Integer, Double> pull = externalWeights(ctx.graph(), members, assignment::get, anchors);
            Option<Vec> spot = spotFor(pull, centers, tentativeRadius, radius, obstacles, centroid, group, true);
            if (spot.isDefined()) {
                keptGroups.add(group);
                centers.put(group, spot.get());
                obstacles.add(new Obstacle(spot.get(), radius));
                members.forEach(m -> assignment.put(m, group));
            }
        }

        Map<Integer, Vec> bridgePositions = placeBridges(ctx, communities, clusters, assignment, centers,
                tentativeRadius, obstacles, centroid);

        List<DiscDraft> discs = Stream.concat(anchors.stream(), keptGroups.stream())
                .map(key -> finalDisc(ctx, key, kinds.get(key), centers.get(key), assignment, bridgePositions))
                .filter(d -> !d.core().isEmpty())
                .toList();
        Map<Integer, Vec> positions = new HashMap<>(bridgePositions);
        discs.forEach(d -> positions.putAll(arrangeDisc(ctx, d, assignment, bridgePositions, centers)));
        return new ComponentLayout(discs, positions, assignment, bridgePositions.keySet());
    }

    private static Map<Integer, Integer> hosts(Context ctx, Map<Integer, List<Integer>> communities,
                                               List<Integer> anchors, List<Integer> floating) {
        Map<Integer, Integer> host = new TreeMap<>();
        boolean progress = true;
        while (progress && host.size() < floating.size()) {
            progress = false;
            for (int group : floating) {
                if (host.containsKey(group)) continue;
                Map<Integer, Double> pull = externalWeights(ctx.graph(), communities.get(group),
                        n -> ctx.community()[n], null);
                Option<Integer> best = strongest(pull, k -> anchors.contains(k) || host.containsKey(k));
                if (best.isDefined()) {
                    int target = best.get();
                    host.put(group, anchors.contains(target) ? target : host.get(target));
                    progress = true;
                }
            }
        }
        floating.forEach(g -> host.putIfAbsent(g, anchors.getFirst()));
        return host;
    }

    private static Map<Integer, Vec> positionAnchors(WeightedGraph g, List<Integer> anchors,
                                                     Map<Integer, Integer> assignment,
                                                     Map<Integer, Double> radiusOf) {
        int k = anchors.size();
        double[] radii = anchors.stream().mapToDouble(radiusOf::get).toArray();
        double[][] affinity = affinityMatrix(g, anchors, assignment);
        double[][] hops = Distances.treeHops(affinity);
        double[][] target = new double[k][k];
        double[][] weight = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                if (i == j) continue;
                double gap = GAP_BASE + GAP_UNIT * (hops[i][j] - 1);
                target[i][j] = radii[i] + radii[j] + gap;
                weight[i][j] = 1 / (gap * gap);
            }
        }
        List<Vec> placed = StressLayout.separate(StressLayout.embed(target, weight), radii, MIN_DISC_GAP);
        Map<Integer, Vec> centers = new HashMap<>();
        for (int i = 0; i < k; i++) centers.put(anchors.get(i), placed.get(i));
        return centers;
    }

    private static double[][] affinityMatrix(WeightedGraph g, List<Integer> keys, Map<Integer, Integer> assignment) {
        Map<Integer, Integer> slot = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) slot.put(keys.get(i), i);
        int k = keys.size();
        double[][] between = new double[k][k];
        double[] volume = new double[k];
        assignment.forEach((node, key) -> {
            Integer a = slot.get(key);
            if (a == null) return;
            volume[a] += g.degree(node);
            int[] nbr = g.neighbours(node);
            double[] w = g.weights(node);
            for (int e = 0; e < nbr.length; e++) {
                Integer b = slot.get(assignment.get(nbr[e]));
                if (b != null && !b.equals(a)) between[a][b] += w[e];
            }
        });
        double[][] affinity = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                double denom = Math.sqrt(volume[i] * volume[j]);
                affinity[i][j] = i == j || denom == 0 ? 0 : between[i][j] / denom;
            }
        }
        return affinity;
    }

    private static Map<Integer, Vec> placeBridges(Context ctx, Map<Integer, List<Integer>> communities,
                                                  List<Integer> clusters, Map<Integer, Integer> assignment,
                                                  Map<Integer, Vec> centers, Map<Integer, Double> radii,
                                                  List<Obstacle> obstacles, Vec centroid) {
        Map<Integer, Vec> bridges = new LinkedHashMap<>();
        if (clusters.size() < 2) return bridges;
        Map<Integer, Integer> removed = new HashMap<>();
        record Candidate(int node, double cohesion, Map<Integer, Double> pull) {}
        List<Candidate> candidates = clusters.stream()
                .flatMap(c -> communities.get(c).stream())
                .map(n -> new Candidate(n, cohesion(ctx.graph(), n, assignment::get),
                        clusterPull(ctx.graph(), n, assignment, clusters)))
                .filter(c -> isInconclusive(c.cohesion(), c.pull(), ctx.settings().floaterShare()))
                .sorted(Comparator.comparingDouble(Candidate::cohesion).thenComparingInt(Candidate::node))
                .toList();
        for (Candidate candidate : candidates) {
            int node = candidate.node();
            int own = assignment.get(node);
            int original = communities.get(own).size();
            if (original - removed.getOrDefault(own, 0) - 1 < Math.ceil(original / 2.0)) continue;
            Map<Integer, Double> pull = clusterPull(ctx.graph(), node, assignment, clusters);
            Option<Vec> spot = spotFor(pull, centers, radii, RingLayout.filmRadius(), obstacles, centroid, node, false);
            if (spot.isEmpty()) continue;
            int home = strongest(pull, k -> true).get();
            assignment.put(node, home);
            removed.merge(own, 1, Integer::sum);
            bridges.put(node, spot.get());
            obstacles.add(new Obstacle(spot.get(), RingLayout.filmRadius()));
        }
        return bridges;
    }

    private static Map<Integer, Double> clusterPull(WeightedGraph g, int node, Map<Integer, Integer> assignment,
                                                    List<Integer> clusters) {
        Map<Integer, Double> pull = externalWeights(g, List.of(node), assignment::get, null);
        pull.merge(assignment.get(node), inside(g, node, assignment::get), Double::sum);
        pull.keySet().retainAll(clusters);
        return pull;
    }

    private static boolean isInconclusive(double cohesion, Map<Integer, Double> pull, double floaterShare) {
        List<Double> ranked = pull.values().stream().filter(w -> w > 0)
                .sorted(Comparator.reverseOrder()).toList();
        return ranked.size() >= 2 && (cohesion < floaterShare || ranked.get(1) >= TIE_RATIO * ranked.get(0));
    }

    private static Option<Vec> spotFor(Map<Integer, Double> pull, Map<Integer, Vec> centers,
                                       Map<Integer, Double> radii, double radius, List<Obstacle> obstacles,
                                       Vec centroid, int salt, boolean allowSatellite) {
        List<Map.Entry<Integer, Double>> ranked = pull.entrySet().stream()
                .filter(e -> e.getValue() > 0 && centers.containsKey(e.getKey()) && radii.containsKey(e.getKey()))
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .toList();
        if (ranked.isEmpty()) return Option.none();
        Obstacle a = new Obstacle(centers.get(ranked.get(0).getKey()), radii.get(ranked.get(0).getKey()));
        if (ranked.size() == 1) {
            if (!allowSatellite) return Option.none();
            Vec outward = a.center().minus(centroid).unitOr(Vec.polar(1, salt * 2.3999632));
            return Placement.satellite(a.center(), a.radius(), outward, radius, MARGIN, obstacleCheck(obstacles));
        }
        Obstacle b = new Obstacle(centers.get(ranked.get(1).getKey()), radii.get(ranked.get(1).getKey()));
        double wa = ranked.get(0).getValue();
        double wb = ranked.get(1).getValue();
        return Placement.corridor(a.center(), a.radius(), b.center(), b.radius(), wb / (wa + wb),
                radius, MARGIN, obstacleCheck(obstacles));
    }

    private static Placement.FreeCheck obstacleCheck(List<Obstacle> obstacles) {
        List<Obstacle> snapshot = List.copyOf(obstacles);
        return (p, r) -> snapshot.stream().allMatch(o -> p.distance(o.center()) >= r + o.radius() + MARGIN - 1e-6);
    }

    private static DiscDraft finalDisc(Context ctx, int key, ClusterKind kind, Vec center,
                                       Map<Integer, Integer> assignment, Map<Integer, Vec> bridges) {
        List<Integer> members = assignment.entrySet().stream()
                .filter(e -> e.getValue() == key && !bridges.containsKey(e.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        Map<Integer, Double> strength = members.stream()
                .collect(Collectors.toMap(n -> n, n -> inside(ctx.graph(), n, assignment::get)));
        List<Integer> core = members.stream()
                .sorted(Comparator.comparingDouble((Integer n) -> -strength.get(n)).thenComparing(n -> n))
                .toList();
        return new DiscDraft(key, kind, core, center, RingLayout.discRadius(kind, core.size()));
    }

    private static Map<Integer, Vec> arrangeDisc(Context ctx, DiscDraft d, Map<Integer, Integer> assignment,
                                                 Map<Integer, Vec> bridges, Map<Integer, Vec> centers) {
        List<Long> order = d.core().stream().map(Integer::longValue).toList();
        Map<Long, Vec> placed = RingLayout.arrange(d.kind(), d.center(), order,
                id -> preferredAngle(ctx.graph(), id.intValue(), d, assignment, bridges, centers));
        Map<Integer, Vec> out = new HashMap<>();
        placed.forEach((id, p) -> out.put(id.intValue(), p));
        return out;
    }

    private static Option<Double> preferredAngle(WeightedGraph g, int node, DiscDraft d,
                                                 Map<Integer, Integer> assignment,
                                                 Map<Integer, Vec> bridges, Map<Integer, Vec> centers) {
        Vec pull = Vec.ZERO;
        double total = 0;
        int[] nbr = g.neighbours(node);
        double[] w = g.weights(node);
        for (int e = 0; e < nbr.length; e++) {
            total += w[e];
            int other = nbr[e];
            Vec toward = bridges.containsKey(other) ? bridges.get(other) : centers.get(assignment.get(other));
            if (toward == null || (assignment.get(other) == d.key() && !bridges.containsKey(other))) continue;
            pull = pull.plus(toward.minus(d.center()).unitOr(Vec.ZERO).times(w[e]));
        }
        return total > 0 && pull.length() >= PREFERENCE_THRESHOLD * total
                ? Option.some(pull.angle())
                : Option.none();
    }

    private static List<ComponentLayout> pack(List<ComponentLayout> layouts) {
        List<ComponentLayout> out = new ArrayList<>();
        List<Obstacle> placed = new ArrayList<>();
        for (ComponentLayout layout : layouts) {
            Obstacle bounds = bounds(layout);
            Vec target = placed.isEmpty() ? Vec.ZERO : packSpot(placed, bounds.radius());
            Vec shift = target.minus(bounds.center());
            out.add(translate(layout, shift));
            placed.add(new Obstacle(target, bounds.radius()));
        }
        return out;
    }

    private static Vec packSpot(List<Obstacle> placed, double radius) {
        Obstacle main = placed.getFirst();
        double ring = main.radius() + BAND_GAP + radius;
        while (true) {
            for (int step = 0; step < 72; step++) {
                Vec p = main.center().plus(Vec.polar(ring, -Math.PI / 2 + step * Math.PI / 36));
                if (placed.stream().allMatch(o -> p.distance(o.center()) >= o.radius() + radius + MIN_DISC_GAP)) return p;
            }
            ring += 2 * radius + MIN_DISC_GAP;
        }
    }

    private static Obstacle bounds(ComponentLayout layout) {
        List<Obstacle> parts = new ArrayList<>();
        layout.discs().forEach(d -> parts.add(new Obstacle(d.center(), d.radius())));
        layout.bridges().forEach(b -> parts.add(new Obstacle(layout.positions().get(b), RingLayout.filmRadius())));
        double minX = parts.stream().mapToDouble(o -> o.center().x() - o.radius()).min().orElse(0);
        double maxX = parts.stream().mapToDouble(o -> o.center().x() + o.radius()).max().orElse(0);
        double minY = parts.stream().mapToDouble(o -> o.center().y() - o.radius()).min().orElse(0);
        double maxY = parts.stream().mapToDouble(o -> o.center().y() + o.radius()).max().orElse(0);
        Vec center = new Vec((minX + maxX) / 2, (minY + maxY) / 2);
        double radius = parts.stream().mapToDouble(o -> o.center().distance(center) + o.radius()).max().orElse(0);
        return new Obstacle(center, radius);
    }

    private static ComponentLayout translate(ComponentLayout layout, Vec shift) {
        List<DiscDraft> discs = layout.discs().stream()
                .map(d -> new DiscDraft(d.key(), d.kind(), d.core(), d.center().plus(shift), d.radius()))
                .toList();
        Map<Integer, Vec> positions = new HashMap<>();
        layout.positions().forEach((n, p) -> positions.put(n, p.plus(shift)));
        return new ComponentLayout(discs, positions, layout.clusterOf(), layout.bridges());
    }

    private static ClusterMap toMap(Context ctx, List<Long> ids, List<ComponentLayout> layouts,
                                    Map<Long, Double> inScore) {
        WeightedGraph g = ctx.graph();
        Map<Integer, Integer> clusterOf = new HashMap<>();
        Set<Integer> bridges = new HashSet<>();
        Map<Integer, Vec> positions = new HashMap<>();
        Map<Integer, Long> componentOf = new HashMap<>();
        Map<Integer, Long> discId = new HashMap<>();
        List<DiscDraft> drafts = new ArrayList<>();
        for (ComponentLayout layout : layouts) {
            clusterOf.putAll(layout.clusterOf());
            bridges.addAll(layout.bridges());
            positions.putAll(layout.positions());
            long componentId = layout.clusterOf().keySet().stream()
                    .map(ids::get)
                    .max(Comparator.<Long>comparingDouble(id -> inScore.getOrDefault(id, 0.0))
                            .thenComparing(Comparator.<Long>reverseOrder()))
                    .orElseThrow();
            layout.clusterOf().keySet().forEach(n -> componentOf.put(n, componentId));
            layout.discs().forEach(d -> {
                discId.put(d.key(), ids.get(d.core().getFirst()));
                drafts.add(d);
            });
        }

        List<PlacedFilm> films = IntStream.range(0, ids.size())
                .filter(clusterOf::containsKey)
                .mapToObj(n -> {
                    long own = discId.get(clusterOf.get(n));
                    List<Share> shares = shares(g, n, m -> discId.get(clusterOf.get(m)));
                    int inside = shares.stream().filter(s -> s.clusterId() == own).mapToInt(Share::links).sum();
                    return new PlacedFilm(ids.get(n), own, componentOf.get(n),
                            bridges.contains(n) ? FilmPlacement.BRIDGE : FilmPlacement.CORE,
                            cohesion(g, n, clusterOf::get), inside, g.neighbours(n).length, shares,
                            positions.get(n));
                })
                .toList();

        List<Disc> discs = drafts.stream()
                .map(d -> new Disc(discId.get(d.key()), d.kind(), componentOf.get(d.core().getFirst()),
                        Stream.concat(d.core().stream(),
                                        bridges.stream().filter(b -> clusterOf.get(b) == d.key()).sorted())
                                .map(ids::get).toList(),
                        d.center(), d.radius()))
                .toList();
        return new ClusterMap(films, discs, links(g, clusterOf, discId));
    }

    private static List<Share> shares(WeightedGraph g, int node, IntFunction<Long> discOf) {
        Map<Long, double[]> byDisc = new TreeMap<>();
        int[] nbr = g.neighbours(node);
        double[] w = g.weights(node);
        for (int e = 0; e < nbr.length; e++) {
            double[] acc = byDisc.computeIfAbsent(discOf.apply(nbr[e]), k -> new double[2]);
            acc[0] += w[e];
            acc[1] += 1;
        }
        double total = g.degree(node);
        return byDisc.entrySet().stream()
                .map(e -> new Share(e.getKey(), e.getValue()[0] / total, (int) e.getValue()[1]))
                .sorted(Comparator.comparingDouble(Share::share).reversed().thenComparingLong(Share::clusterId))
                .toList();
    }

    private static List<Link> links(WeightedGraph g, Map<Integer, Integer> clusterOf, Map<Integer, Long> discId) {
        Map<Long, Double> volume = new HashMap<>();
        Map<List<Long>, double[]> between = new TreeMap<>(Comparator.<List<Long>>comparingLong(l -> l.get(0))
                .thenComparingLong(l -> l.get(1)));
        clusterOf.forEach((n, key) -> {
            long a = discId.get(key);
            volume.merge(a, g.degree(n), Double::sum);
            int[] nbr = g.neighbours(n);
            double[] w = g.weights(n);
            for (int e = 0; e < nbr.length; e++) {
                long b = discId.get(clusterOf.get(nbr[e]));
                if (a < b) {
                    double[] acc = between.computeIfAbsent(List.of(a, b), k -> new double[2]);
                    acc[0] += w[e];
                    acc[1] += 1;
                }
            }
        });
        return between.entrySet().stream()
                .map(e -> {
                    long a = e.getKey().get(0);
                    long b = e.getKey().get(1);
                    double weight = e.getValue()[0];
                    return new Link(a, b, weight, weight / Math.sqrt(volume.get(a) * volume.get(b)),
                            (int) e.getValue()[1]);
                })
                .toList();
    }

    private static Map<Integer, Double> externalWeights(WeightedGraph g, List<Integer> members,
                                                        IntUnaryOperator keyOf, List<Integer> restrictTo) {
        Set<Integer> own = new HashSet<>(members);
        Map<Integer, Double> out = new TreeMap<>();
        for (int n : members) {
            int[] nbr = g.neighbours(n);
            double[] w = g.weights(n);
            for (int e = 0; e < nbr.length; e++) {
                if (own.contains(nbr[e])) continue;
                int key = keyOf.applyAsInt(nbr[e]);
                if (members.size() > 1 || key != keyOf.applyAsInt(n)) out.merge(key, w[e], Double::sum);
            }
        }
        if (restrictTo != null) out.keySet().retainAll(restrictTo);
        return out;
    }

    private static double inside(WeightedGraph g, int node, IntUnaryOperator keyOf) {
        int own = keyOf.applyAsInt(node);
        int[] nbr = g.neighbours(node);
        double[] w = g.weights(node);
        double sum = 0;
        for (int e = 0; e < nbr.length; e++) if (keyOf.applyAsInt(nbr[e]) == own) sum += w[e];
        return sum;
    }

    private static double cohesion(WeightedGraph g, int node, IntUnaryOperator keyOf) {
        double total = g.degree(node);
        return total == 0 ? 0 : inside(g, node, keyOf) / total;
    }

    private static Option<Integer> strongest(Map<Integer, Double> weights, IntPredicate admits) {
        return Option.ofOptional(weights.entrySet().stream()
                .filter(e -> e.getValue() > 0 && admits.test(e.getKey()))
                .max(Map.Entry.<Integer, Double>comparingByValue().thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey));
    }

    private static int countAssigned(Map<Integer, Integer> assignment, int key) {
        return (int) assignment.values().stream().filter(v -> v == key).count();
    }

    private static Map<Integer, List<Integer>> groupBy(int[] labels, int size) {
        Map<Integer, List<Integer>> out = new TreeMap<>();
        for (int i = 0; i < size; i++) out.computeIfAbsent(labels[i], k -> new ArrayList<>()).add(i);
        return out;
    }
}
