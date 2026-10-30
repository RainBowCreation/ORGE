package net.rainbowcreation.orge.command;

import net.minecraft.resources.Identifier;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The one-line cell readout shared by {@code /orge get} and {@code /orge get-live}:
 * {@code <block> <temp>K <material>:<kg> [<material>:<kg> ...] <status>}, e.g.
 * {@code M:sand 288.1K O:sand:1500 O:steam:0.005712 A}. Built for a small screen: {@code minecraft:}
 * prints as {@code M:}, {@code orge:} as {@code O:}, the status as one letter. A composition cell lists
 * every slot, heaviest first; masses print at 4 significant figures so a trace slot stays visible.
 */
final class CellReadout {

    private CellReadout() {}

    /**
     * The readout line. {@code tempK} NaN = no data (prints {@code -}); a component mass NaN prints the
     * material alone.
     */
    static String line(Identifier block, float tempK, List<SectionView.Component> components, LiveStatus status) {
        StringBuilder sb = new StringBuilder(id(block)).append(' ')
                .append(Float.isNaN(tempK) ? "-" : String.format(Locale.ROOT, "%.1fK", tempK));
        List<SectionView.Component> sorted = new ArrayList<>(components);
        sorted.sort(Comparator.comparingDouble((SectionView.Component c) -> c.massKg()).reversed());
        for (SectionView.Component c : sorted) {
            sb.append(' ').append(id(c.material()));
            if (!Float.isNaN(c.massKg())) {
                sb.append(':').append(new BigDecimal(c.massKg())
                        .round(new MathContext(4)).stripTrailingZeros().toPlainString());
            }
        }
        return sb.append(' ').append(status(status)).toString();
    }

    /** {@code minecraft:x} -> {@code M:x}, {@code orge:x} -> {@code O:x}, any other namespace in full. */
    static String id(Identifier id) {
        return switch (id.getNamespace()) {
            case "minecraft" -> "M:" + id.getPath();
            case "orge" -> "O:" + id.getPath();
            default -> id.toString();
        };
    }

    /** ACTIVE A, DORMANT D, UNLOADED U, AMBIENT B (the never-simulated baseline). */
    static String status(LiveStatus s) {
        return switch (s) {
            case ACTIVE -> "A";
            case DORMANT -> "D";
            case UNLOADED -> "U";
            case AMBIENT -> "B";
        };
    }
}
