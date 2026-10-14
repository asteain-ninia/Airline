package com.asteain.airline.client;

import com.asteain.airline.Airline;
import com.asteain.airline.flight.FuelMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** The fuel store's window, drawn as Minecraft draws a one-row chest. Client only. */
public final class FuelScreen extends AbstractContainerScreen<FuelMenu> {
    private static final ResourceLocation CHEST = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 1;

    public FuelScreen(FuelMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageHeight = 114 + ROWS * 18;
        inventoryLabelY = imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        final int x = (width - imageWidth) / 2, y = (height - imageHeight) / 2;
        graphics.blit(CHEST, x, y, 0, 0, imageWidth, ROWS * 18 + 17);
        graphics.blit(CHEST, x, y + ROWS * 18 + 17, 0, 126, imageWidth, 96);
    }

    /** Hooked up on the client only (see {@link Airline}). */
    public static void register(RegisterMenuScreensEvent event) {
        event.register(Airline.FUEL_MENU.get(), FuelScreen::new);
    }
}
