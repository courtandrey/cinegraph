package com.github.courtandrey.cinegraph.api.service;

import com.github.courtandrey.cinegraph.api.repo.EdgeQueryRepository.Recommendation;
import com.github.courtandrey.cinegraph.proto.RecommendReply;
import com.github.courtandrey.cinegraph.proto.RecommendRequest;
import com.github.courtandrey.cinegraph.proto.RecommendServiceGrpc;
import com.github.courtandrey.cinegraph.proto.RecommendStatus;
import com.github.courtandrey.cinegraph.proto.SeedFilm;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


@Service
public class EngineRecommender {

    private static final long DEADLINE_MS = 5_000;

    public record Seed(long movieId, double coef) {}

    @GrpcClient("engine")
    private RecommendServiceGrpc.RecommendServiceBlockingStub engine;

    public Optional<List<Recommendation>> recommend(List<Seed> seeds, Collection<Long> exclude,
                                                    int limit, boolean invert) {
        RecommendRequest.Builder request = RecommendRequest.newBuilder()
                .addAllExclude(exclude)
                .setLimit(limit)
                .setInvert(invert);
        seeds.forEach(s -> request.addSeeds(
                SeedFilm.newBuilder().setMovieId(s.movieId()).setCoef(s.coef())));

        RecommendReply reply = engine
                .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .recommend(request.build());
        if (reply.getStatus() != RecommendStatus.RECOMMEND_OK) {
            return Optional.empty();
        }
        return Optional.of(reply.getItemsList().stream()
                .map(i -> new Recommendation(i.getMovieId(), i.getScore()))
                .toList());
    }
}
