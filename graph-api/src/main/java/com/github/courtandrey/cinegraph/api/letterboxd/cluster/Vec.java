package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

public record Vec(double x, double y) {

    public static final Vec ZERO = new Vec(0, 0);

    public static Vec polar(double radius, double angle) {
        return new Vec(radius * Math.cos(angle), radius * Math.sin(angle));
    }

    public Vec plus(Vec o) {
        return new Vec(x + o.x, y + o.y);
    }

    public Vec minus(Vec o) {
        return new Vec(x - o.x, y - o.y);
    }

    public Vec times(double k) {
        return new Vec(x * k, y * k);
    }

    public double length() {
        return Math.hypot(x, y);
    }

    public double distance(Vec o) {
        return minus(o).length();
    }

    public double angle() {
        return Math.atan2(y, x);
    }

    public Vec unitOr(Vec fallback) {
        double len = length();
        return len < 1e-9 ? fallback : times(1 / len);
    }

    public Vec perpendicular() {
        return new Vec(-y, x);
    }
}
