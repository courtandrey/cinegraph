package com.github.courtandrey.cinegraph.api.service;

import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.GraphNode;
import com.github.courtandrey.cinegraph.api.dto.PathResult;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.PathQueryRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Exact shortest path (fewest hops) between two films via bidirectional BFS over the full
 * edge graph. A precomputed component_id gives an O(1) reachability gate; the search then
 * always expands the smaller frontier and meets in the middle. A node/time budget bounds
 * the rare far-apart hub case — on breach it reports {@code budget}, never a wrong path.
 */
@Service
public class PathService {

    private static final int MAX_HOPS = 12;
    private static final int MAX_SETTLED = 800_000;
    private static final long BUDGET_MS = 20_000;

    private final PathQueryRepository pathRepo;
    private final MovieQueryRepository movieRepo;
    private final EdgeQueryRepository edgeRepo;
    private final TopReasonResolver topReason;

    public PathService(PathQueryRepository pathRepo, MovieQueryRepository movieRepo,
                       EdgeQueryRepository edgeRepo, TopReasonResolver topReason) {
        this.pathRepo = pathRepo;
        this.movieRepo = movieRepo;
        this.edgeRepo = edgeRepo;
        this.topReason = topReason;
    }

    public PathResult shortestPath(long from, long to) {
        Map<Long, Long> comp = pathRepo.components(from, to);
        if (!comp.containsKey(from) || !comp.containsKey(to)) {
            return fail("not_found");
        }
        if (from == to) {
            return buildPath(List.of(from));
        }
        Long cf = comp.get(from);
        Long ct = comp.get(to);

        if (!Objects.equals(cf, ct)) {
            return fail("not_connected");
        }

        Search s = search(from, to);
        if (s.path != null) return buildPath(s.path);
        return fail(s.budget ? "budget" : "unreachable");
    }

    private static final class Search {
        List<Long> path;
        boolean budget;
    }

    private Search search(long from, long to) {
        Search out = new Search();
        Map<Long, Integer> distF = new HashMap<>();
        Map<Long, Integer> distB = new HashMap<>();
        Map<Long, Long> parF = new HashMap<>();
        Map<Long, Long> parB = new HashMap<>();
        distF.put(from, 0);
        distB.put(to, 0);
        List<Long> frontF = new ArrayList<>(List.of(from));
        List<Long> frontB = new ArrayList<>(List.of(to));
        int dF = 0;
        int dB = 0;
        int best = Integer.MAX_VALUE;
        long meet = -1;
        long deadline = System.currentTimeMillis() + BUDGET_MS;

        while (!frontF.isEmpty() && !frontB.isEmpty() && dF + dB < best) {
            if (System.currentTimeMillis() > deadline
                    || distF.size() + distB.size() > MAX_SETTLED
                    || dF + dB >= MAX_HOPS) {
                out.budget = true;
                return out;
            }

            boolean expandF = frontF.size() <= frontB.size();
            List<Long> front = expandF ? frontF : frontB;
            Map<Long, Integer> dist = expandF ? distF : distB;
            Map<Long, Integer> other = expandF ? distB : distF;
            Map<Long, Long> par = expandF ? parF : parB;
            int depth = (expandF ? dF : dB) + 1;

            List<Long> next = new ArrayList<>();
            for (long[] np : pathRepo.expand(front.toArray(Long[]::new))) {
                long node = np[0];
                if (dist.containsKey(node)) continue;
                dist.put(node, depth);
                par.put(node, np[1]);
                next.add(node);
                Integer od = other.get(node);
                if (od != null && depth + od < best) {
                    best = depth + od;
                    meet = node;
                }
            }
            if (expandF) { frontF = next; dF = depth; } else { frontB = next; dB = depth; }
        }

        if (best < Integer.MAX_VALUE) out.path = reconstruct(meet, parF, parB);
        return out;
    }

    private List<Long> reconstruct(long meet, Map<Long, Long> parF, Map<Long, Long> parB) {
        LinkedList<Long> path = new LinkedList<>();
        for (Long cur = meet; cur != null; cur = parF.get(cur)) path.addFirst(cur);
        for (Long cur = parB.get(meet); cur != null; cur = parB.get(cur)) path.addLast(cur);
        return path;
    }

    private PathResult buildPath(List<Long> path) {
        Map<Long, GraphNode> byId = movieRepo.findNodesByIds(path).stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));
        List<GraphNode> nodes = path.stream().map(byId::get).filter(Objects::nonNull).toList();

        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i + 1 < path.size(); i++) {
            long s = path.get(i);
            long t = path.get(i + 1);
            edgeRepo.findEdge(s, t).ifPresent(e -> edges.add(new GraphEdge(
                    s, t, e.totalScore(), topReason.resolveCrewPerson(e.components()), e.components())));
        }
        return new PathResult(true, null, Math.max(0, path.size() - 1), nodes, edges);
    }

    private PathResult fail(String reason) {
        return new PathResult(false, reason, 0, List.of(), List.of());
    }
}
