package gcse.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fullbright, Zoom, Tape Mouse (auto clicker), Waypoints (marks), coordinates HUD. */
public class Extras {
    // ---- Fullbright / Zoom / Tape Mouse ----
    static boolean fullbright, fbApplied, tape, zooming, savedSmooth, heldPressed;
    static int savedFov = 70, tapeMode = 0, tapeInterval = 10, tapeTimer = 0;
    static final String[] MODES = {"Left Click", "Left Hold", "Right Click", "Right Hold"};
    static KeyBinding kFull, kZoom, kTape, kMode, kUp, kDown, kMark, kUnmark, kHat, kAtmo, kWeather;

    // ---- China Hat / Atmosphere ----
    static boolean chinaHat, clearWeather;
    static int atmoMode = 0;
    static final String[] ATMO = {"Off", "Noon", "Sunset", "Night", "Midnight", "Sunrise"};
    static final long[] ATMO_TIME = {-1, 6000, 12800, 14500, 18000, 23000};

    // ---- Marks ----
    record Mark(String world, int x, int y, int z, String name) {}
    static final List<Mark> marks = new ArrayList<>();
    static int markCounter = 0;
    static Path file;
    static String curKey = "";

    static KeyBinding reg(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, key, "key.category.gcse"));
    }

    static void init() {
        kFull = reg("key.gcse.fullbright", GLFW.GLFW_KEY_B);
        kZoom = reg("key.gcse.zoom", GLFW.GLFW_KEY_C);
        kTape = reg("key.gcse.tape", GLFW.GLFW_KEY_Z);
        kMode = reg("key.gcse.tapemode", GLFW.GLFW_KEY_X);
        kUp = reg("key.gcse.tapeup", GLFW.GLFW_KEY_PAGE_UP);
        kDown = reg("key.gcse.tapedown", GLFW.GLFW_KEY_PAGE_DOWN);
        kMark = reg("key.gcse.mark", GLFW.GLFW_KEY_V);
        kUnmark = reg("key.gcse.unmark", GLFW.GLFW_KEY_N);
        kHat = reg("key.gcse.hat", GLFW.GLFW_KEY_U);
        kAtmo = reg("key.gcse.atmo", GLFW.GLFW_KEY_I);
        kWeather = reg("key.gcse.weather", GLFW.GLFW_KEY_O);
        load();
        ClientTickEvents.END_CLIENT_TICK.register(Extras::tick);
        WorldRenderEvents.LAST.register(Extras::renderMarks);
        WorldRenderEvents.LAST.register(Extras::renderHat);
        ClientLifecycleEvents.CLIENT_STOPPING.register(Extras::restoreZoom);
    }

    // ---------------- tick ----------------
    static void tick(MinecraftClient c) {
        while (kFull.wasPressed()) fullbright = !fullbright;
        while (kTape.wasPressed()) { tape = !tape; if (!tape) releaseHold(c); }
        while (kMode.wasPressed()) { tapeMode = (tapeMode + 1) % 4; releaseHold(c); }
        while (kUp.wasPressed()) tapeInterval = Math.min(200, tapeInterval + 2);
        while (kDown.wasPressed()) tapeInterval = Math.max(2, tapeInterval - 2);
        while (kHat.wasPressed()) chinaHat = !chinaHat;
        while (kAtmo.wasPressed()) atmoMode = (atmoMode + 1) % ATMO.length;
        while (kWeather.wasPressed()) clearWeather = !clearWeather;

        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) { restoreZoom(c); return; }
        curKey = worldKey(c);

        // Fullbright (client-side night vision)
        if (fullbright) {
            if (!p.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 1000000, 0, false, false, false));
            }
            fbApplied = true;
        } else if (fbApplied) {
            p.removeStatusEffect(StatusEffects.NIGHT_VISION);
            fbApplied = false;
        }

        // Atmosphere (client-side time of day and weather)
        if (atmoMode > 0) {
            ((ClientWorld.Properties) c.world.getLevelProperties()).setTimeOfDay(ATMO_TIME[atmoMode]);
        }
        if (clearWeather) {
            c.world.setRainGradient(0f);
            c.world.setThunderGradient(0f);
        }

        // Zoom (hold key)
        boolean want = kZoom.isPressed() && c.currentScreen == null;
        if (want && !zooming) {
            savedFov = c.options.getFov().getValue();
            savedSmooth = c.options.smoothCameraEnabled;
            zooming = true;
        }
        if (want) {
            c.options.getFov().setValue(30);
            c.options.smoothCameraEnabled = true;
        } else if (zooming) {
            restoreZoom(c);
        }

        // Tape Mouse
        if (tape && c.currentScreen == null) {
            KeyBinding kb = tapeMode < 2 ? c.options.attackKey : c.options.useKey;
            if (tapeMode == 1 || tapeMode == 3) {
                kb.setPressed(true);
                heldPressed = true;
            } else if (++tapeTimer >= tapeInterval) {
                tapeTimer = 0;
                KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey()));
            }
        }

        // Marks
        while (kMark.wasPressed()) addMark(c, p);
        while (kUnmark.wasPressed()) removeLooked(p);
    }

    static void restoreZoom(MinecraftClient c) {
        if (!zooming) return;
        c.options.getFov().setValue(savedFov);
        c.options.smoothCameraEnabled = savedSmooth;
        zooming = false;
    }

    static void releaseHold(MinecraftClient c) {
        if (heldPressed) {
            c.options.attackKey.setPressed(false);
            c.options.useKey.setPressed(false);
            heldPressed = false;
        }
        tapeTimer = 0;
    }

    // ---------------- marks ----------------
    static String worldKey(MinecraftClient c) {
        String base;
        if (c.isInSingleplayer() && c.getServer() != null) {
            base = "sp:" + c.getServer().getSaveProperties().getLevelName();
        } else {
            ServerInfo si = c.getCurrentServerEntry();
            base = "mp:" + (si != null ? si.address : "unknown");
        }
        return base + "@" + c.world.getRegistryKey().getValue();
    }

    static void addMark(MinecraftClient c, ClientPlayerEntity p) {
        BlockPos pos = p.getBlockPos();
        HitResult h = c.crosshairTarget;
        if (h != null && h.getType() == HitResult.Type.BLOCK && h instanceof BlockHitResult bh) pos = bh.getBlockPos();
        String name = "Mark " + (++markCounter);
        marks.add(new Mark(curKey, pos.getX(), pos.getY(), pos.getZ(), name));
        save();
        p.sendMessage(Text.literal("Метка добавлена: " + name), true);
    }

    static void removeLooked(ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        Vec3d f = p.getRotationVec(1f);
        Mark best = null;
        double bd = 0.97;
        for (Mark m : marks) {
            if (!m.world().equals(curKey)) continue;
            Vec3d d = new Vec3d(m.x() + 0.5, m.y() + 0.5, m.z() + 0.5).subtract(eye);
            double len = d.length();
            if (len < 0.1) continue;
            double dot = d.multiply(1.0 / len).dotProduct(f);
            if (dot > bd) { bd = dot; best = m; }
        }
        if (best != null) {
            marks.remove(best);
            save();
            p.sendMessage(Text.literal("Метка удалена: " + best.name()), true);
        } else {
            p.sendMessage(Text.literal("Наведите прицел на метку"), true);
        }
    }

    static void load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("gcsevisuals-marks.tsv");
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] a = line.split("\t");
                    if (a.length >= 5) {
                        marks.add(new Mark(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]), a[4]));
                    }
                }
            }
        } catch (Exception ignored) { }
        markCounter = marks.size();
    }

    static void save() {
        try {
            List<String> l = new ArrayList<>();
            for (Mark m : marks) l.add(m.world() + "\t" + m.x() + "\t" + m.y() + "\t" + m.z() + "\t" + m.name());
            Files.write(file, l);
        } catch (Exception ignored) { }
    }

    // ---------------- rendering ----------------
    static void renderMarks(WorldRenderContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.world == null || marks.isEmpty() || ctx.matrixStack() == null) return;
        List<Mark> here = new ArrayList<>();
        for (Mark m : marks) if (m.world().equals(curKey)) here.add(m);
        if (here.isEmpty()) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f mat = ctx.matrixStack().peek().getPositionMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (Mark m : here) {
            float x0 = (float) (m.x() - cam.x) - 0.003f, y0 = (float) (m.y() - cam.y) - 0.003f, z0 = (float) (m.z() - cam.z) - 0.003f;
            AdminPanel.cube(b, mat, x0, y0, z0, x0 + 1.006f, y0 + 1.006f, z0 + 1.006f, 0x6600FFFF);
        }
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    static int rainbowA(long t, int off) {
        return (Color.HSBtoRGB(((t / 20 + off) % 360) / 360f, 0.6f, 1f) & 0xFFFFFF) | 0xC8000000;
    }

    static void renderHat(WorldRenderContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!chinaHat || c.world == null || c.player == null || ctx.matrixStack() == null) return;
        if (c.options.getPerspective().isFirstPerson()) return;
        ClientPlayerEntity p = c.player;
        float td = c.getRenderTickCounter().getTickDelta(true);
        Vec3d cam = ctx.camera().getPos();
        double px = MathHelper.lerp(td, p.prevX, p.getX()) - cam.x;
        double py = MathHelper.lerp(td, p.prevY, p.getY()) + p.getHeight() + 0.05 - cam.y;
        double pz = MathHelper.lerp(td, p.prevZ, p.getZ()) - cam.z;
        Matrix4f mat = ctx.matrixStack().peek().getPositionMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
        long t = System.currentTimeMillis();
        b.vertex(mat, (float) px, (float) (py + 0.38), (float) pz).color(rainbowA(t, 0));
        int seg = 40;
        double rad = 0.62;
        for (int i = 0; i <= seg; i++) {
            double a = i * 2 * Math.PI / seg;
            b.vertex(mat, (float) (px + Math.cos(a) * rad), (float) py, (float) (pz + Math.sin(a) * rad))
                    .color(rainbowA(t, i * 360 / seg));
        }
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    static void hud(DrawContext ctx, MinecraftClient c) {
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return;
        var tr = c.textRenderer;

        String dir = p.getHorizontalFacing().asString();
        dir = dir.substring(0, 1).toUpperCase() + dir.substring(1);
        if (Watermark.coords) {
            int y0 = Watermark.topLeftBottom();
            ctx.drawTextWithShadow(tr, String.format("XYZ: %.1f %.1f %.1f", p.getX(), p.getY(), p.getZ()), 5, y0, 0xFFFFFFFF);
            ctx.drawTextWithShadow(tr, "Facing: " + dir, 5, y0 + 10, 0xFFAAAAAA);
        }

        // mark labels projected on screen
        Camera cam = c.gameRenderer.getCamera();
        Vec3d cp = cam.getPos();
        double yaw = Math.toRadians(cam.getYaw()), pit = Math.toRadians(cam.getPitch());
        Vec3d f = new Vec3d(-Math.sin(yaw) * Math.cos(pit), -Math.sin(pit), Math.cos(yaw) * Math.cos(pit));
        Vec3d r = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
        Vec3d u = r.crossProduct(f);
        double W = c.getWindow().getScaledWidth(), H = c.getWindow().getScaledHeight();
        double t = Math.tan(Math.toRadians(c.options.getFov().getValue()) / 2);
        for (Mark m : marks) {
            if (!m.world().equals(curKey)) continue;
            Vec3d d = new Vec3d(m.x() + 0.5 - cp.x, m.y() + 0.5 - cp.y, m.z() + 0.5 - cp.z);
            double zf = d.dotProduct(f);
            if (zf < 0.5) continue;
            int sx = (int) (W / 2 + d.dotProduct(r) / (zf * t * (W / H)) * (W / 2));
            int sy = (int) (H / 2 - d.dotProduct(u) / (zf * t) * (H / 2));
            if (sx < 0 || sx > W || sy < 0 || sy > H) continue;
            String label = m.name() + " [" + (int) d.length() + "m]";
            int tw = tr.getWidth(label);
            ctx.fill(sx - tw / 2 - 2, sy - 2, sx + tw / 2 + 2, sy + 10, 0x90000000);
            ctx.drawTextWithShadow(tr, label, sx - tw / 2, sy, 0xFF55FFFF);
        }
    }

    static void addActive(List<String> on) {
        if (fullbright) on.add("Fullbright");
        if (zooming) on.add("Zoom");
        if (chinaHat) on.add("China Hat");
        if (atmoMode > 0) on.add("Atmosphere: " + ATMO[atmoMode]);
        if (clearWeather) on.add("Clear Weather");
        if (tape) on.add("Tape Mouse: " + MODES[tapeMode] + (tapeMode % 2 == 0 ? " " + tapeInterval + "t" : ""));
    }
}
