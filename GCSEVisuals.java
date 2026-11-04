package gcse.visuals;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class GCSEVisuals implements ClientModInitializer {
    static boolean autoSwap = true, elytraHelper = true, fireworkSaver = true, visuals = true;
    static KeyBinding kSwap, kElytra, kSaver, kVisuals;
    static int cooldown = 0, fwCooldown = 0, groundTicks = 0, fireworksUsed = 0;

    static KeyBinding reg(String id, int key) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(id, InputUtil.Type.KEYSYM, key, "key.category.gcse"));
    }

    @Override
    public void onInitializeClient() {
        kSwap = reg("key.gcse.autoswap", GLFW.GLFW_KEY_R);
        kElytra = reg("key.gcse.elytra", GLFW.GLFW_KEY_G);
        kSaver = reg("key.gcse.saver", GLFW.GLFW_KEY_H);
        kVisuals = reg("key.gcse.visuals", GLFW.GLFW_KEY_RIGHT_SHIFT);
        Watermark.init();
        AdminPanel.init();
        Extras.init();
        ClientTickEvents.END_CLIENT_TICK.register(GCSEVisuals::tick);
        HudRenderCallback.EVENT.register((ctx, tc) -> render(ctx));
    }

    // ---------- helpers ----------
    static int find(ClientPlayerEntity p, Predicate<ItemStack> f) {
        for (int i = 0; i < 36; i++) {
            if (f.test(p.getInventory().getStack(i))) return i < 9 ? 36 + i : i; // screen slot id
        }
        return -1;
    }

    static int countFireworks(ClientPlayerEntity p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isOf(Items.FIREWORK_ROCKET)) n += s.getCount();
        }
        if (p.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) n += p.getOffHandStack().getCount();
        return n;
    }

    static boolean isChestplate(ItemStack s) {
        Item it = s.getItem();
        return Registries.ITEM.getId(it).getPath().endsWith("_chestplate");
    }

    static void equipChest(MinecraftClient c, ClientPlayerEntity p, int slot) {
        int id = p.playerScreenHandler.syncId;
        var im = c.interactionManager;
        im.clickSlot(id, slot, 0, SlotActionType.PICKUP, p);
        im.clickSlot(id, 6, 0, SlotActionType.PICKUP, p);
        im.clickSlot(id, slot, 0, SlotActionType.PICKUP, p);
    }

    static boolean useFirework(MinecraftClient c, ClientPlayerEntity p) {
        int prev = p.getInventory().selectedSlot;
        int hot = -1;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { hot = i; break; }
        }
        if (p.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            c.interactionManager.interactItem(p, Hand.OFF_HAND);
            return true;
        }
        if (hot == -1) return false;
        p.getInventory().selectedSlot = hot;
        c.interactionManager.interactItem(p, Hand.MAIN_HAND);
        p.getInventory().selectedSlot = prev;
        return true;
    }

    // ---------- logic ----------
    static void tick(MinecraftClient c) {
        while (kSwap.wasPressed()) autoSwap = !autoSwap;
        while (kElytra.wasPressed()) elytraHelper = !elytraHelper;
        while (kSaver.wasPressed()) fireworkSaver = !fireworkSaver;
        while (kVisuals.wasPressed()) visuals = !visuals;

        ClientPlayerEntity p = c.player;
        if (p == null || c.interactionManager == null || c.currentScreen != null) return;
        if (fwCooldown > 0) fwCooldown--;
        if (cooldown > 0) { cooldown--; return; }

        // Auto swap: totem into offhand when HP is low
        if (autoSwap && p.getHealth() <= 10f && !p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            int s = find(p, st -> st.isOf(Items.TOTEM_OF_UNDYING));
            if (s != -1) {
                c.interactionManager.clickSlot(p.playerScreenHandler.syncId, s, 40, SlotActionType.SWAP, p);
                cooldown = 3;
                return;
            }
        }

        if (!elytraHelper) return;
        boolean hasElytraOn = p.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        groundTicks = p.isOnGround() ? groundTicks + 1 : 0;

        // Auto equip elytra when falling
        if (!hasElytraOn && !p.isOnGround() && p.fallDistance > 4 && !p.isTouchingWater() && !p.getAbilities().flying) {
            int s = find(p, st -> st.isOf(Items.ELYTRA) && st.getDamage() < st.getMaxDamage() - 1);
            if (s != -1) { equipChest(c, p, s); cooldown = 5; return; }
        }

        // Auto start gliding
        if (hasElytraOn && !p.isFallFlying() && !p.isOnGround() && p.getVelocity().y < -0.1 && !p.isTouchingWater()) {
            p.networkHandler.sendPacket(new ClientCommandC2SPacket(p, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
        }

        // Swap back to chestplate after landing
        if (hasElytraOn && groundTicks > 10) {
            int s = find(p, GCSEVisuals::isChestplate);
            if (s != -1) { equipChest(c, p, s); cooldown = 5; return; }
        }

        // Firework saver: only boost when actually slow, skip free dive speed
        if (p.isFallFlying() && fwCooldown == 0) {
            Vec3d v = p.getVelocity();
            double speed = v.length();
            double threshold = fireworkSaver ? 0.9 : 1.4;
            boolean freeDive = fireworkSaver && p.getPitch() > 20 && v.y < -0.4;
            if (speed < threshold && !freeDive && useFirework(c, p)) {
                fwCooldown = fireworkSaver ? 25 : 10;
                fireworksUsed++;
            }
        }
    }

    // ---------- visuals ----------
    static int rainbow(long t, int off) {
        return Color.HSBtoRGB(((t / 25 + off) % 360) / 360f, 0.55f, 1f) | 0xFF000000;
    }

    static void render(DrawContext ctx) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (!visuals || c.player == null || c.options.hudHidden) return;
        var tr = c.textRenderer;
        long t = System.currentTimeMillis();

        Watermark.render(ctx, c);
        Extras.hud(ctx, c);

        // bottom-left info lines
        List<String> info = new ArrayList<>();
        if (Watermark.bps) {
            Vec3d v = c.player.getVelocity();
            info.add(String.format("BPS: %.1f", Math.sqrt(v.x * v.x + v.z * v.z) * 20));
        }
        if (Watermark.fireworks) info.add("Fireworks: " + countFireworks(c.player) + " (used " + fireworksUsed + ")");
        int base = Watermark.bottomLeftTop(c.getWindow().getScaledHeight());
        for (int k = 0; k < info.size(); k++) {
            ctx.drawTextWithShadow(tr, info.get(k), 5, base - 10 * (info.size() - k), 0xFFFFFFFF);
        }

        if (!Watermark.moduleList) return;

        // array list
        List<String> on = new ArrayList<>();
        if (autoSwap) on.add("Auto Swap");
        if (elytraHelper) on.add("Elytra Helper");
        if (fireworkSaver) on.add("Firework Saver");
        if (AdminPanel.fly) on.add("Fly");
        if (AdminPanel.trigger) on.add("Triggerbot");
        if (AdminPanel.xray) on.add("Xray");
        if (AdminPanel.killaura) on.add("Killaura");
        if (AdminPanel.speed) on.add("Speed");
        Extras.addActive(on);
        on.sort((a, b) -> tr.getWidth(b) - tr.getWidth(a));
        int sw = c.getWindow().getScaledWidth();
        int y = Watermark.topRightBottom(), i = 0;
        for (String s : on) {
            int tw = tr.getWidth(s);
            ctx.fill(sw - tw - 8, y, sw - 2, y + 11, 0x90000000);
            ctx.fill(sw - 2, y, sw, y + 11, rainbow(t, i * 40));
            ctx.drawTextWithShadow(tr, s, sw - tw - 5, y + 2, rainbow(t, i * 40));
            y += 11; i++;
        }
    }
}
