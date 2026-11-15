package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.screen.slot.SlotActionType;
import thunder.hack.events.impl.PacketEvent;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

import static thunder.hack.utility.math.MathUtility.random;

public class AutoPotRefill extends Module {

    public AutoPotRefill() {
        super("AutoPotRefill", Category.COMBAT);
    }

    public final Setting<Integer> hotbarSlotFrom = new Setting<>("SlotFrom", 1, 1, 9);
    public final Setting<Integer> hotbarSlotTo   = new Setting<>("SlotTo",   9, 1, 9);

    // [POLAR BYPASS] her refill arası delay
    private int refillDelay = 0;
    private boolean inventoryWasOpen = false;

    @Override
    public void onEnable() {
        refillDelay = 0;
        inventoryWasOpen = false;
    }

    @EventHandler
    public void onUpdate(PlayerUpdateEvent e) {
        if (fullNullCheck()) return;

        boolean inventoryOpen = mc.currentScreen instanceof InventoryScreen;

        // Envanter açıldığı an tetikle
        if (!inventoryWasOpen && inventoryOpen) {
            refillDelay = (int) random(1, 3); // [POLAR BYPASS] anlık değil, 1-3 tick delay
        }
        inventoryWasOpen = inventoryOpen;

        if (!inventoryOpen) return;
        if (refillDelay-- > 0) return;

        int from = hotbarSlotFrom.getValue() - 1; // 0-indexed
        int to   = hotbarSlotTo.getValue() - 1;

        for (int hotbarSlot = from; hotbarSlot <= to; hotbarSlot++) {
            ItemStack hotbarStack = mc.player.getInventory().getStack(hotbarSlot);
            if (!hotbarStack.isEmpty()) continue; // dolu slot, atla

            // Inventory'de Instant Health II splash pot bul (slot 9-35)
            int invSlot = findSplashHealth2(9, 36);
            if (invSlot == -1) break; // pot kalmadı

            // [POLAR BYPASS] SlotActionType.SWAP — CloseHandledScreen gönderme
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                invSlot,       // inventory slot (GUI index)
                hotbarSlot,    // hotbar index
                SlotActionType.SWAP,
                mc.player
            );

            // [POLAR BYPASS] her click arası random delay — uniform swap pattern Polar flag'liyor
            refillDelay = (int) random(1, 2);
            return; // bir tick'te tek swap
        }
    }

    // Instant Health II splash pot ara (inventory GUI slot index'i döner)
    private int findSplashHealth2(int start, int end) {
        for (int i = start; i < end; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof SplashPotionItem)) continue;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1) { // amplifier 1 = level II
                    return i + 9; // inventory slot → GUI slot index (hotbar offset)
                }
            }
        }
        return -1;
    }
}
