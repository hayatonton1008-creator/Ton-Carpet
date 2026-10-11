package com.toncarpet.mixin;

import com.toncarpet.rules.CommandAccess;
import carpet.commands.PlayerCommand;
import carpet.patches.EntityPlayerMPFake;
import com.toncarpet.build.BuildManager;
import com.toncarpet.mine.MiningJob;
import com.toncarpet.mine.MiningManager;
import com.toncarpet.commands.CoordinateSuggestions;
import com.toncarpet.rules.TonCarpetSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 本家 Carpet Mod (fabric-carpet) の PlayerCommand は、外部Modが
 * サブコマンドを追加登録できるAPIを提供していない。
 * そこで PlayerCommand.register(...) の実行が終わった直後(TAIL)に割り込み、
 * 出来上がったコマンドツリーに直接 "mine" を子ノードとして追加する。
 *
 * これにより
 *   /player <名前> mine <x1> <y1> <z1> <x2> <y2> <z2>
 * が「本物の /player コマンドの一部」として動くようになる。
 */
@Mixin(PlayerCommand.class)
public class PlayerCommandMineMixin {

    // サーバーが一度に壊してよいブロック数の上限(暴走・ラグ対策)
    private static final int MAX_BLOCKS = 200_000;

    // 実際の register() は register(CommandDispatcher<CommandSourceStack>, CommandBuildContext) の2引数。
    // Mixinの@Injectハンドラは、対象メソッドの引数をそのまま(CallbackInfoの前に)受け取る必要がある。
    @Inject(method = "register", at = @At("TAIL"))
    private static void toncarpet$addMineSubcommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, CallbackInfo ci) {
        // 1. 既に登録済みの "/player" ノードを取得
        CommandNode<CommandSourceStack> playerRoot = dispatcher.getRoot().getChild("player");
        if (playerRoot == null) {
            // Carpet側の実装が変わってノード名が変わった場合はここで諦める(エラーにはしない)
            return;
        }

        // 2. "/player <名前>" の <名前> 引数ノードを探す
        //    (argument名がバージョンによって変わっても壊れないよう、型だけで判定する)
        CommandNode<CommandSourceStack> playerNameNode = null;
        for (CommandNode<CommandSourceStack> child : playerRoot.getChildren()) {
            if (child instanceof ArgumentCommandNode) {
                playerNameNode = child;
                break;
            }
        }
        if (playerNameNode == null) {
            return;
        }

        // 3. "mine" サブコマンドを組み立てて、<名前> ノードの子として追加する
        //    /player <名前> mine <x1> <y1> <z1> <x2> <y2> <z2>
        //    各座標は、コマンド実行者が見ているブロックの座標をサジェスト(補完候補)として出す
        //    (x の位置では「x y z」をまとめた候補と x だけの候補、y の位置では「y z」と y だけ、z の位置では z だけ)
        playerNameNode.addChild(
                Commands.literal("mine")
                        .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandPlayerActionmine))
                        .then(Commands.argument("x1", IntegerArgumentType.integer())
                                .suggests(CoordinateSuggestions.lookedAt(0))
                                .then(Commands.argument("y1", IntegerArgumentType.integer())
                                        .suggests(CoordinateSuggestions.lookedAt(1))
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .suggests(CoordinateSuggestions.lookedAt(2))
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .suggests(CoordinateSuggestions.lookedAt(0))
                                                        .then(Commands.argument("y2", IntegerArgumentType.integer())
                                                                .suggests(CoordinateSuggestions.lookedAt(1))
                                                                .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                        .suggests(CoordinateSuggestions.lookedAt(2))
                                                                        .executes(context -> mine(context, 1))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .build()
        );
    }

    private static int mine(CommandContext<CommandSourceStack> context, int ticksPerBlock) {
        CommandSourceStack source = context.getSource();

        if (!CommandAccess.canUse(source, TonCarpetSettings.commandPlayerActionmine)) {
            source.sendFailure(Component.literal("commandPlayerActionmine ルールが無効になっているため使用できません"));
            return 0;
        }

        String playerName = findPlayerNameArgument(context);
        if (playerName == null) {
            source.sendFailure(Component.literal("対象プレイヤー名を取得できませんでした"));
            return 0;
        }

        ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            source.sendFailure(Component.literal("プレイヤー " + playerName + " が見つかりません(ログインしていますか?)"));
            return 0;
        }

        // Carpetの bot(/player <名前> spawn で出したプレイヤー)だけを動かせる。本物のプレイヤーを
        // 勝手に歩かせたり壊させたりできてしまわないようにする(ルールを全員に開放していても)
        if (!(target instanceof EntityPlayerMPFake)) {
            source.sendFailure(Component.literal(playerName + " は bot ではないため mine を使えません(/player <名前> spawn で出した bot だけに使えます)"));
            return 0;
        }

        int x1 = IntegerArgumentType.getInteger(context, "x1");
        int y1 = IntegerArgumentType.getInteger(context, "y1");
        int z1 = IntegerArgumentType.getInteger(context, "z1");
        int x2 = IntegerArgumentType.getInteger(context, "x2");
        int y2 = IntegerArgumentType.getInteger(context, "y2");
        int z2 = IntegerArgumentType.getInteger(context, "z2");

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_BLOCKS) {
            source.sendFailure(Component.literal(
                    "範囲が大きすぎます(" + volume + "ブロック)。" + MAX_BLOCKS + "ブロック以下にしてください"));
            return 0;
        }

        // 空気ブロック・液体ブロックはキューには入れない
        //   - 空気: 壊す意味がない
        //   - 液体: 硬さが無く「採掘」できない(道具では壊れない)ので、
        //           代わりにMiningJob側で層(Y)が変わるタイミングでブロックにより埋める
        // Y座標が高い(上にある)層から先に採掘する(層を掘り下げる方針は維持)。
        // 各層の中は、単純にbotへの距離でソートするのではなく、行ごとに向きを交互に反転させる
        // 「蛇行(サーペンタイン)」パターンで並べる。距離ソートだと「2番目に近いブロック」が
        // 1番目から遠く離れていて後戻りする、ということが起こり得るが、この並べ方なら
        // 各層を後戻りなく一筆書きで掃くように進める(お掃除ロボットの経路と同じ考え方)。
        // 各層の掃き始めの位置は、最初の層ではbotの現在位置、それ以降の層では「直前の層で最後に
        // 掘ったブロック」に合わせる(層が変わるたびに範囲を横断して元の側へ戻る無駄な移動を無くす)。
        BlockPos cursor = target.blockPosition();
        List<BlockPos> positions = new ArrayList<>();
        for (int y = maxY; y >= minY; y--) {
            Map<Integer, List<BlockPos>> byZ = new TreeMap<>();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (target.level().isEmptyBlock(pos)) {
                        continue;
                    }
                    if (!target.level().getBlockState(pos).getFluidState().isEmpty()) {
                        continue;
                    }
                    byZ.computeIfAbsent(z, k -> new ArrayList<>()).add(pos);
                }
            }
            if (byZ.isEmpty()) {
                continue;
            }

            // このレイヤーをどちら側のZ行から掃き始めるかを、botに近い方に合わせる
            List<Integer> zOrder = new ArrayList<>(byZ.keySet()); // TreeMapなので既に昇順
            int minZKey = zOrder.get(0);
            int maxZKey = zOrder.get(zOrder.size() - 1);
            boolean startFromMinZ = Math.abs(cursor.getZ() - minZKey) <= Math.abs(cursor.getZ() - maxZKey);
            if (!startFromMinZ) {
                Collections.reverse(zOrder);
            }

            // 最初の行のX方向の向きも、現在位置に近い側から始めるようにする(行ごとの実際のX範囲で判定する)
            boolean ascendingX = true;
            boolean firstRow = true;
            for (int z : zOrder) {
                List<BlockPos> row = byZ.get(z);
                row.sort(Comparator.comparingInt(BlockPos::getX));
                if (firstRow) {
                    int rowMinX = row.get(0).getX();
                    int rowMaxX = row.get(row.size() - 1).getX();
                    ascendingX = Math.abs(cursor.getX() - rowMinX) <= Math.abs(cursor.getX() - rowMaxX);
                    firstRow = false;
                }
                if (!ascendingX) {
                    Collections.reverse(row);
                }
                positions.addAll(row);
                ascendingX = !ascendingX; // 次の行は逆向きに掃く(蛇行)
            }

            // 次の層は、この層で最後に掘ったブロックの近くから掃き始める
            cursor = positions.get(positions.size() - 1);
        }

        if (positions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("指定範囲に壊せるブロックがありませんでした"), true);
            return 0;
        }

        // 同じbotが別のmine/buildを実行中なら、それを止めて、今回のmineに切り替える
        // (2つのジョブが同じbotを同時に動かして、お互いの邪魔をしないようにする)
        MiningManager.cancelJobsFor(target.getUUID());
        BuildManager.cancelJobsFor(target.getUUID());

        MiningJob job = new MiningJob(target, source, positions, ticksPerBlock, minX, maxX, minY, maxY, minZ, maxZ);
        MiningManager.submit(job);

        double seconds = positions.size() * ticksPerBlock / 20.0;
        source.sendSuccess(() -> Component.literal(String.format(
                "%s が %d個のブロックの採掘を開始しました(約%.1f秒予定)",
                playerName, positions.size(), seconds)), true);

        return positions.size();
    }

    /**
     * "/player <名前> mine ..." の <名前> にあたる引数の値を、
     * 引数名に依存せず(型だけを頼りに)取得する。
     * コマンドツリー上で最初に出てくる引数ノードが名前引数であるという前提。
     */
    private static String findPlayerNameArgument(CommandContext<CommandSourceStack> context) {
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof ArgumentCommandNode) {
                String argName = parsed.getNode().getName();
                try {
                    return com.mojang.brigadier.arguments.StringArgumentType.getString(context, argName);
                } catch (IllegalArgumentException e) {
                    // このノードが文字列引数でなかった場合はスキップして次を探す
                }
            }
        }
        return null;
    }
}
