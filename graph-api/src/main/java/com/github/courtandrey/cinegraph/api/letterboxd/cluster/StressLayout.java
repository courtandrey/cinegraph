package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * Places points so their pairwise distances approximate a target matrix: classical (Torgerson)
 * MDS for a deterministic start, then stress majorization under caller-supplied pair weights. {@link #separate} then removes disc overlaps
 * with the smallest pairwise pushes.
 */
public final class StressLayout {

    private static final int STRESS_ITERATIONS = 300;
    private static final int POWER_ITERATIONS = 200;
    private static final int SEPARATION_ROUNDS = 500;

    private StressLayout() {}

    public static List<Vec> embed(double[][] target, double[][] weight) {
        int n = target.length;
        if (n == 0) return List.of();
        if (n == 1) return List.of(Vec.ZERO);
        List<Vec> points = classicalScaling(target);
        for (int it = 0; it < STRESS_ITERATIONS; it++) points = majorize(points, target, weight);
        return centred(points);
    }

    public static List<Vec> separate(List<Vec> centers, double[] radii, double minGap) {
        List<Vec> pts = new ArrayList<>(centers);
        for (int round = 0; round < SEPARATION_ROUNDS; round++) {
            boolean moved = false;
            for (int i = 0; i < pts.size(); i++) {
                for (int j = i + 1; j < pts.size(); j++) {
                    double need = radii[i] + radii[j] + minGap;
                    Vec delta = pts.get(j).minus(pts.get(i));
                    double d = delta.length();
                    if (d >= need - 1e-6) continue;
                    Vec dir = delta.unitOr(Vec.polar(1, i * 2.3999632 + j));
                    Vec push = dir.times((need - d) / 2);
                    pts.set(i, pts.get(i).minus(push));
                    pts.set(j, pts.get(j).plus(push));
                    moved = true;
                }
            }
            if (!moved) break;
        }
        return centred(pts);
    }

    private static List<Vec> majorize(List<Vec> pts, double[][] target, double[][] weight) {
        int n = pts.size();
        List<Vec> next = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double sx = 0, sy = 0, sw = 0;
            Vec xi = pts.get(i);
            for (int j = 0; j < n; j++) {
                if (i == j || target[i][j] <= 0) continue;
                Vec xj = pts.get(j);
                double w = weight[i][j];
                Vec dir = xi.minus(xj).unitOr(Vec.polar(1, i - j));
                sx += w * (xj.x() + target[i][j] * dir.x());
                sy += w * (xj.y() + target[i][j] * dir.y());
                sw += w;
            }
            next.add(sw == 0 ? xi : new Vec(sx / sw, sy / sw));
        }
        return next;
    }

    private static List<Vec> classicalScaling(double[][] target) {
        int n = target.length;
        double[][] b = doubleCentredSquares(target);
        double[] first = dominantEigenvector(b, null);
        double[] second = dominantEigenvector(b, first);
        double l1 = Math.sqrt(Math.max(rayleigh(b, first), 0));
        double l2 = Math.sqrt(Math.max(rayleigh(b, second), 0));
        double fallback = maxEntry(target) / 2;
        List<Vec> pts = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Vec p = new Vec(first[i] * l1, second[i] * l2);
            pts.add(p.length() < 1e-9 ? Vec.polar(fallback, 2 * Math.PI * i / n) : p);
        }
        return pts;
    }

    private static double[][] doubleCentredSquares(double[][] d) {
        int n = d.length;
        double[][] sq = new double[n][n];
        double[] rowMean = new double[n];
        double grand = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                sq[i][j] = d[i][j] * d[i][j];
                rowMean[i] += sq[i][j] / n;
            }
            grand += rowMean[i] / n;
        }
        double[][] b = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) b[i][j] = -0.5 * (sq[i][j] - rowMean[i] - rowMean[j] + grand);
        }
        return b;
    }

    private static double[] dominantEigenvector(double[][] m, double[] orthogonalTo) {
        int n = m.length;
        double shift = spectralBound(m);
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = 1 + ((i * 0.6180339887) % 1);
        for (int it = 0; it < POWER_ITERATIONS; it++) {
            if (orthogonalTo != null) deflate(v, orthogonalTo);
            double[] next = new double[n];
            for (int i = 0; i < n; i++) {
                next[i] = shift * v[i];
                for (int j = 0; j < n; j++) next[i] += m[i][j] * v[j];
            }
            double norm = Math.sqrt(dot(next, next));
            if (norm < 1e-12) return new double[n];
            for (int i = 0; i < n; i++) v[i] = next[i] / norm;
        }
        if (orthogonalTo != null) deflate(v, orthogonalTo);
        return v;
    }

    private static double spectralBound(double[][] m) {
        double bound = 0;
        for (double[] row : m) {
            double sum = 0;
            for (double v : row) sum += Math.abs(v);
            bound = Math.max(bound, sum);
        }
        return bound;
    }

    private static void deflate(double[] v, double[] basis) {
        double proj = dot(v, basis);
        for (int i = 0; i < v.length; i++) v[i] -= proj * basis[i];
    }

    private static double rayleigh(double[][] m, double[] v) {
        double num = 0;
        for (int i = 0; i < v.length; i++) {
            for (int j = 0; j < v.length; j++) num += v[i] * m[i][j] * v[j];
        }
        double den = dot(v, v);
        return den < 1e-12 ? 0 : num / den;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double maxEntry(double[][] d) {
        double max = 0;
        for (double[] row : d) for (double v : row) max = Math.max(max, v);
        return max;
    }

    private static List<Vec> centred(List<Vec> pts) {
        Vec mean = pts.stream().reduce(Vec.ZERO, Vec::plus).times(1.0 / pts.size());
        return pts.stream().map(p -> p.minus(mean)).toList();
    }
}
