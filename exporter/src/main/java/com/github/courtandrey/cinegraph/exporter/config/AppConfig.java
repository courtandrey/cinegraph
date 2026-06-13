package com.github.courtandrey.cinegraph.exporter.config;

import com.github.courtandrey.cinegraph.exporter.ingest.Roles;
import com.github.courtandrey.cinegraph.exporter.repo.RoleRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AppConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public Roles roles() {
        InputStream yaml = getClass().getResourceAsStream("/roles.yml");
        if (yaml == null) throw new IllegalStateException("roles.yml not found on classpath");
        return Roles.fromYaml(yaml);
    }

    @Bean
    public ApplicationRunner roleSeeder(Roles roles, RoleRepository roleRepository) {
        return args -> roleRepository.seed(roles.seedWeights());
    }
}
