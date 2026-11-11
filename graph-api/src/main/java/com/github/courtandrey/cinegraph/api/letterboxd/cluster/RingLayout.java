package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import com.github.courtandrey.cinegraph.api.domain.ClusterKind;
import io.vavr.control.Option;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class RingLayout {

    static final double NODE_H = 96;
    static final double LABEL_H = 20;
    static final double FILM_EXTENT = 58 + LABEL_H;
    static final double HUB_EXTENT = 87 + LABEL_H;
    static final double PADDING = 24;

    private static final double ARC_PER_NODE = 120;
    private static final double FIRST_RING = 179;
    private static final double RING_STEP = NODE_H + LABEL_H + 16;
    private static final double GROUP_MIN_RING = 80;
    private static final double GOLDEN_ANGLE = 2.3999632;

    private RingLayout() {}

    public static double discRadius(ClusterKind kind, int count) {
        if (count <= 1) return (kind == ClusterKind.CLUSTER ? HUB_EXTENT : FILM_EXTENT) + PADDING;
        List<Double> rings = ringRadii(kind, count - (kind == ClusterKind.CLUSTER ? 1 : 0));
        return rings.getLast() + FILM_EXTENT + PADDING;
    }

    public static double filmRadius() {
        return FILM_EXTENT + PADDING / 2;
    }

    public static Map<Long, Vec> arrange(ClusterKind kind, Vec center, List<Long> membersByStrength,
                                         Function<Long, Option<Double>> preferredAngle) {
        Map<Long, Vec> out = new LinkedHashMap<>();
        if (membersByStrength.isEmpty()) return out;
        boolean hubbed = kind == ClusterKind.CLUSTER || membersByStrength.size() == 1;
        if (hubbed) out.put(membersByStrength.getFirst(), center);
        List<Long> ringed = hubbed ? membersByStrength.subList(1, membersByStrength.size()) : membersByStrength;
        List<Double> radii = ringRadii(kind, ringed.size());

        int offset = 0;
        for (int ring = 0; ring < radii.size(); ring++) {
            double radius = radii.get(ring);
            int size = Math.min(capacity(kind, radius, ringed.size()), ringed.size() - offset);
            List<Long> members = ringed.subList(offset, offset + size);
            double phase = (ring * GOLDEN_ANGLE) % (2 * Math.PI);
            assignSlots(members, phase, preferredAngle)
                    .forEach((id, angle) -> out.put(id, center.plus(Vec.polar(radius, angle))));
            offset += size;
        }
        return out;
    }

    private static List<Double> ringRadii(ClusterKind kind, int count) {
        List<Double> radii = new ArrayList<>();
        if (count <= 0) return radii;
        if (kind == ClusterKind.GROUP) {
            radii.add(Math.max(GROUP_MIN_RING, count * ARC_PER_NODE / (2 * Math.PI)));
            return radii;
        }
        double radius = FIRST_RING;
        int placed = 0;
        while (placed < count) {
            radii.add(radius);
            placed += capacity(kind, radius, count);
            radius += RING_STEP;
        }
        return radii;
    }

    private static int capacity(ClusterKind kind, double radius, int count) {
        return kind == ClusterKind.GROUP
                ? count
                : Math.max(1, (int) Math.floor(2 * Math.PI * radius / ARC_PER_NODE));
    }

    private static Map<Long, Double> assignSlots(List<Long> members, double phase,
                                                 Function<Long, Option<Double>> preferredAngle) {
        int n = members.size();
        double[] slots = new double[n];
        for (int s = 0; s < n; s++) slots[s] = phase + 2 * Math.PI * s / n;
        boolean[] taken = new boolean[n];
        Map<Long, Double> assigned = new HashMap<>();

        members.stream()
                .flatMap(id -> preferredAngle.apply(id).map(a -> Map.entry(id, a)).toJavaStream())
                .forEach(e -> {
                    int slot = nearestFree(slots, taken, e.getValue());
                    taken[slot] = true;
                    assigned.put(e.getKey(), slots[slot]);
                });
        int cursor = 0;
        for (Long id : members) {
            if (assigned.containsKey(id)) continue;
            while (taken[cursor]) cursor++;
            taken[cursor] = true;
            assigned.put(id, slots[cursor]);
        }
        return assigned;
    }

    private static int nearestFree(double[] slots, boolean[] taken, double angle) {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int s = 0; s < slots.length; s++) {
            if (taken[s]) continue;
            double gap = angularGap(slots[s], angle);
            if (gap < bestGap) {
                best = s;
                bestGap = gap;
            }
        }
        return best;
    }

    private static double angularGap(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return Math.min(d, 2 * Math.PI - d);
    }

    static Comparator<Long> byStrength(Map<Long, Double> strength) {
        return Comparator.<Long>comparingDouble(id -> -strength.getOrDefault(id, 0.0))
                .thenComparing(Comparator.naturalOrder());
    }
}
