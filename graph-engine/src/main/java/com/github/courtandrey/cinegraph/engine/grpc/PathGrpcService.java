package com.github.courtandrey.cinegraph.engine.grpc;

import com.github.courtandrey.cinegraph.engine.graph.GraphHolder;
import com.github.courtandrey.cinegraph.engine.graph.ImmutableGraph;
import com.github.courtandrey.cinegraph.proto.PathReply;
import com.github.courtandrey.cinegraph.proto.PathRequest;
import com.github.courtandrey.cinegraph.proto.PathServiceGrpc;
import com.github.courtandrey.cinegraph.proto.PathStatus;
import com.github.courtandrey.cinegraph.proto.ReloadReply;
import com.github.courtandrey.cinegraph.proto.ReloadRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;

@GrpcService
public class PathGrpcService extends PathServiceGrpc.PathServiceImplBase {

    private final GraphHolder holder;
    private final int maxHops;

    public PathGrpcService(GraphHolder holder, @Value("${engine.max-hops:20}") int maxHops) {
        this.holder = holder;
        this.maxHops = maxHops;
    }

    @Override
    public void shortestPath(PathRequest request, StreamObserver<PathReply> responseObserver) {
        responseObserver.onNext(computePath(request.getFrom(), request.getTo()));
        responseObserver.onCompleted();
    }

    private PathReply computePath(long from, long to) {
        ImmutableGraph g = holder.graph();
        if (g == null || !holder.isReady()) {
            return PathReply.newBuilder().setStatus(PathStatus.LOADING).build();
        }
        if (from == to) {
            return PathReply.newBuilder().setStatus(PathStatus.FOUND).setHops(0).addMovieIds(from).build();
        }
        int fromIdx = g.indexOf(from);
        int toIdx = g.indexOf(to);
        if (fromIdx < 0 || toIdx < 0 || !g.sameComponent(fromIdx, toIdx)) {
            return PathReply.newBuilder().setStatus(PathStatus.NOT_CONNECTED).build();
        }
        long[] path = g.shortestPath(fromIdx, toIdx, maxHops);
        if (path == null) {
            return PathReply.newBuilder().setStatus(PathStatus.UNREACHABLE).build();
        }
        PathReply.Builder reply = PathReply.newBuilder().setStatus(PathStatus.FOUND).setHops(path.length - 1);
        for (long id : path) {
            reply.addMovieIds(id);
        }
        return reply.build();
    }

    @Override
    public void reload(ReloadRequest request, StreamObserver<ReloadReply> responseObserver) {
        boolean started = holder.triggerReload();
        responseObserver.onNext(ReloadReply.newBuilder()
                .setStarted(started)
                .setState(holder.status().name())
                .build());
        responseObserver.onCompleted();
    }
}
