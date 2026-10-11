package com.toncarpet.commands;

import com.toncarpet.rules.CommandAccess;
import com.toncarpet.rules.TonCarpetSettings;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * "/hat": メインハンドに持っているアイテムを「1個だけ」頭に被る。
 *   - 今被っていたもの(ヘルメット等)は、持ち物に戻る(空きが無ければ足元に落とす)。
 *   - 手に何も持っていなければ、今被っているものを外して持ち物に戻す。
 *   - サバイバルでは被った分(1個)が手から減る。クリエイティブでは減らず、外したものは消える。
 *
 * 次の場合は被れない:
 *   - 今の頭の装備に「束縛の呪い」がかかっている(外せないため)
 *   - 持っているのが「不死のトーテム」
 *   - 持っているのが、中身の入ったシュルカーボックス
 *   - スペクテイターである
 *
 * "commandHat" ルールで使える人を制限する - TonCarpetSettings を参照。
 */
public class HatCommand {

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("hat")
                                // ルールで使える人を制限する。切り替えは /carpet commandHat <値>
                                .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandHat))
                                .executes(HatCommand::execute)
                )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("プレイヤーだけが /hat を使えます"));
            return 0;
        }
        if (player.isSpectator()) {
            source.sendFailure(Component.literal("スペクテイターでは /hat を使えません"));
            return 0;
        }

        ItemStack currentHat = player.getItemBySlot(EquipmentSlot.HEAD);
        ItemStack held = player.getMainHandItem();

        if (hasBindingCurse(currentHat)) {
            source.sendFailure(Component.literal("束縛の呪いがかかった装備を頭に着けているので、入れ替えられません"));
            return 0;
        }
        if (held.is(Items.TOTEM_OF_UNDYING)) {
            source.sendFailure(Component.literal("不死のトーテムは頭に被れません"));
            return 0;
        }
        if (isFilledShulkerBox(held)) {
            source.sendFailure(Component.literal("中身の入ったシュルカーボックスは頭に被れません"));
            return 0;
        }
        if (held.isEmpty() && currentHat.isEmpty()) {
            source.sendFailure(Component.literal("手にも頭にも何もありません"));
            return 0;
        }

        // 空の ItemStack(共有の EMPTY)の個数を書き換えてしまわないよう、手が空のときはコピーしない
        ItemStack newHat = ItemStack.EMPTY;
        if (!held.isEmpty()) {
            newHat = held.copy();
            newHat.setCount(1);
        }
        player.setItemSlot(EquipmentSlot.HEAD, newHat);

        if (!player.isCreative()) {
            if (!held.isEmpty()) {
                held.shrink(1);
            }
            if (!currentHat.isEmpty()) {
                giveBack(player, currentHat.copy());
            }
        }

        player.inventoryMenu.broadcastChanges();
        return 1;
    }

    /** 束縛の呪いがかかっているか。 */
    private static boolean hasBindingCurse(ItemStack stack) {
        for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
            if (enchantment.is(Enchantments.BINDING_CURSE)) {
                return true;
            }
        }
        return false;
    }

    /** 中身の入ったシュルカーボックスか。 */
    private static boolean isFilledShulkerBox(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof ShulkerBoxBlock)) {
            return false;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        return contents != null && !contents.equals(ItemContainerContents.EMPTY);
    }

    /** 外したアイテムを持ち物へ戻す。空きが無ければ、足元(頭の高さ)に落とす。 */
    private static void giveBack(ServerPlayer player, ItemStack stack) {
        if (player.getInventory().getFreeSlot() < 0) {
            ServerLevel level = player.level();
            ItemEntity dropped = new ItemEntity(level, player.getX(), player.getY() + 1.0, player.getZ(), stack);
            dropped.setDefaultPickUpDelay();
            level.addFreshEntity(dropped);
        } else {
            player.getInventory().add(stack);
        }
    }
}
