package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Expands a set of player anchor sections into the union of their range-N spheres
 * (DESIGN §4). Range 1 = the anchor section only; a section at offset
 * {@code (dx,dy,dz)} is included iff {@code dx²+dy²+dz² <= (range-1)²}. Overlapping
 * spheres dedup naturally via the {@link Set}. A {@code range <= 0} is treated as range 1 (anchor only).
 */
public final class SphereUnion {

    private SphereUnion() {}

    /**
     * Whether {@code target} lies within the range-N sphere centred on {@code anchor}
     * (DESIGN §4): a section at offset {@code (dx,dy,dz)} is included iff
     * {@code dx*dx + dy*dy + dz*dz <= (range-1)^2}. {@code range <= 0} is treated as 1.
     */
    public static boolean contains(SubchunkKey anchor, SubchunkKey target, int range) {
        int r = Math.max(1, range) - 1;
        int dx = target.cx() - anchor.cx();
        int dy = target.sectionY() - anchor.sectionY();
        int dz = target.cz() - anchor.cz();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    public static Set<SubchunkKey> expand(Set<SubchunkKey> anchors, int range) {
        int r = Math.max(1, range) - 1;
        Set<SubchunkKey> out = new HashSet<>();
        for (SubchunkKey a : anchors) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        SubchunkKey candidate =
                                new SubchunkKey(a.cx() + dx, a.sectionY() + dy, a.cz() + dz);
                        if (contains(a, candidate, range)) {
                            out.add(candidate);
                        }
                    }
                }
            }
        }
        return out;
    }
}
