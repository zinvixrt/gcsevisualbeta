package gcse.visuals;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Fully customizable watermark. Open settings with M (rebindable). */
public class Watermark {
    // ---- settings ----
    static String text;
    static boolean fps, ping, time, name, coords, fireworks, bps, moduleList, bold, shadow, bg, accent;
    static int colorMode, color1, color2, bgAlpha, corner, offX, offY;
    static float scale;

    // ---- last drawn box (used to avoid overlaps) ----
    static int lastX, lastY, lastW, lastH;

    static KeyBinding kOpen;
    static Path file;
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    static void defaults() {
        text = "GCSE Visuals";
        fps = true; ping = true; coords = true; fireworks = true; bps = true; moduleList = true;
        shadow = true; bg = true; accent = true;
        time = false; name = false; bold = false;
        colorMode = 0; color1 = 0x55FFFF; color2 = 0xFF55FF;
        bgAlpha = 144; corner = 0; offX = 4; offY = 4;
        scale = 1.0f;
    }

    static void init() {
        defaults();
        file = FabricLoader.getInstance().getConfigDir().resolve("gcsevisuals-watermark.properties");
        load();
        kOpen = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.gcse.watermark", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.category.gcse"));
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            while (kOpen.wasPressed()) {
                if (c.currentScreen == null && c.player != null) c.setScreen(new WatermarkScreen());
            }
        });
    }

    // ---------------- save / load ----------------
    static void save() {
        try {
            Properties p = new Properties();
            p.setProperty("text", text);
            p.setProperty("fps", String.valueOf(fps));
            p.setProperty("ping", String.valueOf(ping));
            p.setProperty("time", String.valueOf(time));
            p.setProperty("name", String.valueOf(name));
            p.setProperty("coords", String.valueOf(coords));
            p.setProperty("fireworks", String.valueOf(fireworks));
            p.setProperty("bps", String.valueOf(bps));
            p.setProperty("moduleList", String.valueOf(moduleList));
            p.setProperty("bold", String.valueOf(bold));
            p.setProperty("shadow", String.valueOf(shadow));
            p.setProperty("bg", String.valueOf(bg));
            p.setProperty("accent", String.valueOf(accent));
            p.setProperty("colorMode", String.valueOf(colorMode));
            p.setProperty("color1", String.valueOf(color1));
            p.setProperty("color2", String.valueOf(color2));
            p.setProperty("bgAlpha", String.valueOf(bgAlpha));
            p.setProperty("corner", String.valueOf(corner));
            p.setProperty("offX", String.valueOf(offX));
            p.setProperty("offY", String.valueOf(offY));
            p.setProperty("scale", String.valueOf(scale));
            try (OutputStream out = Files.newOutputStream(file)) { p.store(out, "GCSE Watermark"); }
        } catch (Exception ignored) { }
    }

    static void load() {
        try {
            if (!Files.exists(file)) return;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(file)) { p.load(in); }
            text = p.getProperty("text", text);
            fps = b(p, "fps", fps); ping = b(p, "ping", ping); time = b(p, "time", time); name = b(p, "name", name);
            coords = b(p, "coords", coords); fireworks = b(p, "fireworks", fireworks); bps = b(p, "bps", bps);
            moduleList = b(p, "moduleList", moduleList); bold = b(p, "bold", bold); shadow = b(p, "shadow", shadow);
            bg = b(p, "bg", bg); accent = b(p, "accent", accent);
            colorMode = i(p, "colorMode", colorMode); color1 = i(p, "color1", color1); color2 = i(p, "color2", color2);
            bgAlpha = i(p, "bgAlpha", bgAlpha); corner = i(p, "corner", corner);
            offX = i(p, "offX", offX); offY = i(p, "offY", offY);
            scale = Float.parseFloat(p.getProperty("scale", String.valueOf(scale)));
        } catch (Exception ignored) { }
    }

    static boolean b(Properties p, String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d))); }
    static int i(Properties p, String k, int d) { return Integer.parseInt(p.getProperty(k, String.valueOf(d))); }

    // ---------------- colors ----------------
    static int lerp(int a, int b, float f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int colorAt(float pos, long t) {
        if (colorMode == 1) return 0xFF000000 | color1;
        if (colorMode == 2) {
            float f = (float) ((pos + t / 3000.0) % 1.0);
            return lerp(color1, color2, Math.abs(f * 2f - 1f));
        }
        return Color.HSBtoRGB((float) (((t / 25.0) + pos * 180) % 360) / 360f, 0.6f, 1f) | 0xFF000000;
    }

    // ---------------- layout helpers (avoid overlapping other HUD parts) ----------------
    static int topLeftBottom() { return (corner == 0 && lastW > 0) ? lastY + lastH + 4 : 4; }
    static int topRightBottom() { return (corner == 1 && lastW > 0) ? lastY + lastH + 4 : 4; }
    static int bottomLeftTop(int screenH) { return (corner == 2 && lastW > 0) ? lastY - 2 : screenH - 8; }

    static String pingStr(MinecraftClient c) {
        if (c.player == null) return "-";
        ClientPlayNetworkHandler h = c.getNetworkHandler();
        if (h == null) return "-";
        PlayerListEntry e = h.getPlayerListEntry(c.player.getUuid());
        return e == null ? "-" : String.valueOf(e.getLatency());
    }

    // ---------------- render ----------------
    static void render(DrawContext ctx, MinecraftClient c) {
        TextRenderer tr = c.textRenderer;
        long t = System.currentTimeMillis();
        Style st = bold ? Style.EMPTY.withBold(true) : Style.EMPTY;

        List<String> segs = new ArrayList<>();
        if (fps) segs.add("FPS: " + c.getCurrentFps());
        if (ping) segs.add("Ping: " + pingStr(c) + " ms");
        if (time) segs.add(LocalTime.now().format(FMT));
        if (name && c.player != null) segs.add(c.player.getName().getString());

        int[] cps = text.codePoints().toArray();
        String[] chars = new String[cps.length];
        int[] cw = new int[cps.length];
        int mainW = 0;
        for (int k = 0; k < cps.length; k++) {
            chars[k] = new String(Character.toChars(cps[k]));
            cw[k] = tr.getWidth(Text.literal(chars[k]).setStyle(st));
            mainW += cw[k];
        }
        int sepW = tr.getWidth(" | ");
        int content = mainW;
        for (String s : segs) content += (content > 0 ? sepW : 0) + tr.getWidth(s);
        if (content == 0) { lastW = 0; lastH = 0; return; }

        int w = content + 10, h = 13;
        float sw = w * scale, sh = h * scale;
        int W = c.getWindow().getScaledWidth(), H = c.getWindow().getScaledHeight();
        float x, y;
        switch (corner) {
            case 1 -> { x = W - sw - offX; y = offY; }
            case 2 -> { x = offX; y = H - sh - offY; }
            case 3 -> { x = W - sw - offX; y = H - sh - offY; }
            case 4 -> { x = (W - sw) / 2f + offX; y = offY; }
            default -> { x = offX; y = offY; }
        }

        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate(x, y, 0f);
        m.scale(scale, scale, 1f);
        if (bg) ctx.fill(0, 0, w, h, bgAlpha << 24);
        if (accent) {
            for (int px = 0; px < w; px += 2) ctx.fill(px, 0, Math.min(px + 2, w), 1, colorAt(px / (float) w, t));
        }
        int cx = 5;
        for (int k = 0; k < chars.length; k++) {
            float pos = mainW > 0 ? (cx - 5) / (float) mainW : 0f;
            ctx.drawText(tr, Text.literal(chars[k]).setStyle(st), cx, 3, colorAt(pos, t), shadow);
            cx += cw[k];
        }
        boolean first = mainW == 0;
        for (String s : segs) {
            if (!first) { ctx.drawText(tr, " | ", cx, 3, 0xFF888888, shadow); cx += sepW; }
            ctx.drawText(tr, s, cx, 3, 0xFFFFFFFF, shadow);
            cx += tr.getWidth(s);
            first = false;
        }
        m.pop();

        lastX = (int) x; lastY = (int) y; lastW = (int) sw; lastH = (int) sh;
    }
}
