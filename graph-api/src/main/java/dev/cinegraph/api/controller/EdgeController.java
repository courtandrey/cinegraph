package dev.cinegraph.api.controller;

import dev.cinegraph.api.dto.EdgeBreakdown;
import dev.cinegraph.api.repo.EdgeQueryRepository;
import dev.cinegraph.api.repo.MovieQueryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/edges")
public class EdgeController {

    private final EdgeQueryRepository edgeRepo;
    private final MovieQueryRepository movieRepo;

    public EdgeController(EdgeQueryRepository edgeRepo, MovieQueryRepository movieRepo) {
        this.edgeRepo = edgeRepo;
        this.movieRepo = movieRepo;
    }

    @GetMapping("/{a}/{b}")
    public ResponseEntity<?> breakdown(@PathVariable long a, @PathVariable long b) {
        var raw = edgeRepo.findEdge(a, b);
        if (raw.isEmpty()) {
            return ResponseEntity.status(404).body(
                    Map.of("status", 404, "error",
                           "no edge between " + Math.min(a, b) + " and " + Math.max(a, b)));
        }
        var e = raw.get();
        var movieA = movieRepo.findById(e.movieA());
        var movieB = movieRepo.findById(e.movieB());
        if (movieA.isEmpty() || movieB.isEmpty()) {
            return ResponseEntity.status(404).body(
                    Map.of("status", 404, "error", "movie details not found"));
        }
        return ResponseEntity.ok(new EdgeBreakdown(
                movieA.get(), movieB.get(), e.totalScore(), e.crewScore(), e.components()));
    }
}
