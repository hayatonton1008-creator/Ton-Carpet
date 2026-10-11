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
import net.minecraft.world.inventory.ContainerLevelAccess;

/**
 * Registers "/craftingtable" (and the short alias "/ct"): opens a crafting
 * table GUI without needing to find/place a real one.
 *
 * Only available while the "commandCraftingTable" rule is true - see TonCarpetSettings.
 */
public class CraftingTableCommand {

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("craftingtable")
                            .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandCraftingTable))
                            .executes(CraftingTableCommand::execute)
            );
            // Short alias, shares the exact same logic.
            dispatcher.register(
                    Commands.literal("ct")
                            .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandCraftingTable))
                            .executes(CraftingTableCommand::execute)
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
                (containerId, inventory, p) -> new VirtualCraftingMenu(
                        containerId,
                        inventory,
                        ContainerLevelAccess.create(player.level(), player.blockPosition())
                ),
                Component.translatable("container.crafting")
        ));

        return 1;
    }
}
