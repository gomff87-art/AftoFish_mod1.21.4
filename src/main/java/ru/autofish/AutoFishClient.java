package ru.autofish;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.projectile.FishingBobberEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.*;

public class AutoFishClient implements ClientModInitializer {

    static double BAND_X0 = 0.36, BAND_X1 = 0.64;
    static double BAND_Y0 = 0.785, BAND_Y1 = 0.825;
    static int SCAN_EVERY = 2;
    static int CAST_DELAY = 30;
    static int CLICK_DELAY = 15;
    static double MARGIN_PX = 2;
    static double LEAD_TICKS = 2;
    static int MIN_ZONE_COLS = 4;

    static boolean enabled = false;
    static boolean debug = false;
    static boolean reelOnBite = false;

    private KeyBinding toggleKey;
    private int sinceCast = 0, cooldown = 0, clickCooldown = 0, scanCount = 0;
    private double prevMx = -1;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autofish.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.autofish"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("afish")
                .executes(ctx -> { toggle(MinecraftClient.getInstance()); return Command.SINGLE_SUCCESS; })
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    toggle(MinecraftClient.getInstance()); return Command.SINGLE_SUCCESS; }))
                .then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug; say("debug = " + debug); return Command.SINGLE_SUCCESS; }))
                .then(ClientCommandManager.literal("bite").executes(ctx -> {
                    reelOnBite = !reelOnBite; say("reelOnBite = " + reelOnBite); return Command.SINGLE_SUCCESS; }))
                .then(ClientCommandManager.literal("dump").executes(ctx -> {
                    dump(MinecraftClient.getInstance()); return Command.SINGLE_SUCCESS; }))
            ));
    }

    private void toggle(MinecraftClient c) {
        enabled = !enabled;
        sinceCast = 0; cooldown = 0; clickCooldown = 0; prevMx = -1;
        say("AutoFish: " + (enabled ? "ON" : "OFF"));
    }

    static void say(String s) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) c.player.sendMessage(Text.literal("[AutoFish] " + s), false);
    }

    private void use(MinecraftClient c) {
        c.interactionManager.interactItem(c.player, Hand.MAIN_HAND);
        c.player.swingHand(Hand.MAIN_HAND);
    }

    private void onTick(MinecraftClient c) {
        while (toggleKey.wasPressed()) toggle(c);
        if (!enabled || c.player == null || c.world == null || c.interactionManager == null) return;
        if (c.currentScreen != null) return;
        if (!c.player.getMainHandStack().isOf(Items.FISHING_ROD)) return;

        if (cooldown > 0) cooldown--;
        if (clickCooldown > 0) clickCooldown--;

        FishingBobberEntity hook = c.player.fishHook;
        if (hook == null) {
            sinceCast = 0; prevMx = -1;
            if (cooldown == 0) { use(c); cooldown = CAST_DELAY; }
            return;
        }
        sinceCast++;

        boolean barSeen = false;
        if (sinceCast > 10 && sinceCast % SCAN_EVERY == 0) {
            barSeen = scan(c);
        }

        if (reelOnBite && !barSeen && sinceCast > 30 && clickCooldown == 0
                && hook.isTouchingWater() && hook.getVelocity().y < -0.08) {
            use(c);
            clickCooldown = CLICK_DELAY;
            cooldown = CAST_DELAY;
        }
    }

    private static boolean isZone(int r, int g, int b) {
        boolean green = g > 150 && r < 150 && b < 130 && g > r + 30;
        boolean yellow = r > 200 && g > 170 && b < 100;
        return green || yellow;
    }

    private static boolean isMarker(int r, int g, int b) {
        boolean white = r > 225 && g > 225 && b > 225;
        boolean red = r > 200 && g < 90 && b < 90;
        return white || red;
    }

    private boolean scan(MinecraftClient c) {
        NativeImage img = ScreenshotRecorder.takeScreenshot(c.getFramebuffer());
        try {
            int w = img.getWidth(), h = img.getHeight();
            int x0 = (int) (w * BAND_X0), x1 = (int) (w * BAND_X1);
            int y0 = (int) (h * BAND_Y0), y1 = (int) (h * BAND_Y1);

            int zoneMin = Integer.MAX_VALUE, zoneMax = -1, zoneCols = 0;
            double markerSum = 0; int markerCols = 0;

            for (int x = x0; x < x1; x++) {
                boolean z = false, m = false;
                for (int y = y0; y < y1; y++) {
                    int p = img.getColorArgb(x, y);
                    int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                    if (isZone(r, g, b)) z = true;
                    else if (isMarker(r, g, b)) m = true;
                }
                if (z) { zoneCols++; zoneMin = Math.min(zoneMin, x); zoneMax = Math.max(zoneMax, x); }
                if (m) { markerCols++; markerSum += x; }
            }

            boolean haveZone = zoneCols >= MIN_ZONE_COLS;
            boolean haveMarker = markerCols > 0 && markerCols < 25;
            double mx = haveMarker ? markerSum / markerCols : -1;

            if (debug && (++scanCount % 10 == 0)) {
                say("zone=" + (haveZone ? zoneMin + ".." + zoneMax : "нет")
                        + " marker=" + (haveMarker ? String.format("%.0f", mx) : "нет")
                        + " img=" + w + "x" + h);
            }

            if (haveZone && haveMarker) {
                double vx = prevMx < 0 ? 0 : (mx - prevMx) / SCAN_EVERY;
                prevMx = mx;
                double target = mx + vx * LEAD_TICKS;
                if (clickCooldown == 0 && target >= zoneMin + MARGIN_PX && target <= zoneMax - MARGIN_PX) {
                    use(c);
                    clickCooldown = CLICK_DELAY;
                    if (debug) say("CLICK mx=" + (int) mx + " zone=" + zoneMin + ".." + zoneMax);
                }
            } else {
                prevMx = -1;
            }
            return haveZone;
        } finally {
            img.close();
        }
    }

    private void dump(MinecraftClient c) {
        NativeImage img = ScreenshotRecorder.takeScreenshot(c.getFramebuffer());
        try {
            int w = img.getWidth(), h = img.getHeight();
            Path out = c.runDirectory.toPath().resolve("autofish_dump.png");
            img.writeTo(out);
            say("Скрин сохранён: " + out);

            Map<String, Integer> hist = new HashMap<>();
            for (int x = (int) (w * BAND_X0); x < (int) (w * BAND_X1); x++)
                for (int y = (int) (h * BAND_Y0); y < (int) (h * BAND_Y1); y++) {
                    int p = img.getColorArgb(x, y);
                    int r = ((p >> 16) & 255) / 16 * 16, g = ((p >> 8) & 255) / 16 * 16, b = (p & 255) / 16 * 16;
                    hist.merge(r + "," + g + "," + b, 1, Integer::sum);
                }
            hist.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(8)
                .forEach(e -> say("RGB~" + e.getKey() + " x" + e.getValue()));
        } catch (Exception e) {
            say("Ошибка dump: " + e);
        } finally {
            img.close();
        }
    }
            }
