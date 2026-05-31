package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.section.SubchunkKey;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure rule for Decision 11 trigger (c): map a section's six boundary-face mass-flux flags to the
 * adjacent section keys that must wake. Without this, an active section that pushes fluid toward a
 * dormant neighbour would see flow stop dead at the border (the dormant neighbour is never snapshotted,
 * so it never accepts the incoming mass). Face order matches the kernel/halo convention used elsewhere:
 * negX, posX, negY, posY, negZ, posZ.
 */
public final class SeamFluxWake {

    private SeamFluxWake() {
    }

    public static List<SubchunkKey> neighboursToWake(SubchunkKey k,
                                                     boolean negX, boolean posX,
                                                     boolean negY, boolean posY,
                                                     boolean negZ, boolean posZ) {
        List<SubchunkKey> out = new ArrayList<>(6);
        if (negX) out.add(new SubchunkKey(k.cx() - 1, k.sectionY(), k.cz()));
        if (posX) out.add(new SubchunkKey(k.cx() + 1, k.sectionY(), k.cz()));
        if (negY) out.add(new SubchunkKey(k.cx(), k.sectionY() - 1, k.cz()));
        if (posY) out.add(new SubchunkKey(k.cx(), k.sectionY() + 1, k.cz()));
        if (negZ) out.add(new SubchunkKey(k.cx(), k.sectionY(), k.cz() - 1));
        if (posZ) out.add(new SubchunkKey(k.cx(), k.sectionY(), k.cz() + 1));
        return out;
    }
}
