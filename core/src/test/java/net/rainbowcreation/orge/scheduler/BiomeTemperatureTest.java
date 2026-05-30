package net.rainbowcreation.orge.scheduler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeTemperatureTest {
    @Test void temperateAnchorsAt285() { assertEquals(285f, BiomeTemperature.toKelvin(0.8f), 1e-4f); }
    @Test void snowy()  { assertEquals(269f, BiomeTemperature.toKelvin(0.0f), 1e-4f); }
    @Test void desert() { assertEquals(309f, BiomeTemperature.toKelvin(2.0f), 1e-4f); }
    @Test void negativeBiome() { assertEquals(259f, BiomeTemperature.toKelvin(-0.5f), 1e-4f); }
    @Test void clampsNonNegative() { assertTrue(BiomeTemperature.toKelvin(-100f) >= 0f); }
}
