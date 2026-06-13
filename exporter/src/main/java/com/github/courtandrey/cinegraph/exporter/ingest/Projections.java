package com.github.courtandrey.cinegraph.exporter.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.courtandrey.cinegraph.exporter.tmdb.dto.MovieBundle;
import io.vavr.control.Try;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Projections {

    public record Fetched(long movieId, String rawJson) {
        public static final Fetched POISON = new Fetched(-1L, null);

        public boolean isPoison() {
            return movieId == -1L;
        }
    }

    public record MovieRow(long movieId, String title, String originalTitle, LocalDate releaseDate,
                           Short releaseYear, String originalLanguage, float popularity,
                           float voteAverage, int voteCount, Short runtime, String posterPath,
                           String overview, String[] countries) {}

    public record PersonRow(long personId, String name, String profilePath) {}

    public record CreditRow(long movieId, long personId, String role, String job,
                            String department, Short castOrder, String character) {}

    public record Ref(int id, String name) {}

    public record Link(long movieId, int refId) {}

    public record RawRow(long movieId, String payload) {}

    public record Failure(long movieId, String reason) {}

    public record Batch(List<Long> projectedIds, List<Failure> failures,
                        List<RawRow> raws, List<MovieRow> movies,
                        List<Ref> genres, List<Link> movieGenres,
                        List<Ref> keywords, List<Link> movieKeywords,
                        List<PersonRow> persons, List<CreditRow> credits,
                        Set<String> roleCodes) {

        public boolean isEmpty() {
            return projectedIds.isEmpty();
        }
    }

    public static Batch project(List<Fetched> fetched, Roles roles, ObjectMapper mapper) {
        var acc = new Accumulator();
        for (Fetched f : fetched) {
            Try.of(() -> mapper.readValue(f.rawJson(), MovieBundle.class))
                    .fold(
                            e -> acc.failures.add(new Failure(f.movieId(), reason(e))),
                            bundle -> acc.add(f, bundle, roles));
        }
        return acc.toBatch();
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null ? e.getClass().getSimpleName()
                : message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static LocalDate parseDate(String s) {
        return s == null || s.isBlank() ? null : Try.of(() -> LocalDate.parse(s)).getOrNull();
    }

    private static final class Accumulator {
        final List<Long> projectedIds = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();
        final List<RawRow> raws = new ArrayList<>();
        final List<MovieRow> movies = new ArrayList<>();
        final List<Ref> genres = new ArrayList<>();
        final List<Link> movieGenres = new ArrayList<>();
        final List<Ref> keywords = new ArrayList<>();
        final List<Link> movieKeywords = new ArrayList<>();
        final List<PersonRow> persons = new ArrayList<>();
        final List<CreditRow> credits = new ArrayList<>();
        final Set<String> roleCodes = new HashSet<>();
        final Set<Long> seenGenreLinks = new HashSet<>();
        final Set<Long> seenKeywordLinks = new HashSet<>();

        boolean add(Fetched f, MovieBundle b, Roles roles) {
            raws.add(new RawRow(b.getId(), f.rawJson()));
            movies.add(movieRow(b));

            if (b.getGenres() != null) {
                for (MovieBundle.Genre g : b.getGenres()) {
                    if (!seenGenreLinks.add((b.getId() << 32) | g.id())) continue;
                    genres.add(new Ref(g.id(), g.name()));
                    movieGenres.add(new Link(b.getId(), g.id()));
                }
            }
            if (b.getKeywords() != null && b.getKeywords().keywords() != null) {
                for (MovieBundle.Keyword k : b.getKeywords().keywords()) {
                    if (!seenKeywordLinks.add((b.getId() << 32) | k.id())) continue;
                    keywords.add(new Ref(k.id(), k.name()));
                    movieKeywords.add(new Link(b.getId(), k.id()));
                }
            }
            if (b.getCredits() != null) {
                addCast(b, roles);
                addCrew(b, roles);
            }
            projectedIds.add(f.movieId());
            return true;
        }

        void addCast(MovieBundle b, Roles roles) {
            if (b.getCredits().cast() == null) return;
            for (MovieBundle.CastMember c : b.getCredits().cast()) {
                String role = roles.castRole(c.castOrder());
                roleCodes.add(role);
                persons.add(new PersonRow(c.id(), c.name(), c.profilePath()));
                credits.add(new CreditRow(b.getId(), c.id(), role, null, "Acting",
                        (short) c.castOrder(), c.character()));
            }
        }

        void addCrew(MovieBundle b, Roles roles) {
            if (b.getCredits().crew() == null) return;
            for (MovieBundle.CrewMember c : b.getCredits().crew()) {
                String role = roles.crewRole(c.department(), c.job());
                roleCodes.add(role);
                persons.add(new PersonRow(c.id(), c.name(), c.profilePath()));
                credits.add(new CreditRow(b.getId(), c.id(), role, c.job(), c.department(),
                        null, null));
            }
        }

        Batch toBatch() {
            return new Batch(projectedIds, failures, raws, movies, genres, movieGenres,
                    keywords, movieKeywords, persons, credits, roleCodes);
        }

        MovieRow movieRow(MovieBundle b) {
            LocalDate releaseDate = parseDate(b.getReleaseDate());
            String[] countries = b.getProductionCountries() == null
                    ? new String[0]
                    : b.getProductionCountries().stream()
                            .map(MovieBundle.ProductionCountry::code)
                            .toArray(String[]::new);
            return new MovieRow(
                    b.getId(), b.getTitle(), b.getOriginalTitle(), releaseDate,
                    releaseDate != null ? (short) releaseDate.getYear() : null,
                    b.getOriginalLanguage(), (float) b.getPopularity(),
                    (float) b.getVoteAverage(), b.getVoteCount(),
                    b.getRuntime() != null ? b.getRuntime().shortValue() : null,
                    b.getPosterPath(), b.getOverview(), countries);
        }
    }

    private Projections() {}
}
