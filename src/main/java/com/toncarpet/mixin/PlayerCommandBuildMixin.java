package com.toncarpet.mixin;

import com.toncarpet.rules.CommandAccess;
import carpet.commands.PlayerCommand;
import carpet.patches.EntityPlayerMPFake;
import com.toncarpet.build.BuildJob;
import com.toncarpet.build.BuildManager;
import com.toncarpet.build.LitematicaSchematicReader;
import com.toncarpet.build.LitematicaPlacements;
import com.toncarpet.mine.MiningManager;
import com.toncarpet.rules.TonCarpetSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * PlayerCommandMineMixinと全く同じ手法(TAIL-inject)で、
 *   /player <名前> build <ファイル名> [region <リージョン名>]
 * を本物の /player コマンドへ子ノードとして追加する。
 *
 * <ファイル名> は、Litematicaが読み込み中のスキーマティックのファイル名(フォルダの場所と拡張子は含めない)。
 * Litematicaが保存している「読み込み中の配置」(config/litematica/ のJSON)から、そのファイルの
 * 原点・回転・ミラー・子リージョンの状態をそのまま使って建築する。座標を入力する必要は無い。
 *   - region を省略した場合: Litematicaでオンになっている子リージョンを、ファイル内の並び順のまま
 *     「1つのリージョンを完全に終わらせてから次のリージョンへ」処理する
 *   - region を指定した場合: そのリージョンだけを建築する(Litematicaでオフでも、指定すれば建築する)
 * シングルプレイ(Litematicaと同じゲームフォルダを使える環境)でのみ使える。
 */
@Mixin(PlayerCommand.class)
public class PlayerCommandBuildMixin {

    // 一度のbuildで置いてよいブロック数の上限(暴走・ラグ対策。mineのMAX_BLOCKSと同じ考え方)
    private static final int MAX_BLOCKS = 200_000;
    // 「範囲内の余計なブロック」を探すためにスキャンするバウンディングボックスの体積上限
    private static final long MAX_VOLUME = 300_000L;

    @Inject(method = "register", at = @At("TAIL"))
    private static void toncarpet$addBuildSubcommand(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext commandBuildContext, CallbackInfo ci) {
        CommandNode<CommandSourceStack> playerRoot = dispatcher.getRoot().getChild("player");
        if (playerRoot == null) {
            return;
        }

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

        // /player <名前> build <ファイル名>
        // /player <名前> build <ファイル名> region <regionName>
        // <ファイル名> はLitematicaで読み込み中のスキーマティックのファイル名(タブ補完に出る)。
        // region は、ファイル名の次の階層に .executes() を持たせることで「省略可能」にしている。
        var regionNode = Commands.literal("region")
                .then(Commands.argument("regionName", StringArgumentType.string())
                        .suggests(PlayerCommandBuildMixin::suggestLitematicaRegions)
                        .executes(context -> buildFromLitematica(context,
                                StringArgumentType.getString(context, "file"),
                                StringArgumentType.getString(context, "regionName"))));

        var fileNode = Commands.argument("file", StringArgumentType.string())
                .suggests(PlayerCommandBuildMixin::suggestLitematicaFiles)
                .executes(context -> buildFromLitematica(context,
                        StringArgumentType.getString(context, "file"), null))
                .then(regionNode);

        playerNameNode.addChild(
                Commands.literal("build")
                        .requires(source -> CommandAccess.canUse(source, TonCarpetSettings.commandPlayerActionbuild))
                        .then(fileNode)
                        .build()
        );
    }

    /**
     * コマンドの文字列引数に、そのまま入力できる形へ直す。
     * Brigadierは、引用符で囲まない文字列を英数字と「_ - . +」までしか読まない(空白・日本語・"/" などで切れる)ので、
     * それ以外の文字を含むときは、"…" で囲み、中の " と \ は \ を付けて書く。
     */
    private static String quoteIfNeeded(String text) {
        boolean plain = !text.isEmpty();
        for (int i = 0; i < text.length() && plain; i++) {
            char c = text.charAt(i);
            plain = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '_' || c == '-' || c == '.' || c == '+';
        }
        if (plain) {
            return text;
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** build <ファイル名> region の補完: 対象の配置の子リージョン名を出す(Litematicaでオフのものは説明を付ける)。 */
    private static CompletableFuture<Suggestions> suggestLitematicaRegions(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            LitematicaPlacements.Loaded loaded = LitematicaPlacements.load(
                    context.getSource().getServer(), context.getSource().getLevel().dimension());
            if (loaded.placements().isEmpty()) {
                return builder.buildFuture();
            }
            LitematicaPlacements.Placement chosen = findPlacementByFileName(loaded,
                    StringArgumentType.getString(context, "file"));
            if (chosen == null) {
                return builder.buildFuture();
            }

            if (!chosen.subRegions().isEmpty()) {
                for (LitematicaPlacements.SubRegion sub : chosen.subRegions()) {
                    if (sub.name().isEmpty()) {
                        continue;
                    }
                    if (sub.enabled()) {
                        builder.suggest(quoteIfNeeded(sub.name()));
                    } else {
                        builder.suggest(quoteIfNeeded(sub.name()), Component.literal("Litematicaではオフ"));
                    }
                }
            } else if (chosen.schematic() != null && Files.isRegularFile(chosen.schematic())) {
                // 配置情報に子リージョンの一覧が無いときは、ファイルの中身から読む
                for (String name : LitematicaSchematicReader.listRegionNames(chosen.schematic())) {
                    builder.suggest(quoteIfNeeded(name));
                }
            }
        } catch (IOException ignored) {
            // 読めないときは候補を出さない
        }
        return builder.buildFuture();
    }

    /** build <ファイル名> の補完: Litematicaで読み込み中のスキーマティックのファイル名だけを出す(場所・拡張子は付けない)。 */
    private static CompletableFuture<Suggestions> suggestLitematicaFiles(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        try {
            LitematicaPlacements.Loaded loaded = LitematicaPlacements.load(
                    context.getSource().getServer(), context.getSource().getLevel().dimension());
            Set<String> names = new LinkedHashSet<>();
            for (LitematicaPlacements.Placement placement : loaded.placements()) {
                String name = LitematicaPlacements.fileName(placement);
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
            for (String name : names) {
                builder.suggest(quoteIfNeeded(name));
            }
        } catch (IOException ignored) {
            // 読めないときは候補を出さない
        }
        return builder.buildFuture();
    }

    /** ファイル名(拡張子なし・大文字小文字は区別しない)から、Litematicaで読み込み中の配置を探す。同名が複数あれば選択中のものを優先。 */
    private static LitematicaPlacements.Placement findPlacementByFileName(LitematicaPlacements.Loaded loaded, String fileName) {
        LitematicaPlacements.Placement found = null;
        for (int i = 0; i < loaded.placements().size(); i++) {
            LitematicaPlacements.Placement candidate = loaded.placements().get(i);
            String name = LitematicaPlacements.fileName(candidate);
            if (name != null && name.equalsIgnoreCase(fileName)) {
                if (i == loaded.selectedIndex()) {
                    return candidate;
                }
                if (found == null) {
                    found = candidate;
                }
            }
        }
        return found;
    }

    /**
     * build <ファイル名>: Litematicaで読み込み中のそのファイルの配置(原点・回転・ミラー)をそのまま使って建築する。
     * regionName が null ならオンの子リージョンすべて、指定があればそのリージョンだけを建築する。
     */
    private static int buildFromLitematica(CommandContext<CommandSourceStack> context, String fileName, String regionName) {
        CommandSourceStack source = context.getSource();

        if (!CommandAccess.canUse(source, TonCarpetSettings.commandPlayerActionbuild)) {
            source.sendFailure(Component.literal("commandPlayerActionbuild ルールが無効になっているため使用できません"));
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

        // Carpetの bot(/player <名前> spawn で出したプレイヤー)だけを動かせる(本物のプレイヤーは動かさない)
        if (!(target instanceof EntityPlayerMPFake)) {
            source.sendFailure(Component.literal(playerName + " は bot ではないため build を使えません(/player <名前> spawn で出した bot だけに使えます)"));
            return 0;
        }

        LitematicaPlacements.Loaded loaded;
        try {
            loaded = LitematicaPlacements.load(source.getServer(), target.level().dimension());
        } catch (IOException e) {
            source.sendFailure(Component.literal("Litematicaの配置情報を読み込めませんでした: " + e.getMessage()));
            return 0;
        }
        if (loaded.placements().isEmpty()) {
            source.sendFailure(Component.literal(
                    "Litematicaに読み込み中の配置がありません(読み込んだ直後は保存されていないことがあります。ワールドを出入りすると反映されます)"));
            return 0;
        }

        LitematicaPlacements.Placement chosen = findPlacementByFileName(loaded, fileName);
        if (chosen == null) {
            StringBuilder names = new StringBuilder();
            for (LitematicaPlacements.Placement candidate : loaded.placements()) {
                String name = LitematicaPlacements.fileName(candidate);
                if (name != null) {
                    if (names.length() > 0) names.append(", ");
                    names.append(name);
                }
            }
            source.sendFailure(Component.literal("Litematicaで読み込み中のファイル '" + fileName + "' が見つかりません。読み込み中: "
                    + (names.length() > 0 ? names : "(なし)")));
            return 0;
        }

        if (chosen.schematic() == null || !Files.isRegularFile(chosen.schematic())) {
            source.sendFailure(Component.literal("スキーマティックファイル '" + fileName + "' が見つかりません"
                    + "(移動・削除されていないか確認してください)"));
            return 0;
        }

        // 子リージョンを動かしていた場合はその位置を使う。region の指定が無ければ、オンの子リージョンだけを建築する
        // (region を指定したときは、Litematicaでオフでも、そのリージョンを建築する)
        Set<String> onlyRegions = null;
        Map<String, BlockPos> positionOverrides = new HashMap<>();
        boolean ownTransform = false;
        if (!chosen.subRegions().isEmpty()) {
            if (regionName == null) {
                onlyRegions = new HashSet<>();
            }
            for (LitematicaPlacements.SubRegion sub : chosen.subRegions()) {
                positionOverrides.put(sub.name(), sub.pos());
                if (regionName == null && sub.enabled()) {
                    onlyRegions.add(sub.name());
                }
                if (regionName == null ? sub.enabled() : sub.name().equalsIgnoreCase(regionName)) {
                    ownTransform |= sub.ownTransform();
                }
            }
        }

        // 名前を指定したリージョンがLitematicaでオフになっている場合は、指定が優先されることを知らせる
        if (regionName != null) {
            for (LitematicaPlacements.SubRegion sub : chosen.subRegions()) {
                if (sub.name().equalsIgnoreCase(regionName) && !sub.enabled()) {
                    source.sendSuccess(() -> Component.literal(
                            "リージョン '" + sub.name() + "' はLitematicaではオフですが、指定されたので建築します"), false);
                }
            }
        }

        String note = "Litematicaの '" + fileName + "'・"
                + (onlyRegions != null ? "オンの子リージョン" + onlyRegions.size() + "個・" : "")
                + (chosen.mirror() != Mirror.NONE ? "ミラー" + chosen.mirror().name() + "・" : "");
        if (ownTransform) {
            source.sendSuccess(() -> Component.literal(
                    "注意: 子リージョン個別の回転・ミラーには未対応のため、配置全体の設定だけで建築します"), false);
        }

        return startBuild(source, target, playerName, chosen.schematic(), chosen.origin(), chosen.rotation(),
                chosen.mirror(), regionName, onlyRegions, positionOverrides, note);
    }

    /**
     * 配置済みの条件(ファイル・原点・回転・ミラー)から、建築ジョブを組み立てて開始する共通処理。
     * onlyRegions が null でなければ、その名前の子リージョンだけを対象にする。
     */
    private static int startBuild(CommandSourceStack source, ServerPlayer target, String playerName, Path file,
                                  BlockPos origin, Rotation rotation, Mirror mirror, String regionFilter,
                                  Set<String> onlyRegions, Map<String, BlockPos> positionOverrides, String extraNote) {
        List<LitematicaSchematicReader.RegionResult> regions;
        try {
            regions = LitematicaSchematicReader.readForPlacement(file, source.registryAccess(), origin, rotation,
                    mirror, positionOverrides);
        } catch (IOException e) {
            source.sendFailure(Component.literal("スキーマティックの読み込みに失敗しました: " + e.getMessage()));
            return 0;
        }

        if (onlyRegions != null) {
            List<LitematicaSchematicReader.RegionResult> enabledRegions = new ArrayList<>();
            for (LitematicaSchematicReader.RegionResult region : regions) {
                if (onlyRegions.contains(region.name())) {
                    enabledRegions.add(region);
                }
            }
            if (enabledRegions.isEmpty()) {
                source.sendFailure(Component.literal("Litematicaでオンになっている子リージョンがありません"));
                return 0;
            }
            regions = enabledRegions;
        }

        if (regionFilter != null) {
            List<LitematicaSchematicReader.RegionResult> filtered = new ArrayList<>();
            for (LitematicaSchematicReader.RegionResult region : regions) {
                if (region.name().equals(regionFilter)) {
                    filtered.add(region);
                }
            }
            if (filtered.isEmpty()) {
                // 大文字小文字の違いだけなら救済する
                for (LitematicaSchematicReader.RegionResult region : regions) {
                    if (region.name().equalsIgnoreCase(regionFilter)) {
                        filtered.add(region);
                    }
                }
            }
            if (filtered.isEmpty()) {
                StringBuilder available = new StringBuilder();
                for (int i = 0; i < regions.size(); i++) {
                    if (i > 0) available.append(", ");
                    available.append(regions.get(i).name());
                }
                source.sendFailure(Component.literal(
                        "リージョン '" + regionFilter + "' が見つかりません。利用可能なリージョン: " + available));
                return 0;
            }
            regions = filtered;
        }

        // 合計(対象リージョン合算)でのブロック数・走査体積の上限チェック(暴走・ラグ対策)
        int totalDesiredBlocks = 0;
        long totalVolume = 0;
        for (LitematicaSchematicReader.RegionResult region : regions) {
            totalDesiredBlocks += region.blocks().size();
            BlockPos regionMin = region.boundsMin();
            BlockPos regionMax = region.boundsMax();
            totalVolume += (long) (regionMax.getX() - regionMin.getX() + 1)
                    * (regionMax.getY() - regionMin.getY() + 1)
                    * (regionMax.getZ() - regionMin.getZ() + 1);
        }
        if (totalDesiredBlocks > MAX_BLOCKS) {
            source.sendFailure(Component.literal(
                    "スキーマティックが大きすぎます(" + totalDesiredBlocks + "ブロック)。" + MAX_BLOCKS + "ブロック以下にしてください"));
            return 0;
        }
        if (totalVolume > MAX_VOLUME) {
            source.sendFailure(Component.literal(
                    "スキーマティックの範囲が大きすぎます(" + totalVolume + "マス)。" + MAX_VOLUME + "マス以下にしてください" +
                            "(範囲内の余計なブロックを検出するために、範囲全体を走査するための上限です)"));
            return 0;
        }

        // 子リージョンごとに、そのリージョンを完全に終わらせてから次のリージョンへ進むよう、
        // タスクをリージョン単位でまとめてから連結する(ファイル内のリージョンの並び順のまま処理する)
        Level level = target.level();
        BlockPos startPos = target.blockPosition();
        List<BuildJob.Placement> orderedAll = new ArrayList<>();
        int regionsWithWork = 0;
        // スキーマティックが指定するブロックすべて(元から正しいものも含む)。BuildJob が壊してはいけない建築物の判定に使う
        Map<BlockPos, BlockState> allDesired = new HashMap<>();
        // アイテムとして持てず設置できないブロック(ピストンヘッド・水・火・ポータル等)の集計(名前 -> 個数)
        Map<String, Integer> unplaceable = new TreeMap<>();

        for (LitematicaSchematicReader.RegionResult region : regions) {
            List<BuildJob.Placement> regionTasks = new ArrayList<>();

            // 目標(desired)をワールド座標で引けるようにしておく。範囲内の「余計なブロック」判定にも使う。
            Map<BlockPos, BlockState> desiredByPos = new HashMap<>();
            for (LitematicaSchematicReader.PlacementBlock block : region.blocks()) {
                desiredByPos.put(block.worldPos(), block.state());
                allDesired.put(block.worldPos(), block.state());
            }

            // 1. このリージョンが指定するブロック: 既にワールド上で一致していればスキップ、
            //    そうでなければ設置タスクとして追加する(位置に別のブロックがあれば、BuildJob側が
            //    先に破壊してから設置する=置き換えとして扱われる)
            for (LitematicaSchematicReader.PlacementBlock block : region.blocks()) {
                BlockState current = level.getBlockState(block.worldPos());
                if (!current.equals(block.state())) {
                    // 対応するアイテムが無いブロックは、持ち物から置くことができない。置こうとして
                    // 素材不足で止まったり、置けないまま粘ったりしないよう、設置タスクに含めない。
                    // (その位置は desiredByPos に残るので、元からあるブロックを「余計なブロック」として壊すことも無い)
                    if (block.state().getBlock().asItem() == Items.AIR) {
                        unplaceable.merge(block.state().getBlock().getName().getString(), 1, Integer::sum);
                        continue;
                    }
                    regionTasks.add(new BuildJob.Placement(block.worldPos(), block.state()));
                }
            }

            // 2. このリージョンのバウンディングボックス内で、リージョンが「空気であるべき」としている
            //    (=desiredByPosに含まれない)位置に何かブロックがあれば、除去タスクとして追加する
            BlockPos regionMin = region.boundsMin();
            BlockPos regionMax = region.boundsMax();
            // 走査用に使い回すMutableBlockPos(最大30万マスをなめるので、マスごとに新しい BlockPos を作らない)。
            // 除去タスクに入れるときだけ immutable にする。
            BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos();
            for (int wy = regionMin.getY(); wy <= regionMax.getY(); wy++) {
                for (int wz = regionMin.getZ(); wz <= regionMax.getZ(); wz++) {
                    for (int wx = regionMin.getX(); wx <= regionMax.getX(); wx++) {
                        scan.set(wx, wy, wz);
                        if (desiredByPos.containsKey(scan)) {
                            continue; // このリージョンが何かを指定している位置は上の1.で処理済み
                        }
                        if (!level.isEmptyBlock(scan)) {
                            regionTasks.add(new BuildJob.Placement(scan.immutable(), null)); // null = 空気であるべき(除去対象)
                        }
                    }
                }
            }

            if (!regionTasks.isEmpty()) {
                regionsWithWork++;
                // 除去タスクと設置タスクは並べ方の方針が異なるので分けて処理する:
                //   - 除去: 単純に下から上へ・近い順で構わない(壊すだけなので接続を気にしなくてよい)
                //   - 設置: スキーマティック自身の隣接関係に沿って、既に建てた部分から外側へ広げていく
                //           (単純な距離順だと、離れた高所へ何度も橋を架けたり、後から積む屋根が
                //            先に建てた壁を巻き込んで壊されたりしやすいため)
                List<BuildJob.Placement> clearTasks = new ArrayList<>();
                List<BuildJob.Placement> placeTasks = new ArrayList<>();
                for (BuildJob.Placement task : regionTasks) {
                    if (task.state() == null) {
                        clearTasks.add(task);
                    } else {
                        placeTasks.add(task);
                    }
                }
                orderedAll.addAll(orderBottomToTopNearestFirst(clearTasks, startPos));
                orderedAll.addAll(orderByConnectivity(placeTasks, startPos));
            }
        }

        if (orderedAll.isEmpty()) {
            source.sendSuccess(() -> Component.literal("既に範囲内がスキーマティック通りになっています"), true);
            return 0;
        }

        BuildJob job = new BuildJob(target, source, orderedAll, 1);
        job.setFullDesired(allDesired);
        // 同じbotが別のmine/buildを実行中なら、それを止めて、今回のbuildに切り替える
        MiningManager.cancelJobsFor(target.getUUID());
        BuildManager.cancelJobsFor(target.getUUID());
        BuildManager.submit(job);

        int total = orderedAll.size();
        double seconds = total / 20.0;
        int finalRegionsWithWork = regionsWithWork;
        int totalRegions = regions.size();
        String regionNote = extraNote + (regionFilter != null ? "リージョン '" + regionFilter + "' のみ・" : "リージョン" + finalRegionsWithWork + "/" + totalRegions + "個・");
        source.sendSuccess(() -> Component.literal(String.format(
                "%s が %d個のブロックの建築(設置・除去・置き換え)を開始しました(%s約%.1f秒予定、使用座標: %d %d %d 回転%d度)",
                playerName, total, regionNote, seconds,
                origin.getX(), origin.getY(), origin.getZ(), rotationDegreesOf(rotation))), true);

        if (!unplaceable.isEmpty()) {
            StringBuilder names = new StringBuilder();
            int shown = 0;
            int skippedTotal = 0;
            for (Map.Entry<String, Integer> e : unplaceable.entrySet()) {
                skippedTotal += e.getValue();
                if (shown < 5) {
                    names.append(shown == 0 ? "" : "、").append(e.getKey()).append("×").append(e.getValue());
                    shown++;
                }
            }
            int finalSkippedTotal = skippedTotal;
            String namesText = names + (unplaceable.size() > shown ? " ほか" : "");
            source.sendSuccess(() -> Component.literal(
                    "アイテムとして置けないブロック" + finalSkippedTotal + "個は設置せずスキップします(" + namesText + ")"), false);
        }

        return total;
    }

    private static int rotationDegreesOf(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

    /**
     * 底(Yが低い方)から上へ向かって層ごとに処理し、各層の中では「今いる位置に近いものから先」に並べる。
     * こうすることで、無作為な順番ではなく実際のプレイヤーが建築するような自然な進め方になる。
     * (除去タスクなど、隣接関係を気にしなくてよい単純な用途向け)
     */
    private static List<BuildJob.Placement> orderBottomToTopNearestFirst(
            List<BuildJob.Placement> tasks, BlockPos startPos) {
        TreeMap<Integer, List<BuildJob.Placement>> byLayer = new TreeMap<>();
        for (BuildJob.Placement task : tasks) {
            byLayer.computeIfAbsent(task.pos().getY(), k -> new ArrayList<>()).add(task);
        }

        List<BuildJob.Placement> result = new ArrayList<>(tasks.size());
        for (List<BuildJob.Placement> layer : byLayer.values()) {
            layer.sort(Comparator.comparingDouble(t -> t.pos().distSqr(startPos)));
            result.addAll(layer);
        }
        return result;
    }

    /**
     * 設置タスクを、スキーマティック自身の隣接関係(26方向: 面・辺・角のいずれかで接する)に沿って、
     * 開始地点に近い・低い場所から外側/上へ広がるように並べる(多始点の優先度付きBFS)。
     *
     * 単純な「下から上へ距離順」だと、離れた高所へ何度も橋を架けたり、後から作る屋根の
     * ブロックが先に建てた壁を巻き込んで壊されたりしやすい(塔と低い棟が混在する建物など、
     * 高低差が激しい建築で特に顕著)。この方式なら、上の階を作る頃には必ずすぐ下か横に
     * 既に建てたブロックがある状態になるため、そうした遠回り・自己破壊が大きく減る。
     * 完全に孤立した部分(他のどのブロックとも接していない浮遊装飾など)は、
     * 優先度(Yが低く、開始地点に近い方が先)に従って新たな起点として再開する。
     */
    private static List<BuildJob.Placement> orderByConnectivity(List<BuildJob.Placement> tasks, BlockPos startPos) {
        if (tasks.isEmpty()) {
            return tasks;
        }

        Map<BlockPos, BuildJob.Placement> remaining = new HashMap<>();
        for (BuildJob.Placement task : tasks) {
            remaining.put(task.pos(), task);
        }

        Comparator<BlockPos> priority = Comparator
                .<BlockPos>comparingInt(p -> p.getY())
                .thenComparingDouble(p -> p.distSqr(startPos));

        // 構造が途切れたときに、新しい起点にする候補を、優先度順に最初に1回だけ並べておく。
        // (途切れるたびに「残り全部から最小を探す」と、離れた部品が多いスキーマティックで計算量が
        //  タスク数の2乗になり、サーバーが固まるため)
        List<BlockPos> seeds = new ArrayList<>(remaining.keySet());
        seeds.sort(priority);
        int seedIndex = 0;

        List<BuildJob.Placement> result = new ArrayList<>(tasks.size());
        PriorityQueue<BlockPos> frontier = new PriorityQueue<>(priority);
        Set<BlockPos> queued = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        while (!remaining.isEmpty()) {
            if (frontier.isEmpty()) {
                // 隣接する未処理タスクが尽きた(=構造がここで途切れている): 残りの中から
                // 最も優先度の高い(低くて開始地点に近い)ものを新しい起点にして再開する
                BlockPos seed = null;
                while (seedIndex < seeds.size()) {
                    BlockPos candidate = seeds.get(seedIndex++);
                    if (remaining.containsKey(candidate)) {
                        seed = candidate;
                        break;
                    }
                }
                if (seed == null) {
                    break; // 残りが無い(通常は起きない)
                }
                frontier.add(seed);
                queued.add(seed);
            }

            BlockPos pos = frontier.poll();
            BuildJob.Placement task = remaining.remove(pos);
            if (task == null) {
                continue; // 別の起点から既に処理済み
            }
            result.add(task);

            // 26近傍の確認には使い回しのMutableBlockPosを使い、実際にキューへ入れるときだけ immutable にする
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                        if (remaining.containsKey(cursor) && !queued.contains(cursor)) {
                            BlockPos neighbor = cursor.immutable();
                            queued.add(neighbor);
                            frontier.add(neighbor);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * "/player <名前> build ..." の <名前> にあたる引数の値を、引数名に依存せず(型だけを頼りに)取得する。
     * PlayerCommandMineMixinの同名メソッドと同じロジック(Mixinクラス間でメソッドを共有できないための重複)。
     */
    private static String findPlayerNameArgument(CommandContext<CommandSourceStack> context) {
        for (ParsedCommandNode<CommandSourceStack> parsed : context.getNodes()) {
            if (parsed.getNode() instanceof ArgumentCommandNode) {
                String argName = parsed.getNode().getName();
                try {
                    return StringArgumentType.getString(context, argName);
                } catch (IllegalArgumentException e) {
                    // このノードが文字列引数でなかった場合はスキップして次を探す
                }
            }
        }
        return null;
    }
}
