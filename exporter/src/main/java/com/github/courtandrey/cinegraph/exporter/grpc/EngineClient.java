package com.github.courtandrey.cinegraph.exporter.grpc;

import com.github.courtandrey.cinegraph.proto.PathServiceGrpc;
import com.github.courtandrey.cinegraph.proto.ReloadReply;
import com.github.courtandrey.cinegraph.proto.ReloadRequest;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EngineClient {

    private static final Logger log = LoggerFactory.getLogger(EngineClient.class);

    @GrpcClient("engine")
    private PathServiceGrpc.PathServiceStub engine;

    public void triggerReload() {
        log.info("[engine] requesting graph reload");
        engine.reload(ReloadRequest.getDefaultInstance(), new StreamObserver<>() {
            @Override
            public void onNext(ReloadReply reply) {
                log.info("[engine] reload accepted: started={} state={}", reply.getStarted(), reply.getState());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("[engine] reload trigger failed: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
            }
        });
    }
}
