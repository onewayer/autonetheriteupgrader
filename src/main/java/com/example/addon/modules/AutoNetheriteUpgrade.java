package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.SmithingScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;

public class AutoNetheriteUpgrade extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("action-delay")
        .description("Delay between actions in milliseconds.")
        .defaultValue(250)
        .range(0, 1000)
        .sliderRange(0, 1000)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disables when no more items are found to upgrade.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Logs debug messages to chat.")
        .defaultValue(false)
        .build()
    );

    private enum State {
        CHECK,
        PLACE_TEMPLATE,
        PLACE_DIAMOND,
        VERIFY_DIAMOND,
        PLACE_INGOT,
        TAKE_OUTPUT
    }

    private State state = State.CHECK;
    private long lastActionTime = 0;
    private final List<Integer> diamondItemsToUpgrade = new ArrayList<>();

    public AutoNetheriteUpgrade() {
        super(AddonTemplate.CATEGORY, "auto-netherite-upgrade", "Automatically upgrades all diamond items to netherite.");
    }

    private void log(String message) {
        if (debug.get()) {
            ChatUtils.info("[Debug] " + message);
        }
    }

    @Override
    public void onActivate() {
        log("Module activated.");
        populateDiamondItemsList();
        state = State.CHECK;
    }

    @Override
    public void onDeactivate() {
        log("Module deactivated.");
        state = State.CHECK;
        lastActionTime = 0;
        diamondItemsToUpgrade.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!(mc.currentScreen instanceof SmithingScreen) || mc.player.currentScreenHandler == null) {
            if (!diamondItemsToUpgrade.isEmpty()) {
                diamondItemsToUpgrade.clear();
                state = State.CHECK;
            }
            return;
        }

        if (System.currentTimeMillis() - lastActionTime < actionDelay.get()) {
            return;
        }

        log("Current state: " + state.toString());
        switch (state) {
            case CHECK:          doCheck();          break;
            case PLACE_TEMPLATE: doPlaceTemplate();  break;
            case PLACE_DIAMOND:  doPlaceDiamond();   break;
            case VERIFY_DIAMOND: doVerifyDiamond();  break;
            case PLACE_INGOT:    doPlaceIngot();     break;
            case TAKE_OUTPUT:    doTakeOutput();     break;
        }

        lastActionTime = System.currentTimeMillis();
    }

    private void doCheck() {
        log("Checking screen state...");
        if (!mc.player.currentScreenHandler.getCursorStack().isEmpty()) {
            log("Cursor is not empty, waiting.");
            return;
        }

        ItemStack outputStack = mc.player.currentScreenHandler.getSlot(3).getStack();
        if (!outputStack.isEmpty() && isNetheriteItem(outputStack)) {
            log("Output has item, moving to TAKE_OUTPUT.");
            state = State.TAKE_OUTPUT;
            return;
        }

        if (diamondItemsToUpgrade.isEmpty()) {
            populateDiamondItemsList(); // one last check
            if (diamondItemsToUpgrade.isEmpty()) {
                if (autoDisable.get()) {
                    ChatUtils.info("No more diamond items to upgrade.");
                    toggle();
                }
                return;
            }
        }

        if (mc.player.currentScreenHandler.getSlot(0).getStack().isEmpty()) {
            if (!InvUtils.find(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE).found()) {
                if (autoDisable.get()) { ChatUtils.info("No more netherite templates."); toggle(); }
                return;
            }
            log("Template slot is empty, moving to PLACE_TEMPLATE.");
            state = State.PLACE_TEMPLATE;
            return;
        }

        if (mc.player.currentScreenHandler.getSlot(1).getStack().isEmpty()) {
            log("Diamond slot is empty, moving to PLACE_DIAMOND.");
            state = State.PLACE_DIAMOND;
            return;
        }

        if (mc.player.currentScreenHandler.getSlot(2).getStack().isEmpty()) {
            if (!InvUtils.find(Items.NETHERITE_INGOT).found()) {
                if (autoDisable.get()) { ChatUtils.info("No more netherite ingots."); toggle(); }
                return;
            }
            log("Ingot slot is empty, moving to PLACE_INGOT.");
            state = State.PLACE_INGOT;
            return;
        }

        log("All slots are full, waiting for result.");
    }

    private void doPlaceTemplate() {
        log("Attempting to quick move template...");
        int slot = InvUtils.find(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE).slot();
        if (slot == -1) {
            log("Template not found.");
            state = State.CHECK;
            return;
        }
        log("Quick moving template from inventory slot " + slot);
        quickMoveItem(slot);
        state = State.PLACE_DIAMOND;
    }

    private void doPlaceDiamond() {
        log("Attempting to quick move diamond item...");
        if (diamondItemsToUpgrade.isEmpty()) {
            log("Diamond item list is empty.");
            state = State.CHECK;
            return;
        }
        int slot = diamondItemsToUpgrade.get(0);
        log("Quick moving diamond item from inventory slot " + slot);
        quickMoveItem(slot);
        state = State.VERIFY_DIAMOND;
    }

    private void doVerifyDiamond() {
        log("Verifying diamond placement...");
        boolean isSlotEmpty = mc.player.currentScreenHandler.getSlot(1).getStack().isEmpty();

        if (!isSlotEmpty) {
            log("Verification successful.");
            state = State.PLACE_INGOT;
        } else {
            log("Verification failed, skipping item.");
            ChatUtils.warning("Failed to place diamond item, skipping.");
            if (!diamondItemsToUpgrade.isEmpty()) {
                diamondItemsToUpgrade.remove(0);
            }
            state = State.CHECK;
        }
    }

    private void doPlaceIngot() {
        log("Attempting to quick move ingot...");
        int slot = InvUtils.find(Items.NETHERITE_INGOT).slot();
        if (slot == -1) {
            log("Ingot not found.");
            state = State.CHECK;
            return;
        }
        log("Quick moving ingot from inventory slot " + slot);
        quickMoveItem(slot);
        state = State.CHECK;
    }

    private void doTakeOutput() {
        log("Attempting to take output...");
        if (!mc.player.currentScreenHandler.getSlot(3).getStack().isEmpty()) {
            moveItemFromOutputSlot(3);
            if (!diamondItemsToUpgrade.isEmpty()) {
                log("Took output. Removing item from list.");
                diamondItemsToUpgrade.remove(0);
            }
        }
        state = State.CHECK;
    }

    private void populateDiamondItemsList() {
        log("Populating diamond item list...");
        diamondItemsToUpgrade.clear();
        Item[] diamondItems = {
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.DIAMOND_SWORD, Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_HOE
        };
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            for (Item item : diamondItems) {
                if (stack.getItem() == item) {
                    diamondItemsToUpgrade.add(i);
                    break;
                }
            }
        }
        log("Found " + diamondItemsToUpgrade.size() + " diamond items.");
    }

    private boolean isNetheriteItem(ItemStack stack) {
        Item[] netheriteItems = {
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            Items.NETHERITE_SWORD, Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_SHOVEL, Items.NETHERITE_HOE
        };
        for (Item item : netheriteItems) {
            if (stack.getItem() == item) return true;
        }
        return false;
    }

    private void quickMoveItem(int inventorySlot) {
        if (mc.player.currentScreenHandler == null) return;
        int containerSlot = (inventorySlot < 9) ? inventorySlot + 31 : inventorySlot - 5;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, containerSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }

    private void moveItemFromOutputSlot(int outputSlot) {
        if (mc.player.currentScreenHandler == null) return;
        mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, outputSlot, 0, SlotActionType.QUICK_MOVE, mc.player);
    }
}
