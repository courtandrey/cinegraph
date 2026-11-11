package com.github.courtandrey.cinegraph.api.letterboxd.cluster;

import io.vavr.control.Option;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Placement {

    @FunctionalInterface
    public interface FreeCheck {
        boolean isFree(Vec position, double radius);
    }

    private static final int AXIS_STEPS = 3;
    private static final int LATERAL_STEPS = 2;
    private static final int SATELLITE_STEPS = 12;
    private static final double SATELLITE_TURN = Math.PI / 12;

    private Placement() {}

    public static Option<Vec> corridor(Vec a, double ra, Vec b, double rb, double towardB,
                                       double radius, double margin, FreeCheck free) {
        Vec axis = b.minus(a).unitOr(new Vec(1, 0));
        double from = ra + radius + margin;
        double to = a.distance(b) - rb - radius - margin;
        if (to < from) return Option.none();
        double base = from + (to - from) * towardB;
        double step = 2 * radius + margin;
        double lateralLimit = Math.min(ra, rb);

        record Candidate(double along, double lateral) {
            double displacement(double base) {
                return Math.abs(along - base) + Math.abs(lateral);
            }
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int s = -AXIS_STEPS; s <= AXIS_STEPS; s++) {
            double along = base + s * step;
            if (along < from || along > to) continue;
            for (int l = -LATERAL_STEPS; l <= LATERAL_STEPS; l++) {
                if (Math.abs(l * step) <= lateralLimit) candidates.add(new Candidate(along, l * step));
            }
        }
        return Option.ofOptional(candidates.stream()
                .sorted(Comparator.comparingDouble((Candidate c) -> c.displacement(base))
                        .thenComparingDouble(Candidate::along)
                        .thenComparingDouble(Candidate::lateral))
                .map(c -> a.plus(axis.times(c.along())).plus(axis.perpendicular().times(c.lateral())))
                .filter(p -> free.isFree(p, radius))
                .findFirst());
    }

    public static Option<Vec> satellite(Vec a, double ra, Vec outward, double radius, double margin,
                                        FreeCheck free) {
        double distance = ra + radius + margin;
        double heading = outward.angle();
        for (int s = 0; s <= SATELLITE_STEPS; s++) {
            for (int sign : s == 0 ? new int[]{1} : new int[]{1, -1}) {
                Vec p = a.plus(Vec.polar(distance, heading + sign * s * SATELLITE_TURN));
                if (free.isFree(p, radius)) return Option.some(p);
            }
        }
        return Option.none();
    }
}
