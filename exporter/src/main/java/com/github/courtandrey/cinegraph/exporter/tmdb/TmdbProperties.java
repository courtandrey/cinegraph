package com.github.courtandrey.cinegraph.exporter.tmdb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tmdb")
public class TmdbProperties {

    private String baseUrl = "https://api.themoviedb.org";
    private String exportBaseUrl = "https://files.tmdb.org";
    private String accessToken;
    private int maxConcurrency = 64;
    private RateLimit rateLimit = new RateLimit();

    public static class RateLimit {
        private int permitsPerSecond = 30;

        public int getPermitsPerSecond() { return permitsPerSecond; }
        public void setPermitsPerSecond(int permitsPerSecond) { this.permitsPerSecond = permitsPerSecond; }
    }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getExportBaseUrl() { return exportBaseUrl; }
    public void setExportBaseUrl(String exportBaseUrl) { this.exportBaseUrl = exportBaseUrl; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }
}
