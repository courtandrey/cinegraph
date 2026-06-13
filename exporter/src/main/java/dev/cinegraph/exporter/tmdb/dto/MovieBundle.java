package dev.cinegraph.exporter.tmdb.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MovieBundle {

    private long id;
    private String title;
    @JsonProperty("original_title") private String originalTitle;
    @JsonProperty("release_date")   private String releaseDate;
    @JsonProperty("original_language") private String originalLanguage;
    private double popularity;
    @JsonProperty("vote_average")   private double voteAverage;
    @JsonProperty("vote_count")     private int voteCount;
    private Integer runtime;
    @JsonProperty("poster_path")    private String posterPath;
    private String overview;
    @JsonProperty("production_countries") private List<ProductionCountry> productionCountries;
    private List<Genre> genres;
    private Credits credits;
    private KeywordsWrapper keywords;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Genre(int id, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductionCountry(@JsonProperty("iso_3166_1") String code) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Credits(List<CastMember> cast, List<CrewMember> crew) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CastMember(
            long id, String name,
            @JsonProperty("profile_path") String profilePath,
            @JsonProperty("order") int castOrder,
            String character
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrewMember(
            long id, String name,
            @JsonProperty("profile_path") String profilePath,
            String department,
            String job
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record KeywordsWrapper(List<Keyword> keywords) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Keyword(int id, String name) {}

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOriginalTitle() { return originalTitle; }
    public void setOriginalTitle(String originalTitle) { this.originalTitle = originalTitle; }
    public String getReleaseDate() { return releaseDate; }
    public void setReleaseDate(String releaseDate) { this.releaseDate = releaseDate; }
    public String getOriginalLanguage() { return originalLanguage; }
    public void setOriginalLanguage(String originalLanguage) { this.originalLanguage = originalLanguage; }
    public double getPopularity() { return popularity; }
    public void setPopularity(double popularity) { this.popularity = popularity; }
    public double getVoteAverage() { return voteAverage; }
    public void setVoteAverage(double voteAverage) { this.voteAverage = voteAverage; }
    public int getVoteCount() { return voteCount; }
    public void setVoteCount(int voteCount) { this.voteCount = voteCount; }
    public Integer getRuntime() { return runtime; }
    public void setRuntime(Integer runtime) { this.runtime = runtime; }
    public String getPosterPath() { return posterPath; }
    public void setPosterPath(String posterPath) { this.posterPath = posterPath; }
    public String getOverview() { return overview; }
    public void setOverview(String overview) { this.overview = overview; }
    public List<ProductionCountry> getProductionCountries() { return productionCountries; }
    public void setProductionCountries(List<ProductionCountry> productionCountries) { this.productionCountries = productionCountries; }
    public List<Genre> getGenres() { return genres; }
    public void setGenres(List<Genre> genres) { this.genres = genres; }
    public Credits getCredits() { return credits; }
    public void setCredits(Credits credits) { this.credits = credits; }
    public KeywordsWrapper getKeywords() { return keywords; }
    public void setKeywords(KeywordsWrapper keywords) { this.keywords = keywords; }
}
