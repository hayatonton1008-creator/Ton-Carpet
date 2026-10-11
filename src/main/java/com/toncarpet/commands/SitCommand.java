package com.toncarpet.commands;

import com.toncarpet.rules.CommandAccess;
import com.toncarpet.rules.TonCarpetSettings;
import com.toncarpet.seats.SeatEntity;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * "/sit": 足元に透明な椅子(当たり判定の無いアーマースタンド)を出して、その上に座る。
 * スニーク(shift)で、普通の乗り物と同じように立ち上がる(椅子は降りた瞬間に自動で消える)。
 *
 * 使えるのは地面の上に立っているときだけ。空中・落下中は座れない。
 * 椅子の挙動(降りたときの処理・向きの追従・保存)は ArmorStandSeatMixin が担当する。
 *
 * "commandSit" ルールで使える人を制限する - TonCarpetSettings を参照。
 */
public class SitCommand {

    // 椅子を、プレイヤーの足元よりこれだけ下に出す。当たり判定の無い(高さ0の)椅子に座ると、
    // プレイヤーはこの分だけ椅子より上に乗るので、ちょうど地面に座った高さになる。
    // (降りるときは、同じ分だけ椅子を持ち上げてから降ろす)
    private static final double SEAT_Y_OFFSET = 0.16;

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("sit")
                                // ルールで使える人を制限する。切り替えは /carpet commandSit <値>
                                .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandSit))
                                .executes(SitCommand::execute)
                )
        );
    }

    private static int execute(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("プレイヤーだけが /sit を使えます"));
            return 0;
        }

        if (!player.onGround()) {
            source.sendFailure(Component.literal("地面の上に立っているときだけ座れます"));
            return 0;
        }

        if (player.isPassenger()) {
            source.sendFailure(Component.literal("すでに座っている(または何かに乗っている)ので座れません"));
            return 0;
        }

        ServerLevel level = player.level();

        ArmorStand seat = new ArmorStand(level, player.getX(), player.getY() - SEAT_Y_OFFSET, player.getZ());
        ((SeatEntity) seat).toncarpet$setSeat(true);
        seat.setInvulnerable(true);
        seat.setNoGravity(true);
        seat.setSilent(true);

        level.addFreshEntity(seat);
        player.setShiftKeyDown(false);
        // 騎乗できなかった場合(他のMODに拒否された等)は、椅子を残さず即座に消して失敗を伝える
        if (!player.startRiding(seat)) {
            seat.discard();
            source.sendFailure(Component.literal("ここには座れません"));
            return 0;
        }
        return 1;
    }
}
