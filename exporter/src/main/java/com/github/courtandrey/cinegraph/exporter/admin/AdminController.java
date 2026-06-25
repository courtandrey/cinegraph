package com.github.courtandrey.cinegraph.exporter.admin;

import com.github.courtandrey.cinegraph.exporter.domain.RunKind;
import com.github.courtandrey.cinegraph.exporter.domain.RunStatus;
import com.github.courtandrey.cinegraph.exporter.ingest.EdgeBuildService;
import com.github.courtandrey.cinegraph.exporter.ingest.FullLoadService;
import com.github.courtandrey.cinegraph.exporter.ingest.PathIndexService;
import com.github.courtandrey.cinegraph.exporter.ingest.IncrementalLoadService;
import com.github.courtandrey.cinegraph.exporter.ingest.ReprojectService;
import com.github.courtandrey.cinegraph.exporter.repo.FetchQueueRepository;
import com.github.courtandrey.cinegraph.exporter.repo.LoadRunRepository;
import io.vavr.control.Try;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final FullLoadService fullLoadService;
    private final IncrementalLoadService incrementalLoadService;
    private final EdgeBuildService edgeBuildService;
    private final PathIndexService pathIndexService;
    private final ReprojectService reprojectService;
    private final LoadRunRepository runRepo;
    private final FetchQueueRepository queueRepo;
    private final RunRegistry runRegistry;

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private final AtomicBoolean pathIndexRunning = new AtomicBoolean(false);

    public AdminController(FullLoadService fullLoadService,
                           IncrementalLoadService incrementalLoadService,
                           EdgeBuildService edgeBuildService,
                           PathIndexService pathIndexService,
                           ReprojectService reprojectService,
                           LoadRunRepository runRepo,
                           FetchQueueRepository queueRepo,
                           RunRegistry runRegistry) {
        this.fullLoadService = fullLoadService;
        this.incrementalLoadService = incrementalLoadService;
        this.edgeBuildService = edgeBuildService;
        this.pathIndexService = pathIndexService;
        this.reprojectService = reprojectService;
        this.runRepo = runRepo;
        this.queueRepo = queueRepo;
        this.runRegistry = runRegistry;
    }

    @PostMapping("/full-load")
    public ResponseEntity<Map<String, Object>> startFullLoad() {
        return accept(fullLoadService::triggerFullLoad, RunKind.FULL);
    }

    @PostMapping("/incremental")
    public ResponseEntity<Map<String, Object>> startIncremental() {
        return accept(incrementalLoadService::triggerIncremental, RunKind.INCREMENTAL);
    }

    @PostMapping("/retry-stuck")
    public ResponseEntity<Map<String, Object>> retryStuck() {
        return accept(fullLoadService::triggerRetryStuck, RunKind.RETRY);
    }

    @PostMapping("/reproject")
    public ResponseEntity<Map<String, Object>> reproject() {
        return accept(reprojectService::trigger, RunKind.REPROJECT);
    }

    @PostMapping("/edges/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildEdges() {
        return accept(edgeBuildService::triggerFullRebuild, RunKind.EDGE_FULL);
    }

    @PostMapping("/edges/incremental")
    public ResponseEntity<Map<String, Object>> incrementalEdges(
            @RequestParam(required = false) Long ingestRunId) {
        if (ingestRunId == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ingestRunId query parameter is required"));
        }
        return accept(() -> edgeBuildService.triggerIncrementalEdges(ingestRunId), RunKind.EDGE_INCREMENTAL);
    }

    @PostMapping("/edges/path-index")
    public ResponseEntity<Map<String, Object>> recomputePathIndex() {
        if (!pathIndexRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "path-index recompute already running"));
        }
        Thread.ofPlatform().name("path-index-build").start(() ->
                Try.run(pathIndexService::recompute)
                        .onFailure(e -> log.error("path-index recompute failed", e))
                        .andFinally(() -> pathIndexRunning.set(false)));
        return ResponseEntity.accepted().body(Map.of("status", "path-index recompute started"));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRunStatus(@PathVariable long id) {
        Optional<Map<String, Object>> run = runRepo.findById(id);
        if (run.isEmpty()) return ResponseEntity.notFound().build();

        Map<String, Object> result = new LinkedHashMap<>(run.get());
        if (RunStatus.RUNNING.name().equals(result.get("status"))) {
            if (RunKind.valueOf((String) result.get("kind")).isLoad()) result.put("queueCounts", queueRepo.stateCounts());
            result.put("cancelled", runRegistry.isCancelled(id));
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/runs/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancelRun(@PathVariable long id) {
        Optional<Map<String, Object>> run = runRepo.findById(id);
        if (run.isEmpty()) return ResponseEntity.notFound().build();
        if (!RunStatus.RUNNING.name().equals(run.get().get("status"))) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Run " + id + " is not in RUNNING state"));
        }
        if (!runRegistry.cancel(id)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Run " + id + " is not actively tracked (may have already finished)"));
        }
        return ResponseEntity.ok(Map.of("runId", id, "message", "Cancellation requested"));
    }

    private ResponseEntity<Map<String, Object>> accept(Supplier<Long> trigger, RunKind kind) {
        return Try.ofSupplier(trigger)
                .<ResponseEntity<Map<String, Object>>>map(runId -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of("runId", runId, "status", RunStatus.RUNNING.name(), "kind", kind.name())))
                .recover(IllegalStateException.class, e -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", e.getMessage())))
                .get();
    }
}
