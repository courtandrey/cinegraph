package com.github.courtandrey.cinegraph.api.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String LETTERBOXD_TMDB_ID = "letterboxdTmdbId";
}
