package net.rainbowcreation.orge.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The optional {@code "effects"} JSON object a material declares (render only). */
class MaterialEffectsTest {

    private static final Identifier ID = Identifier.fromNamespaceAndPath("orge", "test_water");
    private static final String BASE = "\"thermal_conductivity\": 0.6, \"heat_capacity\": 4186, \"molar_mass\": 0.018,"
            + " \"default_mass\": 1000, \"default_temperature\": 290, \"viscosity\": 0.001";

    private static Material decode(String extra) {
        return MaterialCodec.fromJson(ID, JsonParser.parseString("{" + BASE + extra + "}"));
    }

    @Test
    void absentEffectsAreNone() {
        assertSame(MaterialEffects.NONE, decode("").effects());
    }

    @Test
    void aParticleIdBecomesTheVanillaParticleObject() {
        Material m = decode(", \"effects\": {\"suspended\": {\"particle\": \"minecraft:falling_water\","
                + " \"under_solid\": \"minecraft:dripping_water\"}}");
        MaterialEffects.Effect s = m.effects().suspended();
        assertEquals(JsonParser.parseString("{\"type\": \"minecraft:falling_water\"}"), s.particle());
        assertEquals(JsonParser.parseString("{\"type\": \"minecraft:dripping_water\"}"), s.underSolid());
        assertNull(s.sound());
        assertEquals(0f, s.minKg());
        assertNull(m.effects().boiling());
    }

    @Test
    void aFullParticleObjectAndSoundAreKept() {
        Material m = decode(", \"effects\": {\"boiling\": {\"particle\": {\"type\": \"minecraft:dust\", \"scale\": 2},"
                + " \"sound\": \"minecraft:block.bubble_column.upwards_ambient\", \"sound_chance\": 0.5, \"min_kg\": 3}}");
        MaterialEffects.Effect b = m.effects().boiling();
        assertEquals(JsonParser.parseString("{\"type\": \"minecraft:dust\", \"scale\": 2}"), b.particle());
        assertEquals(Identifier.parse("minecraft:block.bubble_column.upwards_ambient"), b.sound());
        assertEquals(0.5f, b.soundChance());
        assertEquals(3f, b.minKg());
    }

    @Test
    void anUnknownEffectOrAMissingParticleIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> decode(", \"effects\": {\"glowing\": {\"particle\": \"minecraft:flame\"}}"));
        assertTrue(e.getMessage().contains("glowing"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> decode(", \"effects\": {\"boiling\": {}}"));
    }
}
