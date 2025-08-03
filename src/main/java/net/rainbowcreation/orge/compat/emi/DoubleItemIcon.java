package net.rainbowcreation.orge.compat.emi;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.emi.emi.api.render.EmiRenderable;
import net.createmod.catnip.gui.element.GuiGameElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

public class DoubleItemIcon implements EmiRenderable {
    private final ItemStack primaryStack;
    private final ItemStack secondaryStack;
    private boolean unbatchable;

    public DoubleItemIcon(ItemStack primaryStack, ItemStack secondaryStack) {
        this.primaryStack = primaryStack;
        this.secondaryStack = secondaryStack;
    }

    public static DoubleItemIcon of(ItemLike first, ItemLike second) {
        return of(first.asItem().getDefaultInstance(), second.asItem().getDefaultInstance());
    }

    public static DoubleItemIcon of(ItemStack first, ItemStack second) {
        return new DoubleItemIcon(first, second);
    }

    @Override
    public void render(GuiGraphics graphics, int xOffset, int yOffset, float delta) {
        PoseStack matrixStack = graphics.pose();
        RenderSystem.enableDepthTest();
        matrixStack.pushPose(); // note: this -1 is specific to EMI
        matrixStack.translate(xOffset - 1, yOffset, 0);

        matrixStack.pushPose();
        matrixStack.translate(1, 1, 0);
        GuiGameElement.of(primaryStack)
                .render(graphics);
        matrixStack.popPose();

        matrixStack.pushPose();
        matrixStack.translate(10, 10, 100);
        matrixStack.scale(.5f, .5f, .5f);
        GuiGameElement.of(secondaryStack)
                .render(graphics);
        matrixStack.popPose();

        matrixStack.popPose();
    }
}
