package net.rainbowcreation.orge.material;

/**
 * Loader-agnostic read access to a block's blockstate property values, keyed by the
 * serialized property name. Returns the serialized value (e.g. {@code "true"}, {@code "7"})
 * or {@code null} if the block has no such property. Lets {@link MaterialBindings} match
 * blockstate predicates without importing Minecraft; the live seam adapts a {@code BlockState}.
 */
@FunctionalInterface
public interface PropertyView {
    String get(String property);

    /** A view with no properties — every lookup returns null. */
    PropertyView EMPTY = property -> null;
}
