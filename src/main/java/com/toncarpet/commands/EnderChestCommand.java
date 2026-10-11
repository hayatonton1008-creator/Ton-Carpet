package com.toncarpet.commands;

import com.toncarpet.rules.CommandAccess;
import com.toncarpet.rules.TonCarpetSettings;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;

/**
 * Registers "/enderChest" (and the short alias "/ec"): opens your own ender
 * chest inventory without needing to find/place a real one.
 *
 * Only available while the "commandEnderChest" rule is true - see TonCarpetSettings.
 */
public class EnderChestCommand {

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("enderChest")
                            .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandEnderChest))
                            .executes(EnderChestCommand::execute)
            );
            // Short alias, shares the exact same logic.
            dispatcher.register(
                    Commands.literal("ec")
                            .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandEnderChest))
                            .executes(EnderChestCommand::execute)
            );
        });
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can use this command."));
            return 0;
        }

        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> ChestMenu.threeRows(containerId, inventory, player.getEnderChestInventory()),
                Component.translatable("container.enderchest")
        ));

        return 1;
    }
}
