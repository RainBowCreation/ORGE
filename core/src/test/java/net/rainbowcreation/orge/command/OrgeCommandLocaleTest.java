package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class OrgeCommandLocaleTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restore() { Locale.setDefault(original); }

    /** A read source returning a fixed-temperature ambient view, so GET formats a float. */
    private static OrgeCommandLogic logic() {
        ThermalReadSource src = (dim, key) -> Optional.of(new SectionView() {
            public float tempAt(int cell) { return 285.0f; }
            public float massAt(int cell) { return 1000.0f; }
            public net.rainbowcreation.orge.section.SectionData.Form form() {
                return net.rainbowcreation.orge.section.SectionData.Form.UNIFORM;
            }
            public boolean ambient() { return true; }
        });
        return new OrgeCommandLogic(List.of(src), NoWriteSink.INSTANCE, () -> 8);
    }

    @Test
    void getUsesDotDecimalUnderCommaLocale() {
        Locale.setDefault(Locale.GERMANY); // comma decimal separator
        OrgeCommandLogic.Request r = new OrgeCommandLogic.Request(
                OrgeCommandLogic.Op.GET,
                Identifier.fromNamespaceAndPath("minecraft", "overworld"),
                0, 64, 0, 0, 64, 0, null, null, true, null, -64, 320);
        OrgeCommandLogic.Response resp = logic().run(r);
        assertTrue(resp.ok());
        String line = resp.lines().get(0);
        assertTrue(line.contains("285.00 K"), "expected dot-decimal, got: " + line);
        assertFalse(line.contains("285,00"), "comma decimal leaked: " + line);
    }

    private enum NoWriteSink implements ThermalWriteSink {
        INSTANCE;
        public boolean isLoaded(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key) { return false; }
        public void writeTemp(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key, int cell, float t) {}
        public void writeMass(Identifier dim, net.rainbowcreation.orge.section.SubchunkKey key, int cell, float m) {}
    }
}
