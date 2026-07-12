package com.github.courtandrey.cinegraph.api.service;

import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.GraphNode;
import com.github.courtandrey.cinegraph.api.dto.PathResult;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.proto.PathReply;
import com.github.courtandrey.cinegraph.proto.PathRequest;
import com.github.courtandrey.cinegraph.proto.PathServiceGrpc;
import com.github.courtandrey.cinegraph.proto.SubsetPathRequest;
import io.grpc.StatusRuntimeException;
import io.vavr.control.Try;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class PathService {

    private static final Logger log = LoggerFactory.getLogger(PathService.class);
    private static final long DEADLINE_MS = 15_000;

    private final MovieQueryRepository movieRepo;
    private final EdgeQueryRepository edgeRepo;
    private final TopReasonResolver topReason;

    @GrpcClient("engine")
    private PathServiceGrpc.PathServiceBlockingStub engine;

    public PathService(MovieQueryRepository movieRepo, EdgeQueryRepository edgeRepo, TopReasonResolver topReason) {
        this.movieRepo = movieRepo;
        this.edgeRepo = edgeRepo;
        this.topReason = topReason;
    }

    public PathResult shortestPath(long from, long to) {
        return callEngine(() -> engine.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .shortestPath(PathRequest.newBuilder().setFrom(from).setTo(to).build()));
    }

    public PathResult shortestPathWithin(long from, long to, List<Long> allowed) {
        return callEngine(() -> engine.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .shortestPathWithin(SubsetPathRequest.newBuilder()
                        .setFrom(from).setTo(to).addAllAllowed(allowed).build()));
    }

    private PathResult callEngine(Supplier<PathReply> call) {
        return Try.ofSupplier(call)
                .map(this::toResult)
                .recover(StatusRuntimeException.class, e -> fail(degradedReason(e)))
                .get();
    }

    private static String degradedReason(StatusRuntimeException e) {
        String reason = switch (e.getStatus().getCode()) {
            case RESOURCE_EXHAUSTED, DEADLINE_EXCEEDED -> "busy";
            case UNAVAILABLE -> "loading";
            default -> throw e;
        };
        log.warn("[path] engine call degraded to '{}': {}", reason, e.getStatus());
        return reason;
    }

    private PathResult toResult(PathReply reply) {
        return switch (reply.getStatus()) {
            case FOUND -> hydrate(reply.getMovieIdsList());
            case NOT_CONNECTED -> fail("not_connected");
            case UNREACHABLE -> fail("unreachable");
            case LOADING -> fail("loading");
            default -> fail("not_found");
        };
    }

    private PathResult hydrate(List<Long> ids) {
        Map<Long, GraphNode> byId = movieRepo.findNodesByIds(ids).stream()
                .collect(Collectors.toMap(GraphNode::id, Function.identity()));
        List<GraphNode> nodes = ids.stream().map(byId::get).filter(Objects::nonNull).toList();

        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 0; i + 1 < ids.size(); i++) {
            long s = ids.get(i);
            long t = ids.get(i + 1);
            edgeRepo.findEdge(s, t).ifPresent(e -> edges.add(new GraphEdge(
                    s, t, e.totalScore(),
                    topReason.resolveCrewPerson(e.components(), s != e.movieA()),
                    e.components())));
        }
        return new PathResult(true, null, Math.max(0, ids.size() - 1), nodes, edges);
    }

    private PathResult fail(String reason) {
        return new PathResult(false, reason, 0, List.of(), List.of());
    }
}
