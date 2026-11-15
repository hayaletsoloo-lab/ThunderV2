package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.core.manager.client.ModuleManager;
import thunder.hack.setting.Setting;

import static thunder.hack.utility.math.MathUtility.random;

public class ElytraTarget extends Module {

    // ── Settings ────────────────────────────────────────────────────────────
    public final Setting<Float>   range        = new Setting<>("Range",        60f, 20f, 100f);
    public final Setting<Float>   leadMult     = new Setting<>("LeadMult",     1.2f, 0.5f, 3.0f);
    public final Setting<Float> delay = new Setting<>("Delay", 5f, 1f, 20f);

    // ── State ────────────────────────────────────────────────────────────────
    private long lastFireworkMs = 0L;

    public ElytraTarget() {
        super("ElytraTarget", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        lastFireworkMs = 0L;
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        // Sadece biz elytra'dayken çalış
        if (!mc.player.isFallFlying()) return;

        // Aura'nın hedefini al
        if (ModuleManager.aura == null || !ModuleManager.aura.isEnabled()) return;
        var target = ModuleManager.aura.target;
        if (!(target instanceof PlayerEntity)) return;

        // Range check
        if (mc.player.distanceTo(target) > range.getValue()) return;

        // Delay check (ms bazlı)
        long now = System.currentTimeMillis();
        if (now - lastFireworkMs < (long)(delay.getValue() * 100)) return;

        // Inventory'den fişek bul (silent — slot switch paketi gönderme)
        int fireworkSlot = findFireworkSlot();
        if (fireworkSlot == -1) return;

        // ── Predictive lead point ────────────────────────────────────────────
        // Firework hızı yaklaşık 1.6 blok/tick, travel time tahmin
        double dist        = mc.player.distanceTo(target);
        double travelTicks = dist / 1.6;
        Vec3d  vel         = target.getVelocity();

        // Hedefin bir sonraki N tick'teki pozisyonu (önüne geç)
        Vec3d leadPos = target.getEyePos().add(
            vel.x * travelTicks * leadMult.getValue(),
            vel.y * travelTicks * leadMult.getValue(),
            vel.z * travelTicks * leadMult.getValue()
        );

        // ── Rotation hesapla (lead point'e) ─────────────────────────────────
        Vec3d eyePos = mc.player.getEyePos();
        double dx = leadPos.x - eyePos.x;
        double dy = leadPos.y - eyePos.y;
        double dz = leadPos.z - eyePos.z;

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        pitch = MathHelper.clamp(pitch, -90f, 90f);

        // ── GCD fix (Polar mouse sensitivity pattern engeli) ─────────────────
        double sens  = mc.options.getMouseSensitivity().getValue();
        double f     = sens * 0.6 + 0.2;
        double gcd   = Math.pow(f, 3.0) * (8.0 / 3.0);
        float  safeYaw   = (float)(yaw   - (yaw   - mc.player.getYaw())   % gcd);
        float  safePitch = (float)(pitch - (pitch - mc.player.getPitch()) % gcd);

        // ── [POLAR BYPASS] Silent fişek ──────────────────────────────────────
        // UpdateSelectedSlot paketi gönderme — sadece PlayerInteractItem spoof
        // Hotbar slotu ise direkt MAIN_HAND, değilse SWAP ile geç + ateşle + geri al
        if (fireworkSlot < 9) {
            // Hotbar: slot paketini geciktir, önce interact at
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(fireworkSlot));
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, safeYaw, safePitch));
            // [POLAR BYPASS] Restore'u bir tick sonraki update'e bırak (slot velocity check engeli)
            final int prevSlot = mc.player.getInventory().selectedSlot;
            mc.execute(() -> mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(prevSlot)));
        } else {
            // Inventory slot: clickSlot SWAP → interact → geri al, CloseHandledScreen YOK
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                fireworkSlot,
                mc.player.getInventory().selectedSlot,
                net.minecraft.screen.slot.SlotActionType.SWAP,
                mc.player
            );
            sendSequencedPacket(id -> new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, id, safeYaw, safePitch));
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                fireworkSlot,
                mc.player.getInventory().selectedSlot,
                net.minecraft.screen.slot.SlotActionType.SWAP,
                mc.player
            );
            // CloseHandledScreen kasıtlı gönderilmiyor — Polar bu paketi flag'liyor
        }

        // [POLAR BYPASS] Timestamp kaydet
        lastFireworkMs = System.currentTimeMillis();
    }

    // Inventory'de fişek ara (hotbar öncelikli)
    private int findFireworkSlot() {
        // Önce hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof FireworkRocketItem)
                return i;
        }
        // Sonra inventory (9-35)
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getItem() instanceof FireworkRocketItem)
                return i;
        }
        return -1;
    }
}
