package com.github.courtandrey.cinegraph.api.service;

import com.github.courtandrey.cinegraph.api.dto.GraphEdge;
import com.github.courtandrey.cinegraph.api.dto.GraphNode;
import com.github.courtandrey.cinegraph.api.dto.PathResult;
import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository;
import com.github.courtandrey.cinegraph.api.repo.MovieQueryRepository;
import com.github.courtandrey.cinegraph.proto.PathReply;
import com.github.courtandrey.cinegraph.proto.PathRequest;
import com.github.courtandrey.cinegraph.proto.PathServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PathService {

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
        PathReply reply = engine.shortestPath(PathRequest.newBuilder().setFrom(from).setTo(to).build());
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
                    s, t, e.totalScore(), topReason.resolveCrewPerson(e.components()), e.components())));
        }
        return new PathResult(true, null, Math.max(0, ids.size() - 1), nodes, edges);
    }

    private PathResult fail(String reason) {
        return new PathResult(false, reason, 0, List.of(), List.of());
    }
}
