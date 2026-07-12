package com.github.courtandrey.cinegraph.engine.grpc;

import com.github.courtandrey.cinegraph.engine.graph.GraphHolder;
import com.github.courtandrey.cinegraph.engine.graph.ImmutableGraph;
import com.github.courtandrey.cinegraph.proto.RecommendReply;
import com.github.courtandrey.cinegraph.proto.RecommendRequest;
import com.github.courtandrey.cinegraph.proto.RecommendServiceGrpc;
import com.github.courtandrey.cinegraph.proto.RecommendStatus;
import com.github.courtandrey.cinegraph.proto.ScoredMovie;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.List;

@GrpcService
public class RecommendGrpcService extends RecommendServiceGrpc.RecommendServiceImplBase {

    private final GraphHolder holder;
    private final QueryGate gate;

    public RecommendGrpcService(GraphHolder holder, QueryGate gate) {
        this.holder = holder;
        this.gate = gate;
    }

    @Override
    public void recommend(RecommendRequest request, StreamObserver<RecommendReply> responseObserver) {
        try {
            responseObserver.onNext(gate.execute(() -> compute(request)));
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        }
    }

    private RecommendReply compute(RecommendRequest request) {
        ImmutableGraph g = holder.graph();
        if (g == null || !holder.isReady()) {
            return RecommendReply.newBuilder().setStatus(RecommendStatus.RECOMMEND_LOADING).build();
        }
        int n = request.getSeedsCount();
        long[] seedIds = new long[n];
        double[] coefs = new double[n];
        for (int i = 0; i < n; i++) {
            seedIds[i] = request.getSeeds(i).getMovieId();
            coefs[i] = request.getSeeds(i).getCoef();
        }
        boolean[] exclude = g.allowedMask(request.getExcludeList());

        List<ImmutableGraph.Scored> scored =
                g.recommend(seedIds, coefs, exclude, request.getLimit(), request.getInvert());

        RecommendReply.Builder reply = RecommendReply.newBuilder().setStatus(RecommendStatus.RECOMMEND_OK);
        for (ImmutableGraph.Scored s : scored) {
            reply.addItems(ScoredMovie.newBuilder().setMovieId(s.movieId()).setScore(s.score()));
        }
        return reply.build();
    }
}
