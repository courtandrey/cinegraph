package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

/**
 * Turns cluster affinities into layout target distances. Hops are path lengths through the
 * maximum spanning tree of the affinity graph, each edge costing {@code 1 - ln(a / aMax)}.
 * Every cluster's strongest link is a tree edge, so it gets the shortest target, and a tree
 * metric embeds in the plane with far less distortion than the dense affinity graph.
 */
public final class Distances {

    private Distances() {}

    public static double[][] treeHops(double[][] affinity) {
        return affinityHops(maximumSpanningTree(affinity));
    }

    static double[][] maximumSpanningTree(double[][] affinity) {
        int k = affinity.length;
        double[][] tree = new double[k][k];
        boolean[] reached = new boolean[k];
        for (int root = 0; root < k; root++) {
            if (reached[root]) continue;
            reached[root] = true;
            while (true) {
                int from = -1, to = -1;
                for (int i = 0; i < k; i++) {
                    if (!reached[i]) continue;
                    for (int j = 0; j < k; j++) {
                        if (reached[j] || affinity[i][j] <= 0) continue;
                        if (from < 0 || affinity[i][j] > affinity[from][to]) {
                            from = i;
                            to = j;
                        }
                    }
                }
                if (from < 0) break;
                reached[to] = true;
                tree[from][to] = tree[to][from] = affinity[from][to];
            }
        }
        return tree;
    }

    static double[][] affinityHops(double[][] affinity) {
        int k = affinity.length;
        double strongest = 0;
        for (double[] row : affinity) for (double a : row) strongest = Math.max(strongest, a);
        double[][] d = new double[k][k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < k; j++) {
                d[i][j] = i == j ? 0
                        : affinity[i][j] > 0 ? 1 - Math.log(affinity[i][j] / strongest)
                        : Double.POSITIVE_INFINITY;
            }
        }
        for (int via = 0; via < k; via++) {
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) d[i][j] = Math.min(d[i][j], d[i][via] + d[via][j]);
            }
        }
        double farthest = 1;
        for (double[] row : d) for (double v : row) if (Double.isFinite(v)) farthest = Math.max(farthest, v);
        for (double[] row : d) {
            for (int j = 0; j < k; j++) if (!Double.isFinite(row[j])) row[j] = farthest + 1;
        }
        return d;
    }
}
