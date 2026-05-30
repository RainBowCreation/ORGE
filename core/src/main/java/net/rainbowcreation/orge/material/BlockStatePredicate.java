package net.rainbowcreation.orge.material;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and matches blockstate-predicate binding keys of the form
 * {@code <id>[<prop><op><val>,...]} (engine-audit, §4). Two operators: {@code =} (string
 * equality) and {@code >} (numeric greater-than, for integer properties like {@code power}).
 * Pure and Minecraft-free; property values are read through a {@link PropertyView}.
 */
public final class BlockStatePredicate {

    public enum Op { EQ, GT }

    /** One blockstate requirement, e.g. {@code lit=true} or {@code power>0}. */
    public record Requirement(String property, Op op, String value) {
        public boolean matches(PropertyView props) {
            String actual = props.get(property);
            if (actual == null) {
                return false;
            }
            return switch (op) {
                case EQ -> actual.equals(value);
                case GT -> {
                    Integer a = parseInt(actual);
                    Integer b = parseInt(value);
                    yield a != null && b != null && a > b;
                }
            };
        }
    }

    /** A parsed key: the bare block/tag id plus its (possibly empty) requirement list. */
    public record Parsed(String id, List<Requirement> requirements) {}

    private BlockStatePredicate() {}

    /**
     * Splits {@code "id[a=b,c>d]"} into the id and its requirements. A key with no
     * {@code [...]} yields an empty requirement list (plain, predicate-free binding).
     *
     * @throws IllegalArgumentException on a malformed predicate (missing {@code ]}, empty
     *                                  clause, or no recognised operator in a clause)
     */
    public static Parsed parseKey(String key) {
        int open = key.indexOf('[');
        if (open < 0) {
            return new Parsed(key, List.of());
        }
        if (!key.endsWith("]")) {
            throw new IllegalArgumentException("predicate key missing ']': " + key);
        }
        String id = key.substring(0, open);
        String body = key.substring(open + 1, key.length() - 1);
        List<Requirement> reqs = new ArrayList<>();
        for (String clause : body.split(",")) {
            String c = clause.trim();
            if (c.isEmpty()) {
                throw new IllegalArgumentException("empty predicate clause in: " + key);
            }
            int gt = c.indexOf('>');
            int eq = c.indexOf('=');
            if (gt >= 0) {
                reqs.add(new Requirement(c.substring(0, gt).trim(), Op.GT, c.substring(gt + 1).trim()));
            } else if (eq >= 0) {
                reqs.add(new Requirement(c.substring(0, eq).trim(), Op.EQ, c.substring(eq + 1).trim()));
            } else {
                throw new IllegalArgumentException("predicate clause has no operator: " + c);
            }
        }
        return new Parsed(id, reqs);
    }

    /** True iff every requirement matches {@code props}. Empty list => always true. */
    public static boolean matchesAll(List<Requirement> requirements, PropertyView props) {
        for (Requirement r : requirements) {
            if (!r.matches(props)) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
