package dev.cinegraph.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "graph")
public class GraphScoringProperties {

    private double minCrewScore     = 1.0;
    private double perPersonCap     = 20.0;
    private int    maxEdgeCandidates = 4000;

    public double getMinCrewScore()         { return minCrewScore; }
    public void setMinCrewScore(double v)         { this.minCrewScore = v; }

    public double getPerPersonCap()         { return perPersonCap; }
    public void setPerPersonCap(double v)         { this.perPersonCap = v; }

    public int getMaxEdgeCandidates()       { return maxEdgeCandidates; }
    public void setMaxEdgeCandidates(int v)       { this.maxEdgeCandidates = v; }
}
