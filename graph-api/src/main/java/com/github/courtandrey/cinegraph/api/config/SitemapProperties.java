package com.github.courtandrey.cinegraph.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "sitemap")
public class SitemapProperties {

    private String siteBaseUrl = "http://localhost:4200";
    private int    maxUrls     = 45000;

    public String getSiteBaseUrl()          { return siteBaseUrl; }
    public void setSiteBaseUrl(String v)          { this.siteBaseUrl = v; }

    public int getMaxUrls()                 { return maxUrls; }
    public void setMaxUrls(int v)                 { this.maxUrls = v; }
}
