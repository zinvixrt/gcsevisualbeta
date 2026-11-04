package gcse.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Admin panel. Works ONLY in singleplayer worlds (auto-disabled on servers). */
public class AdminPanel {
    static final String PASSWORD = "7641";
    static final double REACH = 7.0;
    static final int RADIUS = 16;

    static boolean unlocked, fly, trigger, xray, flyApplied, killaura, speed;
    static final double KA_RANGE = 6.0;
    static KeyBinding kOpen;
    static int tbCool, scanCool;
    static List<BlockPos> orePos = new ArrayList<>();
    static List<Integer> oreCol = new ArrayList<>();

    static void init() {
        kOpen = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.gcse.admin", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.category.gcse"));
        ClientTickEvents.END_CLIENT_TICK.register(AdminPanel::tick);
        WorldRenderEvents.LAST.register(AdminPanel::renderEsp);
    }

    static boolean sp(MinecraftClient c) {
        return c.isInSingleplayer() && c.getServer() != null;
    }

    // ---------------- logic ----------------
    static void tick(MinecraftClient c) {
        while (kOpen.wasPressed()) {
            if (c.currentScreen == null && c.player != null) c.setScreen(new AdminScreen());
        }
        ClientPlayerEntity p = c.player;
        if (p == null || c.world == null) return;
        if (!sp(c)) { fly = false; trigger = false; xray = false; killaura = false; speed = false; }

        // Fly
        if (fly != flyApplied || (fly && !p.getAbilities().allowFlying)) {
            setFly(c, p, fly);
            flyApplied = fly;
        }

        // Triggerbot
        if (tbCool > 0) tbCool--;
        if (trigger && c.currentScreen == null && tbCool == 0 && p.getAttackCooldownProgress(0.5f) >= 0.95f) {
            Vec3d start = p.getCameraPosVec(1f);
            Vec3d dir = p.getRotationVec(1f);
            Vec3d end = start.add(dir.multiply(REACH));
            Box box = p.getBoundingBox().stretch(dir.multiply(REACH)).expand(1.0);
            EntityHitResult hit = ProjectileUtil.raycast(p, start, end, box,
                    e -> !e.isSpectator() && e.canHit() && e instanceof LivingEntity && e instanceof Monster, REACH * REACH);
            if (hit != null) {
                BlockHitResult bh = c.world.raycast(new RaycastContext(start, end,
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, p));
                double blockDist = bh.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : bh.getPos().squaredDistanceTo(start);
                if (hit.getPos().squaredDistanceTo(start) < blockDist) {
                    int id = hit.getEntity().getId();
                    MinecraftServer s = c.getServer();
                    s.execute(() -> {
                        ServerPlayerEntity spl = s.getPlayerManager().getPlayer(p.getUuid());
                        if (spl == null) return;
                        Entity t = spl.getServerWorld().getEntityById(id);
                        if (t != null && t.isAlive()) spl.attack(t);
                    });
                    p.swingHand(Hand.MAIN_HAND);
                    p.resetLastAttackedTicks();
                    tbCool = 4;
                }
            }
        }

        // Killaura (hostile mobs only, singleplayer)
        if (killaura && c.currentScreen == null && tbCool == 0 && p.getAttackCooldownProgress(0.5f) >= 0.95f) {
            Entity best = null;
            double bestD = KA_RANGE * KA_RANGE;
            for (LivingEntity e : c.world.getEntitiesByClass(LivingEntity.class, p.getBoundingBox().expand(KA_RANGE),
                    e -> e instanceof Monster && e.isAlive())) {
                double d = p.squaredDistanceTo(e);
                if (d <= bestD) { bestD = d; best = e; }
            }
            if (best != null) {
                attackServerSide(c, p, best.getId());
                tbCool = 4;
            }
        }

        // Speed (ground speed boost, capped)
        if (speed && p.isOnGround() && !p.isFallFlying()) {
            Vec3d v = p.getVelocity();
            double h = Math.sqrt(v.x * v.x + v.z * v.z);
            if (h > 0.08 && h < 0.45) {
                double k = Math.min(1.25, 0.45 / h);
                p.setVelocity(v.x * k, v.y, v.z * k);
            }
        }

        // Xray scan
        if (xray) {
            if (--scanCool <= 0) { scanCool = 20; scan(c, p); }
        } else if (!orePos.isEmpty()) {
            orePos = new ArrayList<>();
            oreCol = new ArrayList<>();
        }
    }

    static void attackServerSide(MinecraftClient c, ClientPlayerEntity p, int id) {
        MinecraftServer s = c.getServer();
        if (s == null) return;
        s.execute(() -> {
            ServerPlayerEntity spl = s.getPlayerManager().getPlayer(p.getUuid());
            if (spl == null) return;
            Entity t = spl.getServerWorld().getEntityById(id);
            if (t != null && t.isAlive()) spl.attack(t);
        });
        p.swingHand(Hand.MAIN_HAND);
        p.resetLastAttackedTicks();
    }

    static void setFly(MinecraftClient c, ClientPlayerEntity p, boolean on) {
        boolean allow = on || p.isCreative() || p.isSpectator();
        p.getAbilities().allowFlying = allow;
        if (!allow) p.getAbilities().flying = false;
        MinecraftServer s = c.getServer();
        if (s != null) {
            s.execute(() -> {
                ServerPlayerEntity spl = s.getPlayerManager().getPlayer(p.getUuid());
                if (spl == null) return;
                spl.getAbilities().allowFlying = allow;
                if (!allow) spl.getAbilities().flying = false;
                spl.sendAbilitiesUpdate();
            });
        }
    }

    static int oreColor(BlockState st) {
        if (st.isAir()) return 0;
        if (st.isIn(BlockTags.DIAMOND_ORES)) return 0xAA00FFFF;
        if (st.isIn(BlockTags.EMERALD_ORES)) return 0xAA00FF00;
        if (st.isIn(BlockTags.GOLD_ORES)) return 0xAAFFD700;
        if (st.isIn(BlockTags.IRON_ORES)) return 0xAAFFC0A0;
        if (st.isIn(BlockTags.REDSTONE_ORES)) return 0xAAFF0000;
        if (st.isIn(BlockTags.LAPIS_ORES)) return 0xAA3050FF;
        if (st.isIn(BlockTags.COPPER_ORES)) return 0xAAE07030;
        if (st.isIn(BlockTags.COAL_ORES)) return 0xAA555555;
        if (st.isOf(Blocks.ANCIENT_DEBRIS)) return 0xAAFF8800;
        return 0;
    }

    static void scan(MinecraftClient c, ClientPlayerEntity p) {
        List<BlockPos> np = new ArrayList<>();
        List<Integer> nc = new ArrayList<>();
        BlockPos o = p.getBlockPos();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -RADIUS; x <= RADIUS; x++)
            for (int y = -RADIUS; y <= RADIUS; y++)
                for (int z = -RADIUS; z <= RADIUS; z++) {
                    m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                    int col = oreColor(c.world.getBlockState(m));
                    if (col != 0 && np.size() < 3000) { np.add(m.toImmutable()); nc.add(col); }
                }
        orePos = np;
        oreCol = nc;
    }

    // ---------------- xray-style ore highlight (through walls) ----------------
    static void renderEsp(WorldRenderContext ctx) {
        if (!xray || orePos.isEmpty() || ctx.matrixStack() == null) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f m = ctx.matrixStack().peek().getPositionMatrix();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder b = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < orePos.size(); i++) {
            BlockPos bp = orePos.get(i);
            float x0 = (float) (bp.getX() - cam.x) - 0.002f, y0 = (float) (bp.getY() - cam.y) - 0.002f, z0 = (float) (bp.getZ() - cam.z) - 0.002f;
            cube(b, m, x0, y0, z0, x0 + 1.004f, y0 + 1.004f, z0 + 1.004f, oreCol.get(i));
        }
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
    }

    static void v(BufferBuilder b, Matrix4f m, float x, float y, float z, int c) {
        b.vertex(m, x, y, z).color(c);
    }

    static void cube(BufferBuilder b, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int c) {
        v(b, m, x0, y0, z0, c); v(b, m, x1, y0, z0, c); v(b, m, x1, y0, z1, c); v(b, m, x0, y0, z1, c);
        v(b, m, x0, y1, z0, c); v(b, m, x0, y1, z1, c); v(b, m, x1, y1, z1, c); v(b, m, x1, y1, z0, c);
        v(b, m, x0, y0, z0, c); v(b, m, x0, y1, z0, c); v(b, m, x1, y1, z0, c); v(b, m, x1, y0, z0, c);
        v(b, m, x0, y0, z1, c); v(b, m, x1, y0, z1, c); v(b, m, x1, y1, z1, c); v(b, m, x0, y1, z1, c);
        v(b, m, x0, y0, z0, c); v(b, m, x0, y0, z1, c); v(b, m, x0, y1, z1, c); v(b, m, x0, y1, z0, c);
        v(b, m, x1, y0, z0, c); v(b, m, x1, y1, z0, c); v(b, m, x1, y1, z1, c); v(b, m, x1, y0, z1, c);
    }

    // ---------------- screen ----------------
    public static class AdminScreen extends Screen {
        TextFieldWidget pass;
        String msg = "";

        AdminScreen() { super(Text.literal("GCSE Admin")); }

        static Text label(String n, boolean on) {
            return Text.literal(n + ": " + (on ? "ВКЛ" : "ВЫКЛ"));
        }

        void toggle(int y, String name, Supplier<Boolean> get, Consumer<Boolean> set) {
            addDrawableChild(ButtonWidget.builder(label(name, get.get()), b -> {
                if (!sp(client)) { msg = "Работает только в одиночном мире"; return; }
                set.accept(!get.get());
                b.setMessage(label(name, get.get()));
            }).dimensions(width / 2 - 70, y, 140, 20).build());
        }

        @Override
        protected void init() {
            int cx = width / 2, cy = height / 2;
            if (!unlocked) {
                pass = new TextFieldWidget(textRenderer, cx - 60, cy - 8, 120, 20, Text.literal("Пароль"));
                pass.setMaxLength(16);
                addDrawableChild(pass);
                setInitialFocus(pass);
                addDrawableChild(ButtonWidget.builder(Text.literal("Войти"), b -> {
                    if (pass.getText().equals(PASSWORD)) { unlocked = true; msg = ""; clearAndInit(); }
                    else msg = "Неверный пароль";
                }).dimensions(cx - 40, cy + 18, 80, 20).build());
            } else {
                toggle(cy - 62, "Fly", () -> fly, v -> fly = v);
                toggle(cy - 38, "Triggerbot (7 блоков)", () -> trigger, v -> trigger = v);
                toggle(cy - 14, "Xray (руды)", () -> xray, v -> xray = v);
                toggle(cy + 10, "Killaura (6 блоков)", () -> killaura, v -> killaura = v);
                toggle(cy + 34, "Speed", () -> speed, v -> speed = v);
            }
        }

        @Override
        public void render(DrawContext ctx, int mx, int my, float delta) {
            super.render(ctx, mx, my, delta);
            ctx.drawCenteredTextWithShadow(textRenderer, "GCSE Admin Panel", width / 2, height / 2 - 90, 0xFFFFFFFF);
            if (!unlocked) ctx.drawCenteredTextWithShadow(textRenderer, "Введите пароль", width / 2, height / 2 - 22, 0xFFAAAAAA);
            if (!msg.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, msg, width / 2, height / 2 + 64, 0xFFFF5555);
        }

        @Override
        public boolean shouldPause() { return false; }
    }
}
