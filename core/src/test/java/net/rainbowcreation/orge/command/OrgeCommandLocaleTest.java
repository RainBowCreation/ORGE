package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrgeCommandLocaleTest {

    private final Locale original = Locale.getDefault();

    @AfterEach
    void restore() { Locale.setDefault(original); }

    @Test
    void cellReadoutUsesDotDecimalUnderCommaLocale() {
        Locale.setDefault(Locale.GERMANY); // comma decimal separator
        String line = CellReadout.line(Identifier.fromNamespaceAndPath("minecraft", "air"), 285.0f,
                List.of(new SectionView.Component(Identifier.fromNamespaceAndPath("orge", "air"), 1.225f)),
                LiveStatus.AMBIENT);
        assertEquals("M:air 285.0K O:air:1.225 B", line);
    }
}
