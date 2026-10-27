package com.gabdab.weboptimizer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Draggable HUD. Mouse and key state are polled through GLFW,
 * so the screen does not depend on the (frequently changing) input method signatures.
 */
public class WebScreen extends Screen {
    private static final int W = 220;
    private static final int H = 214;
    private static final int TITLE_H = 18;

    // relative y positions
    private static final int Y_WEB = 24;          // button
    private static final int Y_SWORD = 100;       // button
    private static final int Y_DRAIN = 124;       // button
    // sliders: label y (track = label + 12)
    private static final int[] SLIDER_Y = {46, 72, 146, 172};
    private static final int SLIDERS = 4;

    private boolean wasDown;
    private boolean shiftWasDown = true; // Right Shift is still held when the screen opens
    private boolean closeRequested = false;

    private boolean dragging = false;
    private int dragOffX, dragOffY;
    private int activeSlider = -1;

    public WebScreen() {
        super(Text.literal("WebOptimizer"));
        long window = MinecraftClient.getInstance().getWindow().getHandle();
        wasDown = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        if (closeRequested) {
            closeRequested = false;
            this.close();
        }
    }

    @Override
    public void removed() {
        WebConfig.save();
    }

    // ------------------------------------------------------------------ input (polled)

    private void pollInput(int mouseX, int mouseY) {
        long window = MinecraftClient.getInstance().getWindow().getHandle();

        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (down && !wasDown) onPress(mouseX, mouseY);
        if (down) onDrag(mouseX, mouseY);
        else {
            dragging = false;
            activeSlider = -1;
        }
        wasDown = down;

        boolean shift = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        if (shift && !shiftWasDown) closeRequested = true;
        shiftWasDown = shift;
    }

    private void onPress(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX, y = c.hudY;

        if (inside(mx, my, x, y, W, TITLE_H)) {
            dragging = true;
            dragOffX = mx - x;
            dragOffY = my - y;
            return;
        }
        if (inside(mx, my, x + 8, y + Y_WEB, W - 16, 18)) {
            c.enabled = !c.enabled;
            WebConfig.save();
            return;
        }
        if (inside(mx, my, x + 8, y + Y_SWORD, W - 16, 18)) {
            c.returnSword = !c.returnSword;
            WebConfig.save();
            return;
        }
        if (inside(mx, my, x + 8, y + Y_DRAIN, W - 16, 18)) {
            c.drainEnabled = !c.drainEnabled;
            WebConfig.save();
            return;
        }
        for (int i = 0; i < SLIDERS; i++) {
            if (inside(mx, my, x + 8, y + SLIDER_Y[i] + 7, W - 16, 16)) {
                activeSlider = i;
                updateSlider(i, mx);
                return;
            }
        }
    }

    private void onDrag(int mx, int my) {
        WebConfig c = WebConfig.INSTANCE;
        if (dragging) {
            c.hudX = clamp(mx - dragOffX, 0, Math.max(0, this.width - W));
            c.hudY = clamp(my - dragOffY, 0, Math.max(0, this.height - H));
        }
        if (activeSlider >= 0) updateSlider(activeSlider, mx);
    }

    private void updateSlider(int id, int mx) {
        WebConfig c = WebConfig.INSTANCE;
        double t = (mx - (c.hudX + 8)) / (double) (W - 16);
        t = Math.max(0.0, Math.min(1.0, t));
        switch (id) {
            case 0 -> c.smoothness = (int) Math.round(t * 1000.0);
            case 1 -> c.reach = Math.round((2.0 + t * 2.5) * 10.0) / 10.0;
            case 2 -> c.drainSmoothness = (int) Math.round(t * 1000.0);
            case 3 -> c.drainDelayMs = (int) Math.round(t * 50.0) * 10;
            default -> { }
        }
    }

    private static double sliderFraction(int id) {
        WebConfig c = WebConfig.INSTANCE;
        return switch (id) {
            case 0 -> c.smoothness / 1000.0;
            case 1 -> (c.reach - 2.0) / 2.5;
            case 2 -> c.drainSmoothness / 1000.0;
            case 3 -> c.drainDelayMs / 500.0;
            default -> 0.0;
        };
    }

    private static String sliderLabel(int id) {
        WebConfig c = WebConfig.INSTANCE;
        return switch (id) {
            case 0 -> "Плавність веба: " + c.smoothness + " / 1000";
            case 1 -> "Дальність веба: " + String.format(java.util.Locale.ROOT, "%.1f", c.reach) + " бл.";
            case 2 -> "Плавність Drain: " + c.drainSmoothness + " / 1000";
            case 3 -> "Затримка Drain: " + c.drainDelayMs + " мс";
            default -> "";
        };
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        pollInput(mouseX, mouseY);

        WebConfig c = WebConfig.INSTANCE;
        int x = c.hudX, y = c.hudY;

        // panel + title bar
        ctx.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF5BC0EB);
        ctx.fill(x, y, x + W, y + H, 0xF0141420);
        ctx.fill(x, y, x + W, y + TITLE_H, 0xFF22223A);
        drawCentered(ctx, "WebOptimizer", x + W / 2, y + 5, 0xFFFFFFFF);

        drawToggle(ctx, x + 8, y + Y_WEB, "Web: " + (c.enabled ? "ON" : "OFF"), c.enabled, mouseX, mouseY);
        drawSlider(ctx, x, y, 0);
        drawSlider(ctx, x, y, 1);
        drawToggle(ctx, x + 8, y + Y_SWORD, "Повертати меч: " + (c.returnSword ? "ON" : "OFF"), c.returnSword, mouseX, mouseY);

        drawToggle(ctx, x + 8, y + Y_DRAIN, "Auto Drain: " + (c.drainEnabled ? "ON" : "OFF"), c.drainEnabled, mouseX, mouseY);
        drawSlider(ctx, x, y, 2);
        drawSlider(ctx, x, y, 3);

        drawCentered(ctx, "By:GAB_DAB2025", x + W / 2, y + H - 14, 0xFF9AA0B5);
    }

    private void drawToggle(DrawContext ctx, int bx, int by, String text, boolean on, int mx, int my) {
        boolean hover = inside(mx, my, bx, by, W - 16, 18);
        int color = on ? (hover ? 0xFF3FA044 : 0xFF2E7D32) : (hover ? 0xFF3A3A55 : 0xFF2A2A40);
        ctx.fill(bx, by, bx + W - 16, by + 18, color);
        drawCentered(ctx, text, bx + (W - 16) / 2, by + 5, 0xFFFFFFFF);
    }

    private void drawSlider(DrawContext ctx, int hx, int hy, int id) {
        int labelY = hy + SLIDER_Y[id];
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(sliderLabel(id)), hx + 8, labelY, 0xFFDDDDDD);

        int trackX = hx + 8, trackY = labelY + 12, trackW = W - 16;
        ctx.fill(trackX, trackY, trackX + trackW, trackY + 6, 0xFF2A2A40);
        int filled = (int) Math.round(trackW * sliderFraction(id));
        ctx.fill(trackX, trackY, trackX + filled, trackY + 6, 0xFF5BC0EB);
        int knobX = clamp(trackX + filled - 2, trackX, trackX + trackW - 4);
        ctx.fill(knobX, trackY - 3, knobX + 4, trackY + 9, 0xFFFFFFFF);
    }

    private void drawCentered(DrawContext ctx, String text, int cx, int y, int color) {
        int w = this.textRenderer.getWidth(text);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal(text), cx - w / 2, y, color);
    }
}
