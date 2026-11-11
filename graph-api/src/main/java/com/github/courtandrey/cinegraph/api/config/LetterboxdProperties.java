package com.github.courtandrey.cinegraph.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "letterboxd")
public class LetterboxdProperties {

    private String userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private int maxGraphNodes = 50000;
    private int minGraphNodes = 1;
    private double clusterResolution = 1.0;
    private int minClusterSize = 8;
    private double floaterShare = 0.4;

    public String getUserAgent()       { return userAgent; }
    public void setUserAgent(String v) { this.userAgent = v; }

    public int getMaxGraphNodes()      { return maxGraphNodes; }
    public void setMaxGraphNodes(int v) { this.maxGraphNodes = v; }

    public int getMinGraphNodes()      { return minGraphNodes; }
    public void setMinGraphNodes(int v) { this.minGraphNodes = v; }

    public double getClusterResolution()      { return clusterResolution; }
    public void setClusterResolution(double v) { this.clusterResolution = v; }

    public int getMinClusterSize()      { return minClusterSize; }
    public void setMinClusterSize(int v) { this.minClusterSize = v; }

    public double getFloaterShare()      { return floaterShare; }
    public void setFloaterShare(double v) { this.floaterShare = v; }
}
