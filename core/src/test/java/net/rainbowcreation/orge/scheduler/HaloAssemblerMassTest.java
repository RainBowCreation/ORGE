package net.rainbowcreation.orge.scheduler;

import net.rainbowcreation.orge.engine.NeighborHalo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HaloAssemblerMassTest {
    @Test
    void neighbourMassIsCarriedOntoTheFace() {
        // a -x neighbour whose cells all carry 750 kg.
        // Neighbor arrays are SECTION-sized (4096): HaloAssembler#assemble indexes them by
        // sidx(x,y,z)=x+16y+256z (up to 4095), matching HaloAssemblerTest's identityNeighbor().
        float[] t = new float[4096]; char[] m = new char[4096]; float[] mass = new float[4096];
        java.util.Arrays.fill(t, 280f);
        java.util.Arrays.fill(m, (char) 1);
        java.util.Arrays.fill(mass, 750f);
        HaloAssembler.Neighbor negX = new HaloAssembler.Neighbor(t, m, mass);
        HaloAssembler.Neighbor none = HaloAssembler.Neighbor.absent();
        NeighborHalo h = HaloAssembler.assemble(negX, none, none, none, none, none);
        assertEquals(750f, h.negXMass()[0], 0f);
        assertEquals(0f, h.posXMass()[0], 0f); // absent => 0 kg
    }
}
