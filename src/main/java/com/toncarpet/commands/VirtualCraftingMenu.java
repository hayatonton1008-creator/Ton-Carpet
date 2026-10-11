package com.toncarpet.commands;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;

/**
 * A CraftingMenu that doesn't require a real crafting table block to exist
 * in the world. Vanilla's CraftingMenu.stillValid() checks that the block at
 * its ContainerLevelAccess position is actually Blocks.CRAFTING_TABLE, which
 * would fail instantly for a menu we opened purely via command (closing the
 * screen right away). We override just that check - recipe matching and
 * "give back leftover items on close" still work normally, since those use
 * the real Level from the access, not the block check.
 */
public class VirtualCraftingMenu extends CraftingMenu {

    public VirtualCraftingMenu(int containerId, Inventory playerInventory, ContainerLevelAccess access) {
        super(containerId, playerInventory, access);
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
