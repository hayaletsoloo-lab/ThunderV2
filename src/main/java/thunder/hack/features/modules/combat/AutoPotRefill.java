package thunder.hack.features.modules.combat;

import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SplashPotionItem;
import net.minecraft.screen.slot.SlotActionType;
import thunder.hack.events.impl.PlayerUpdateEvent;
import thunder.hack.features.modules.Module;
import thunder.hack.setting.Setting;

import static thunder.hack.utility.math.MathUtility.random;

public class AutoPotRefill extends Module {

    public AutoPotRefill() {
        super("AutoPotRefill", Category.COMBAT);
    }

    // Delay: kaç tick'te bir pot taşınsın
    public final Setting<Integer> delay = new Setting<>("Delay", 2, 1, 10);

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

        // Envanter açıldığı an ilk delay'i koy
        if (!inventoryWasOpen && inventoryOpen)
            refillDelay = (int) random(1, 3);
        inventoryWasOpen = inventoryOpen;

        if (!inventoryOpen) return;
        if (refillDelay-- > 0) return;

        // Hotbar'da (slot 0-8) boş slot bul
        for (int hotbarSlot = 0; hotbarSlot < 9; hotbarSlot++) {
            if (!mc.player.getInventory().getStack(hotbarSlot).isEmpty()) continue;

            // Inventory'de (slot 9-35) Instant Health II splash pot bul
            int invSlot = findSplashHealth2();
            if (invSlot == -1) return;

            // GUI slot mapping:
            // Hotbar slot N → GUI slot N (0-8)
            // Inventory slot N (9-35) → GUI slot N (9-35)
            // SWAP: clickSlot(syncId, guiSlot, hotbarIndex, SWAP)
            // guiSlot = inventory item slot, hotbarIndex = hotbar slot 0-8
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                invSlot,     // inventory GUI slot (9-35)
                hotbarSlot,  // hotbar index (0-8)
                SlotActionType.SWAP,
                mc.player
            );

            // [POLAR BYPASS] her swap arası delay
            refillDelay = (int) random(delay.getValue(), delay.getValue() + 1);
            return; // bir seferde tek swap
        }
    }

    // Inventory'de (player inventory index 9-35) Instant Health II splash pot bul
    // GUI slot index olarak döner (9-35 arası, mapping 1:1)
    private int findSplashHealth2() {
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof SplashPotionItem)) continue;

            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value() == StatusEffects.INSTANT_HEALTH.value()
                        && effect.getAmplifier() >= 1)
                    return i; // GUI slot = player inventory index (9-35), offset YOK
            }
        }
        return -1;
    }
}
