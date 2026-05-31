package net.rainbowcreation.orge.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScratchPoolTest {

    @Test
    void reusesArraysAcrossCallsOfTheSameSize() {
        ScratchPool p = new ScratchPool();
        float[] t1 = p.temp(4096);
        float[] m1 = p.mass(4096);
        char[]  s1 = p.material(4096);
        float[] t2 = p.temp(4096);
        assertSame(t1, t2, "same-size temp request returns the SAME array (no realloc)");
        assertSame(m1, p.mass(4096));
        assertSame(s1, p.material(4096));
    }

    @Test
    void growsWhenABiggerBatchArrives_andKeepsTheBiggerArray() {
        ScratchPool p = new ScratchPool();
        float[] small = p.temp(4096);
        float[] big = p.temp(8192);
        assertNotSame(small, big);
        assertTrue(big.length >= 8192);
        // a subsequent smaller request reuses the bigger array (length >= requested), still no realloc.
        float[] again = p.temp(4096);
        assertSame(big, again, "pool keeps the high-water array and serves smaller requests from it");
    }

    @Test
    void lengthAtLeastRequested() {
        ScratchPool p = new ScratchPool();
        assertTrue(p.temp(100).length >= 100);
        assertTrue(p.mass(100).length >= 100);
        assertTrue(p.material(100).length >= 100);
    }
}
