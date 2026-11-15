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
    public final Setting<Float>   minRange     = new Setting<>("MinRange",     20f, 1f,  100f);
    public final Setting<Float>   maxRange     = new Setting<>("MaxRange",     100f, 1f, 100f);
    public final Setting<Float>   leadMult     = new Setting<>("LeadMult",     0.7f, 0.1f, 3.0f);
    public final Setting<Float>   delay        = new Setting<>("Delay", 5f, 1f, 20f);
    public final Setting<Rotations> rotations  = new Setting<>("Rotations", Rotations.Sharp);
    public final Setting<Float>   smoothFactor = new Setting<>("SmoothFactor", 0.35f, 0.05f, 1.0f, v -> rotations.is(Rotations.Smooth));

    private enum Rotations { Sharp, Smooth }

    // ── State ────────────────────────────────────────────────────────────────
    private long lastFireworkMs = 0L;
    private float smoothYaw   = 0f;
    private float smoothPitch = 0f;

    // Velocity smooth için son 5 tick buffer
    private static final int VEL_SAMPLES = 5;
    private final Vec3d[] velHistory = new Vec3d[VEL_SAMPLES];
    private int velIndex = 0;

    public ElytraTarget() {
        super("ElytraTarget", Category.MOVEMENT);
    }

    @Override
    public void onEnable() {
        lastFireworkMs = 0L;
        java.util.Arrays.fill(velHistory, Vec3d.ZERO);
        if (!fullNullCheck()) {
            smoothYaw   = mc.player.getYaw();
            smoothPitch = mc.player.getPitch();
        }
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        // Sadece biz elytra'dayken çalış
        if (!mc.player.isFallFlying()) return;

        // Aura'nın hedefini al
        if (ModuleManager.aura == null || !ModuleManager.aura.isEnabled()) return;
        var target = ModuleManager.aura.target;
        if (!(target instanceof PlayerEntity)) return;

        // Range check — min ve max arası hedef
        double dist = mc.player.distanceTo(target);
        if (dist < minRange.getValue() || dist > maxRange.getValue()) return;

        // Delay check (ms bazlı)
        long now = System.currentTimeMillis();
        if (now - lastFireworkMs < (long)(delay.getValue() * 100)) return;

        // Inventory'den fişek bul (silent — slot switch paketi gönderme)
        int fireworkSlot = findFireworkSlot();
        if (fireworkSlot == -1) return;

        // ── Velocity smooth (son 5 tick ortalaması) ──────────────────────────
        velHistory[velIndex % VEL_SAMPLES] = target.getVelocity();
        velIndex++;
        double svx = 0, svy = 0, svz = 0;
        int count = 0;
        for (Vec3d v : velHistory) {
            if (v == null) continue;
            svx += v.x; svy += v.y; svz += v.z;
            count++;
        }
        if (count == 0) return;
        Vec3d smoothVel = new Vec3d(svx / count, svy / count, svz / count);

        // ── Bounding box merkezi (eye pos yerine) ────────────────────────────
        Vec3d targetCenter = target.getBoundingBox().getCenter();

        // ── İteratif travel time simülasyonu ─────────────────────────────────
        // Fişeğin başlangıç hızı: bizim velocity + yön * 1.6
        Vec3d eyePos   = mc.player.getEyePos();
        Vec3d toTarget = targetCenter.subtract(eyePos).normalize();
        Vec3d fwVel    = mc.player.getVelocity();
        // Fişek başlangıç hızı: player velocity + yön * fişek boost hızı
        double fwSpeed = 1.6;
        Vec3d fwVelVec = new Vec3d(
            fwVel.x + toTarget.x * fwSpeed,
            fwVel.y + toTarget.y * fwSpeed,
            fwVel.z + toTarget.z * fwSpeed
        );

        // 3 iterasyon: lead tahminini kendi üzerine uygula
        Vec3d leadPos = targetCenter;
        for (int iter = 0; iter < 3; iter++) {
            double dx0 = leadPos.x - eyePos.x;
            double dy0 = leadPos.y - eyePos.y;
            double dz0 = leadPos.z - eyePos.z;
            double d0  = Math.sqrt(dx0*dx0 + dy0*dy0 + dz0*dz0);
            // Travel time: mesafe / fişeğin o yöndeki hız bileşeni
            double speed = fwVelVec.length();
            if (speed < 0.01) speed = fwSpeed;
            double travelTicks = d0 / speed;

            leadPos = targetCenter.add(
                smoothVel.x * travelTicks * leadMult.getValue(),
                smoothVel.y * travelTicks * leadMult.getValue(),
                smoothVel.z * travelTicks * leadMult.getValue()
            );
        }

        // ── Rotation hesapla (lead point'e) ─────────────────────────────────
        double dx = leadPos.x - eyePos.x;
        double dy = leadPos.y - eyePos.y;
        double dz = leadPos.z - eyePos.z;

        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        pitch = MathHelper.clamp(pitch, -90f, 90f);

        // ── GCD fix ──────────────────────────────────────────────────────────
        double sens = mc.options.getMouseSensitivity().getValue();
        double f    = sens * 0.6 + 0.2;
        double gcd  = Math.pow(f, 3.0) * (8.0 / 3.0);

        float targetYaw, targetPitch;
        if (rotations.is(Rotations.Smooth)) {
            smoothYaw   = smoothYaw   + (yaw   - smoothYaw)   * smoothFactor.getValue();
            smoothPitch = smoothPitch + (pitch - smoothPitch) * smoothFactor.getValue();
            targetYaw   = smoothYaw;
            targetPitch = smoothPitch;
        } else {
            targetYaw   = yaw;
            targetPitch = pitch;
        }

        float safeYaw   = (float)(targetYaw   - (targetYaw   - mc.player.getYaw())   % gcd);
        float safePitch = (float)(targetPitch - (targetPitch - mc.player.getPitch()) % gcd);

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
