package com.github.courtandrey.cinegraph.exporter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "scoring")
public class ScoringProperties {

    private double minCrewScore        = 1.0;
    private double perPersonCap        = 20.0;
    private int    maxCreditsPerPerson = 800;
    private boolean includeMinorCast   = false;
    private int    buckets             = 64;

    public double getMinCrewScore()        { return minCrewScore; }
    public void setMinCrewScore(double v)        { this.minCrewScore = v; }

    public double getPerPersonCap()        { return perPersonCap; }
    public void setPerPersonCap(double v)        { this.perPersonCap = v; }

    public int getMaxCreditsPerPerson()    { return maxCreditsPerPerson; }
    public void setMaxCreditsPerPerson(int v)    { this.maxCreditsPerPerson = v; }

    public boolean isIncludeMinorCast()    { return includeMinorCast; }
    public void setIncludeMinorCast(boolean v)   { this.includeMinorCast = v; }

    public int getBuckets()                { return buckets; }
    public void setBuckets(int v)                { this.buckets = v; }
}
