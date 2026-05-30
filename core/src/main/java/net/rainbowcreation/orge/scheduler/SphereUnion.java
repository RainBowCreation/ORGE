package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.HashSet;
import java.util.Set;

/**
 * Expands a set of player anchor sections into the union of their range-N spheres
 * (DESIGN §4). Range 1 = the anchor section only; a section at offset
 * {@code (dx,dy,dz)} is included iff {@code dx²+dy²+dz² <= (range-1)²}. Overlapping
 * spheres dedup naturally via the {@link Set}.
 */
public final class SphereUnion {

    private SphereUnion() {}

    public static Set<SubchunkKey> expand(Set<SubchunkKey> anchors, int range) {
        int r = Math.max(1, range) - 1;
        int r2 = r * r;
        Set<SubchunkKey> out = new HashSet<>();
        for (SubchunkKey a : anchors) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dy * dy + dz * dz <= r2) {
                            out.add(new SubchunkKey(a.cx() + dx, a.sectionY() + dy, a.cz() + dz));
                        }
                    }
                }
            }
        }
        return out;
    }
}
