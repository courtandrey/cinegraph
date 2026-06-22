package com.github.courtandrey.cinegraph.api.controller;

import com.github.courtandrey.cinegraph.api.letterboxd.LetterboxdGraphService;
import io.vavr.control.Try;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/letterboxd")
public class LetterboxdController {

    private final LetterboxdGraphService service;

    public LetterboxdController(LetterboxdGraphService service) {
        this.service = service;
    }

    public record RecenterRequest(String hash, Long movieId, Float minScore, Integer limit) {}

    public record ReweightRequest(String hash, Long movieId, Integer limit,
                                  Map<String, Double> weights, Float minScore) {}

    @PostMapping(value = "/graphs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> graphs(@RequestPart("file") MultipartFile file) {
        byte[] bytes = file == null || file.isEmpty()
                ? new byte[0]
                : Try.of(file::getBytes).getOrElse(new byte[0]);
        if (bytes.length == 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "CSV file required"));
        }
        String hash = sha256(bytes);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        return ResponseEntity.ok(service.buildGraphs(hash, csv));
    }

    @GetMapping("/{hash}/graphs")
    public ResponseEntity<?> overview(@PathVariable String hash) {
        return ResponseEntity.ok(service.overview(hash));
    }

    @GetMapping("/{hash}/search")
    public ResponseEntity<?> search(@PathVariable String hash,
                                    @RequestParam String q,
                                    @RequestParam(defaultValue = "10") int limit) {
        if (q == null || q.trim().length() < 2) {
            return ResponseEntity.ok(java.util.List.of());
        }
        return ResponseEntity.ok(service.search(hash, q.trim(), Math.min(limit, 50)));
    }

    @PostMapping("/recenter")
    public ResponseEntity<?> recenter(@RequestBody RecenterRequest req) {
        if (req.hash() == null || req.movieId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "hash and movieId required"));
        }
        float minScore = req.minScore() == null ? 12f : req.minScore();
        int limit = req.limit() == null ? 40 : req.limit();
        return service.recenter(req.hash(), req.movieId(), minScore, limit)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(req.movieId()));
    }

    @PostMapping("/reweight")
    public ResponseEntity<?> reweight(@RequestBody ReweightRequest req) {
        if (req.hash() == null || req.movieId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "hash and movieId required"));
        }
        float minScore = req.minScore() == null ? 0f : req.minScore();
        return service.reweight(req.hash(), req.movieId(), req.limit(), req.weights(), minScore)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(req.movieId()));
    }

    private ResponseEntity<?> notFound(long id) {
        return ResponseEntity.status(404).body(Map.of("status", 404, "error", "movie " + id + " not found"));
    }

    private static String sha256(byte[] data) {
        return Try.of(() -> MessageDigest.getInstance("SHA-256"))
                .map(md -> HexFormat.of().formatHex(md.digest(data)))
                .get();
    }
}
