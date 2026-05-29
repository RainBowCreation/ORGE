package net.rainbowcreation.orge.compat.emi;

import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiRenderable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.rainbowcreation.orge.AllBlocks;
import net.rainbowcreation.orge.AllItems;
import net.rainbowcreation.orge.AllRecipeTypes;
import net.rainbowcreation.orge.Orge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

public class OrgeEMI implements EmiPlugin {
    public static final Map<ResourceLocation, EmiRecipeCategory> ALL = new LinkedHashMap<>();

    /*
    public static final EmiRecipeCategory
            SOLID = register("solid", DoubleItemIcon.of(AllBlocks.DIRT.get(), AllItems.DIRT.get()));

     */

    @Override
    public void register(EmiRegistry registry) {
        ALL.forEach((id, category) -> registry.addCategory(category));
    }

    private static EmiRecipeCategory register(String name, EmiRenderable icon) {
        ResourceLocation id = Orge.asResource(name);
        EmiRecipeCategory category = new EmiRecipeCategory(id, icon);
        ALL.put(id, category);
        return category;
    }

    @SuppressWarnings("unchecked")
    private <T extends Recipe<?>> void addAll(EmiRegistry registry, AllRecipeTypes type, Function<T, EmiRecipe> constructor) {
        /*for (T recipe : (List<T>) registry.getRecipeManager().getAllRecipesFor(type.getType())) {
            registry.addRecipe(constructor.apply(recipe));
        }*/
    }

    @SuppressWarnings("unchecked")
    private <T extends Recipe<?>> void addAll(EmiRegistry registry, AllRecipeTypes type, EmiRecipeCategory category,
                                              BiFunction<EmiRecipeCategory, T, EmiRecipe> constructor) {
        /*for (T recipe : (List<T>) registry.getRecipeManager().getAllRecipesFor(type.getType())) {
            registry.addRecipe(constructor.apply(category, recipe));
        }*/
    }
}
