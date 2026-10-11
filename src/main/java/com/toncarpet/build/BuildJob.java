package com.toncarpet.build;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import com.toncarpet.mine.BotEater;
import com.toncarpet.mine.BotNavigator;
import com.toncarpet.mine.PathFinder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "/player <名前> build ..." 1回分の実行を表す。
 * MiningJobと同じ考え方: 対象位置がリーチ外ならA*で歩いて向かい、着いたら少し狙いを定めてから作業する。
 *
 * 各タスク(Placement)は「このマスは最終的にこうあるべき」という目標を表し、実際に必要な作業は
 * 現在のワールドの状態と目標を tick 開始時に比較して、その場で決める:
 *   - 目標が空気で、今何かがある                     -> 破壊のみ(余計なブロックの除去)
 *   - 目標がブロックで、今空気                         -> 設置のみ(通常の新規設置)
 *   - 目標がブロックで、今すでに別の何かがある(異なるブロック) -> まず破壊、続けて設置(置き換え)
 *   - 目標と現在の状態が一致                           -> 何もしない(スキップ)
 *
 * 状態遷移:
 *   MOVING        ... 対象へ向かって(経路のウェイポイントを辿りながら)歩いている
 *   BREAKING      ... リーチ内で、今そこにあるブロックを破壊している(採掘と同じ硬さ・道具依存の進捗計算)
 *   PLACING       ... リーチ内で、隣接する固体ブロックの面へ狙いを定めてから設置する
 *   OBSTRUCTION_CLEARING... x,z座標が変わらないまま(頭上や柵・壁などで)動けなくなっている場合に、
 *                     採掘(/player mine)と全く同じ仕組みでその場のブロックを破壊している
 *   PAUSED        ... 1つの作業が終わった後、次の対象に取り掛かる前の小休止
 */
public class BuildJob {

    private enum Phase { MOVING, BREAKING, PLACING, OBSTRUCTION_CLEARING, PAUSED }

    /**
     * 1個分のタスク: posの位置は最終的にどうあるべきか。
     * state が null の場合は「このマスは空気であるべき(=余計なブロックがあれば除去する)」を意味する。
     */
    public record Placement(BlockPos pos, BlockState state) {
    }

    // サバイバルのブロック設置・破壊リーチにほぼ合わせた値(採掘と同じ)
    private static final double REACH_DISTANCE = 4.5;
    // 「近づけていない(進歩が無い)」とみなすまでの猶予tick数(5秒)。
    // 単純な経過時間ではなく、対象との距離が縮まっているかどうかで判定する(高い柱を登る等、
    // 時間はかかるが着実に近づいているケースを誤って諦めないようにするため)。
    private static final int STUCK_TIMEOUT_TICKS = 100;
    private static final double PROGRESS_EPSILON = 0.05; // このくらい縮まっていれば「進歩あり」とみなす
    private double bestDistanceToTarget = Double.MAX_VALUE;
    private int ticksSinceProgress = 0;
    // ジャンプし続けても状況が変わらない(壁にぶつかり続けている)tick数がこれを超えたら、
    // 足元にブロックを設置して足場を作る(柱を立てて段差を突破する)
    private static final int STUCK_JUMP_TICKS = 20;
    private int jumpStuckTicks = 0;
    // ウェイポイントに「着いた」とみなす水平距離
    private static final double WAYPOINT_ARRIVAL_DISTANCE = 0.4;

    // リーチに入ってから実際に設置するまでの「狙いを定める」時間(採掘のBREAKINGに相当)
    private static final int PLACE_AIM_TICKS = 5;
    private int placeAimTicksRemaining;
    private BlockHitResult aimingHitResult;

    // 1個の対象を破壊するのに許容する最大tick数(30秒)。
    // 岩盤など理論上壊せないブロックが範囲内に含まれていた場合の無限ループ防止(MiningJobと同じ)。
    private static final int MAX_BREAK_TICKS = 600;
    private float breakProgress = 0f; // 現在のブロックの破壊進捗(0.0〜1.0で破壊完了)

    // 柱登り中、x,z座標が変化していないと判定するまでの猶予tick数(ブロック単位の座標で判定する)
    private static final int HORIZONTAL_STILL_THRESHOLD_TICKS = 10;
    // 1つの対象で「詰まった」と判定してよい回数。これを超えたら、その対象は今は諦めて次へ進む
    // (別ルートを探しても抜けられない場所で、同じ障害物をずっと狙い続けないため)
    private static final int MAX_STUCK_EPISODES = 6;
    // 頭上の障害物を壊しにいく回数の上限(1つの対象あたり)と、1個の破壊にかけてよい見込みtick数。
    // 壊せない(岩盤など)・極端に硬いブロックを何十秒も狙い続けて、そのまま動かなくならないようにする。
    private static final int MAX_CLEARING_ATTEMPTS = 3;
    private static final float MAX_CLEARING_TICKS = 200f;
    // 1つの対象へ向かって歩く・障害物を壊す時間の合計の上限(tick)。これを超えたら、その対象は諦める。
    private static final int MAX_TICKS_ON_TASK = 1800;
    private int lastStillBlockX = Integer.MIN_VALUE;
    private int lastStillBlockZ = Integer.MIN_VALUE;
    private int horizontalStillTicks = 0;
    private int stuckEpisodes = 0;
    private int lastStuckEpisodeY = Integer.MIN_VALUE;
    private int clearingAttempts = 0;
    private int ticksOnTask = 0;
    // 壊そうとしたが壊せなかった(時間切れになった)障害物。二度と狙わない。
    private final Set<BlockPos> failedClearing = new HashSet<>();
    // OBSTRUCTION_CLEARINGフェーズで、今まさに破壊している位置と進捗(BREAKINGフェーズと同じ仕組みを別に持つ)
    private BlockPos obstructionClearPos;
    private float obstructionBreakProgress = 0f;

    // 橋・柱として一時的に設置したブロックの記録。後片付けは基本的に build の最後にまとめて行う
    // (途中で何度も下へ降りて壊しに戻らないため)。ただし溜まりすぎて足場用の材料が尽きないよう、
    // SOFT_LIMIT を超えたときだけ、今いる場所から手が届く範囲にあるものをその場で片付ける。
    private record TemporaryBlock(BlockPos pos, BlockState placedState) {
    }
    private static final int SOFT_LIMIT_SCAFFOLD_BLOCKS = 16;
    private int keptScaffoldCount = 0; // 落下の危険があるため撤去せずに残した数
    private final List<TemporaryBlock> temporaryBlocks = new ArrayList<>();
    private int verifyPassesRemaining = 3; // build完了間際の最終確認を最大で何回やり直すか(修正が連鎖した場合の保険)
    private boolean completionAnnounced = false;

    // 1ブロック設置/破壊するごとの空腹消費(採掘と同じ考え方で、動きが分かりやすいよう大きめの値にしている)
    private static final float PLACE_EXHAUSTION = 0.2F;
    private static final float BREAK_EXHAUSTION = 0.2F;

    // 空腹ゲージが低ければ、所持している食料を自動で食べる(mineと共通)
    private final BotEater eater;

    private final ServerPlayer target;
    private final CommandSourceStack source;
    private final List<Placement> placements;
    private final int pauseTicksAfterPlace;

    private int index = 0;
    private Phase phase = Phase.MOVING;
    private int ticksInPhase = 0;

    // 現在のタスク(index)について、tick開始時に一度だけ判定する内容。
    // taskPrepared が false の間は、次のtickの冒頭で改めて現在のワールド状態と目標を比較する。
    private boolean taskPrepared = false;
    private boolean taskNeedsBreak = false; // 今そこにある何かを先に破壊する必要があるか
    private boolean taskNeedsPlace = false; // 目標が空気ではなく、ブロックを設置する必要があるか

    private int placedCount = 0;
    private int clearedCount = 0; // 余計なブロックを除去した数(置き換えの破壊分・柱登り中の障害物破壊も含む)
    private int scaffoldCleanedCount = 0; // 橋・柱として使った仮ブロックを、後で片付けた数
    private int alreadyCorrectCount = 0; // 既に目標通りだったためスキップした数
    private int unreachableCount = 0; // 辿り着けず諦めた数
    // 素材が足りずスキップしたブロックの集計(ブロック種別 -> 不足数)。完了時にまとめて報告する。
    private final Map<Block, Integer> missingMaterials = new LinkedHashMap<>();

    // 対象の近くまで歩いていく処理(経路探索・計画された橋・ドアの開閉)は BotNavigator にまとめてある
    private final BotNavigator navigator;

    // 足場として置いた仮ブロックの位置と状態。足場の塔から降りるときに「自分が置いたものか」を判定するのに使う。
    private final Map<BlockPos, BlockState> scaffoldStateAt = new HashMap<>();
    // 直前に建築物のブロックを置いた位置。置いた直後に、その上へ登れるなら登る(maybeStartHop)ために覚えておく。
    private BlockPos hopCandidate = null;

    // posごとの目標状態(state==nullの除去タスクは含まない)。仮ブロックの片付け時に、
    // 偶然スキーマティックの本来のブロックと同じ場所・同じ状態になっていた場合に誤って壊さないためのガード。
    private final Map<BlockPos, BlockState> desiredNonAirByPos;
    // スキーマティックが指定するブロックすべて(位置 -> 状態。空気は含まない)。
    // 設置タスクにならなかった「元から正しいブロック」も含む。壊してはいけない建築物のブロックの判定に使う
    // (タスクだけから作る desiredNonAirByPos には、元から正しいブロックが含まれない)。
    private Map<BlockPos, BlockState> fullDesired = Map.of();
    // 除去タスク(state==null)の位置。建築物の範囲内の地形かどうかの判定(下へ掘ってよい場所か)に使う。
    private final Set<BlockPos> removalPositions = new HashSet<>();
    // placements内の除去タスク(state==null)のうち、橋・柱の仮ブロック片付け由来のものの位置。
    // 実際に破壊できたとき、余計なブロックの除去(clearedCount)ではなく仮設撤去(scaffoldCleanedCount)として数えるために使う。
    private final Set<BlockPos> scaffoldPositions = new HashSet<>();
    // このジョブ自身が建築物として置いたブロック(位置 -> ブロックの種類)。隣のブロックの影響で
    // 状態(フェンスのつながり・階段の形・葉の距離など)が後から変わっても「置いたブロック」だと分かるように、
    // 状態ではなく種類で覚えておく。壊してはいけないブロックの判定に使う。
    private final Map<BlockPos, Block> placedByJob = new HashMap<>();

    public BuildJob(ServerPlayer target, CommandSourceStack source, List<Placement> placements, int pauseTicksAfterPlace) {
        this.target = target;
        this.source = source;
        this.placements = placements;
        this.pauseTicksAfterPlace = Math.max(1, pauseTicksAfterPlace);

        Map<BlockPos, BlockState> desiredMap = new HashMap<>();
        for (Placement p : placements) {
            if (p.state() != null) {
                desiredMap.put(p.pos(), p.state());
            } else {
                removalPositions.add(p.pos());
            }
        }
        this.desiredNonAirByPos = desiredMap;
        this.eater = new BotEater(target);
        this.navigator = new BotNavigator(target, this::placeBlockAt, () -> findAnyBlockItemSlot() >= 0,
                this::isReservedCell, this::digCost);
    }

    public int totalBlocks() {
        return placements.size();
    }

    public java.util.UUID getTargetUUID() {
        return target.getUUID();
    }

    /** Carpet本家の "/player <名前> stop" から呼ばれる。ボットの動きを止め、buildを中断する。 */
    public void onExternalStop() {
        stopBot();
        if (phase == Phase.BREAKING && index < placements.size()) {
            target.level().destroyBlockProgress(target.getId(), placements.get(index).pos(), -1); // ひび割れ表示を消す
        } else if (phase == Phase.OBSTRUCTION_CLEARING && obstructionClearPos != null) {
            target.level().destroyBlockProgress(target.getId(), obstructionClearPos, -1); // ひび割れ表示を消す
        }
        source.sendSuccess(() -> Component.literal(
                target.getScoreboardName() + " のbuildを停止しました(" + buildProgressSummary() + ")"), true);
        reportMissingMaterialsIfAny();
    }

    /**
     * 毎tick呼ばれる。ジョブが継続中なら true、終わった(または中断した)なら false を返す。
     */
    public boolean tick() {
        boolean stillOnline = source.getServer().getPlayerList().getPlayer(target.getUUID()) != null;
        if (!stillOnline || target.isRemoved()) {
            stopBot();
            source.sendFailure(Component.literal(
                    target.getScoreboardName() + " がいなくなったため build を中断しました(" + buildProgressSummary() + ")"));
            return false;
        }

        // 通り過ぎて離れたドア/ゲートを閉じ直す
        navigator.tickDoors();

        // 手に持っている道具が壊れる寸前なら、新しいものに持ち替える(Tweakerooの tweakSwapAlmostBrokenTools と同じ仕組み)
        com.toncarpet.mine.ToolSwapUtil.trySwapAlmostBrokenTools(target, message -> source.sendSuccess(() -> message, false));

        // 空腹ゲージが低ければ、所持している食料を優先して食べる(移動・作業より先に処理する)
        if (eater.tick()) {
            return true; // 食事中は他の行動を一時停止する
        }

        if (index >= placements.size()) {
            if (!completionAnnounced) {
                // 足場の仮ブロックは途中では基本的に残してあるので、最後にまとめて片付け対象にする
                flushAllRemainingTemporaryBlocks();
            }
            if (index >= placements.size() && !completionAnnounced && verifyPassesRemaining > 0) {
                // 本当に完了する前に、これまで扱った全箇所をもう一度見直し、ズレがあれば直す
                verifyPassesRemaining--;
                enqueueVerificationMismatches();
            }
            if (index >= placements.size()) {
                if (!completionAnnounced) {
                    completionAnnounced = true;
                    stopBot();
                    // 完了時のログは「終了しました」だけにする(内訳は出さない)
                    source.sendSuccess(() -> Component.literal("終了しました"), true);
                    reportMissingMaterialsIfAny();
                }
                return false;
            }
            // 上のflush/最終確認で新しいタスクが追加された(index < placements.sizeになった)ので、
            // このまま下の通常処理へ続けてそれに取り掛からせる
        }

        final Level level = target.level();
        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();
        Placement task = placements.get(index);
        BlockPos pos = task.pos();
        BlockState desired = task.state(); // null なら「ここは空気であるべき(余計なブロックの除去)」

        if (!taskPrepared) {
            boolean desiredIsAir = (desired == null);
            BlockState current = level.getBlockState(pos);

            // 仮ブロックの撤去タスクが、その後に同じ場所へ置かれたスキーマティック本来のブロックを
            // 壊してしまわないようにする(高低差のある建築で、足場の位置に本来のブロックが入るとき)
            boolean isBuildBlockAlreadyThere = desiredIsAir && isProtectedBuildBlock(pos);

            // 自分が立っている仮ブロックを壊すと高所から落ちてしまう場合は、壊さずに残す
            if (desiredIsAir && scaffoldPositions.contains(pos) && pos.equals(target.blockPosition().below())
                    && !PathFinder.canDropSafely(level, pos)) {
                scaffoldPositions.remove(pos);
                keptScaffoldCount++;
                advanceToNextBlock(false);
                return true;
            }

            // 自分で置いたブロックが、隣の影響で状態だけ変わった場合(フェンスのつながり等)は、
            // 壊して置き直さず「正しい」とみなす(種類が同じで、このジョブが置いたものであること)
            boolean placedHereByMe = !desiredIsAir && current.is(desired.getBlock())
                    && placedByJob.get(pos) == current.getBlock();
            boolean alreadyCorrect = isBuildBlockAlreadyThere || placedHereByMe
                    || (desiredIsAir ? level.isEmptyBlock(pos) : current.equals(desired));
            if (alreadyCorrect) {
                if (isBuildBlockAlreadyThere) {
                    scaffoldPositions.remove(pos);
                }
                alreadyCorrectCount++;
                advanceToNextBlock(false);
                return true;
            }

            taskNeedsBreak = !level.isEmptyBlock(pos); // 何か(液体含む)がある場合は先に取り除く必要がある
            taskNeedsPlace = !desiredIsAir;

            // 取り除く必要があるのに壊せないブロック(岩盤など)なら、永遠に壊れないので狙い続けずスキップする
            if (taskNeedsBreak && current.getDestroySpeed(level, pos) < 0) {
                unreachableCount++;
                advanceToNextBlock(false);
                return true;
            }

            // アイテムとして持てないブロック(ピストンヘッド等)は、素材不足ではなく「置けない」ので、止まらずに飛ばす
            if (taskNeedsPlace && desired.getBlock().asItem() == Items.AIR) {
                advanceToNextBlock(false);
                return true;
            }
            if (taskNeedsPlace && (findBlockItemSlotFor(desired.getBlock()) < 0
                    || countItems(desired.getBlock().asItem()) < itemsRequired(desired))) {
                return stopDueToMissingMaterial(desired.getBlock());
            }
            taskPrepared = true;
        }

        Vec3 blockCenter = Vec3.atCenterOf(pos);

        if (phase == Phase.PAUSED) {
            ticksInPhase++;
            if (ticksInPhase >= pauseTicksAfterPlace) {
                phase = Phase.MOVING;
                ticksInPhase = 0;
                maybeStartHop();
            }
            return true;
        }

        // 1つの対象へ向かって歩く・障害物を壊す時間が長すぎるなら、その対象は諦めて次へ進む
        // (完了前の最終確認で、もう一度だけ試される)
        if (phase == Phase.MOVING || phase == Phase.OBSTRUCTION_CLEARING) {
            ticksOnTask++;
            if (ticksOnTask > MAX_TICKS_ON_TASK) {
                if (phase == Phase.OBSTRUCTION_CLEARING && obstructionClearPos != null) {
                    level.destroyBlockProgress(target.getId(), obstructionClearPos, -1); // ひび割れ表示を消す
                    obstructionClearPos = null;
                }
                ap.stopMovement();
                applyJumping(false);
                unreachableCount++;
                advanceToNextBlock(false);
                return true;
            }
        }

        if (phase == Phase.OBSTRUCTION_CLEARING) {
            ap.lookAt(Vec3.atCenterOf(obstructionClearPos));

            if (level.isEmptyBlock(obstructionClearPos)) {
                // 狙いを定めている間に何らかの理由で既に無くなっていた: 破壊完了扱いで移動を再開する
                level.destroyBlockProgress(target.getId(), obstructionClearPos, -1);
                resumeMovingAfterObstructionClear();
                return true;
            }

            BlockState obstructionState = level.getBlockState(obstructionClearPos);
            float increment = obstructionState.getDestroyProgress(target, level, obstructionClearPos);
            obstructionBreakProgress += increment;
            ticksInPhase++;

            int stage = (int) (Math.min(obstructionBreakProgress, 1.0f) * 9.0f);
            level.destroyBlockProgress(target.getId(), obstructionClearPos, stage);

            if (obstructionBreakProgress >= 1.0f || ticksInPhase > MAX_BREAK_TICKS) {
                level.destroyBlockProgress(target.getId(), obstructionClearPos, -1); // ひび割れ表示を消す
                if (obstructionBreakProgress < 1.0f) {
                    failedClearing.add(obstructionClearPos); // 壊しきれなかった: 二度と狙わない
                }
                if (obstructionBreakProgress >= 1.0f && com.toncarpet.mine.BlockMiningUtil.destroyBlockAndCollect(target, level, obstructionClearPos)) {
                    scaffoldStateAt.remove(obstructionClearPos);
                    scaffoldPositions.remove(obstructionClearPos);
                    temporaryBlocks.removeIf(tb -> tb.pos().equals(obstructionClearPos));
                    clearedCount++;
                    target.causeFoodExhaustion(BREAK_EXHAUSTION);
                }
                resumeMovingAfterObstructionClear();
            }
            return true;
        }

        if (phase == Phase.BREAKING) {
            ap.lookAt(blockCenter);

            if (level.isEmptyBlock(pos)) {
                // 狙いを定めている間に何らかの理由で既に無くなっていた: 破壊完了扱いで次の段階へ
                level.destroyBlockProgress(target.getId(), pos, -1);
                return onBreakStepFinished(pos, desired);
            }

            BlockState state = level.getBlockState(pos);
            float increment = state.getDestroyProgress(target, level, pos);
            breakProgress += increment;
            ticksInPhase++;

            // 近くのプレイヤーにひび割れアニメーションを表示する(採掘中の見た目)
            int stage = (int) (Math.min(breakProgress, 1.0f) * 9.0f);
            level.destroyBlockProgress(target.getId(), pos, stage);

            if (breakProgress >= 1.0f || ticksInPhase > MAX_BREAK_TICKS) {
                level.destroyBlockProgress(target.getId(), pos, -1); // ひび割れ表示を消す

                if (breakProgress >= 1.0f && com.toncarpet.mine.BlockMiningUtil.destroyBlockAndCollect(target, level, pos)) {
                    if (scaffoldPositions.remove(pos)) {
                        scaffoldCleanedCount++;
                    } else {
                        clearedCount++;
                    }
                    target.causeFoodExhaustion(BREAK_EXHAUSTION);
                }
                return onBreakStepFinished(pos, desired);
            }
            return true;
        }

        if (phase == Phase.PLACING) {
            ap.lookAt(aimingHitResult.getLocation());
            placeAimTicksRemaining--;
            if (placeAimTicksRemaining > 0) {
                return true; // まだ狙いを定めている最中
            }

            boolean placed = performPlacement(pos, desired, aimingHitResult);
            if (!placed) {
                // 壊しきれず(または別の理由で)塞がったままなら、素材不足ではないので停止せず、この箇所だけ諦める
                if (!level.isEmptyBlock(pos) && level.getBlockState(pos).getFluidState().isEmpty()) {
                    unreachableCount++;
                    advanceToNextBlock(false);
                    return true;
                }
                // 狙いを定めている間に資材を失った等: 素材不足として停止する
                return stopDueToMissingMaterial(desired.getBlock());
            }
            hopCandidate = pos.immutable(); // 置いた直後に、そのブロックの上へ登れるなら登る(次の一時停止のあとで判断)
            return finishTaskAndCheckCompletion(true);
        }

        // phase == MOVING
        // 置いた直後に、そのブロックの上へ登る(maybeStartHop で登ると決めたとき)
        Vec3 hopTarget = navigator.tickHop();
        if (hopTarget != null) {
            ap.lookAt(hopTarget);
            ap.setForward(1);
            applyJumping(navigator.hopNeedsJump());
            return true;
        }

        double distance = target.getEyePosition().distanceTo(blockCenter);

        if (distance <= REACH_DISTANCE) {
            // 届く距離に入った: 止まって、必要な方の作業(破壊 or 設置)を開始する
            ap.stopMovement();
            applyJumping(false);
            if (taskNeedsBreak) {
                ap.lookAt(blockCenter);
                equipBestToolFor(level.getBlockState(pos));
                phase = Phase.BREAKING;
                ticksInPhase = 0;
                breakProgress = 0f;
            } else {
                beginPlacing(pos, desired);
            }
            return true;
        }

        // まだ届かない: 手が届く場所までの経路(A*)に沿って歩く。
        // 経路の探し直し・計画された橋の設置・ドアの開閉は navigator が行う。
        Vec3 moveTarget = navigator.update(pos, blockCenter);
        boolean followingPath = navigator.isFollowingPath();

        // 降りるために足元のブロックを壊す(経路の計画で、歩いて降りられないと判断されたとき)。
        // 障害物を壊すのと同じ処理で、サバイバルと同じ速度で壊す。
        BlockPos digPos = navigator.takePendingDig();
        if (digPos != null) {
            int attemptsBefore = clearingAttempts; // 降りるための掘削は、頭上の障害物を壊す回数の上限に数えない
            beginObstructionClearing(digPos);
            clearingAttempts = attemptsBefore;
            return true;
        }

        // 足場を積んで登る一手のときは、前へ歩かず(足場の上から落ちてしまう)その場でジャンプして足元に置く
        boolean pillarStep = navigator.isPillarStep();
        ap.lookAt(pillarStep ? blockCenter : moveTarget);
        ap.setForward(pillarStep ? 0 : 1);

        // 経路が無く直進しているとき(空中に浮いた建築対象へ渡る等)だけ、
        // 安全に降りられる床も無い場合に限って橋を架ける
        navigator.bridgeAheadIfNoPath(moveTarget);

        // x,z座標が実際に動いているかどうかを記録しておく(詰まっているかどうかの判定に使う)
        updateHorizontalStillness();

        // x,z座標が変わらないまま動けなくなっている場合: 頭上のブロックが原因なら
        // 採掘(/player mine)と全く同じ仕組みで破壊するが、それ以外(柵・壁など進行方向の障害物)が
        // 原因の場合は破壊せず、その方向を避けて別ルートを探させる。
        // (足場を積んで登っている間は、x,zが動かないのが正常なので、詰まりとは判定しない)
        if (horizontalStillTicks >= HORIZONTAL_STILL_THRESHOLD_TICKS && !pillarStep) {
            // 柱登りなどで足元の高さが変わっている間は、詰まった回数を数え直す
            int feetY = target.blockPosition().getY();
            if (feetY != lastStuckEpisodeY) {
                stuckEpisodes = 0;
                lastStuckEpisodeY = feetY;
            }
            if (++stuckEpisodes > MAX_STUCK_EPISODES) {
                // 別ルートを探しても抜けられない: この箇所は今は諦めて次へ進む(完了前の最終確認でもう一度試す)
                ap.stopMovement();
                applyJumping(false);
                unreachableCount++;
                advanceToNextBlock(false);
                return true;
            }

            // 頭上が建築物のブロックだったり、壊せない・壊すのに時間がかかりすぎるブロックなら壊さず、
            // 別ルートを探す(canClearObstruction が false を返す)
            BlockPos above = target.blockPosition().above(2);
            if (isObstruction(above) && canClearObstruction(above)) {
                beginObstructionClearing(above);
                return true;
            }
            rerouteAroundCurrentDirection();
        }

        // 既存の地形だけでは辿り着けない場合(=A*経路が見つからず直線移動中)、
        // 対象が今より高い位置にあるなら、足元にブロックを継ぎ足しながらジャンプして
        // その場で柱・橋を築きつつよじ登っていく(空中に浮いた建築対象へのアクセス用)
        // 経路が無く、対象の真下付近(水平3マス未満)まで来ているときだけ、足元にブロックを継ぎ足して登る
        // (遠くから対象へ向かっている最中に、その場で柱を立て始めて仮ブロックを無駄にしないため)
        boolean climbNeeded = !followingPath && navigator.isHorizontallyNear(blockCenter, 6.0) && needsClimb(pos);
        if (climbNeeded) {
            placeBlockUnderfoot();
        }

        // 前方の壁にぶつかっている場合や、よじ登り中でジャンプが必要な場合はジャンプする
        // 次のマスが今より高ければ、ぶつかる前にジャンプして登る
        boolean wantJump = target.horizontalCollision || climbNeeded || navigator.needsStepUp();
        applyJumping(wantJump);

        if (target.horizontalCollision && !climbNeeded) {
            jumpStuckTicks++;
            if (jumpStuckTicks >= STUCK_JUMP_TICKS) {
                placeBlockUnderfoot();
                jumpStuckTicks = 0;
            }
        } else {
            jumpStuckTicks = 0;
        }

        // 対象との距離が縮まっているかどうかで「本当に詰まっているか」を判定する。
        // 高い柱を登る・遠回りするなど時間はかかっても着実に近づいているケースは諦めない。
        // 遠回りの経路を着実に進んでいる(ウェイポイントに到達している)間も「進歩あり」とみなす。
        boolean walkedAlongPath = navigator.pollProgress();
        if (distance < bestDistanceToTarget - PROGRESS_EPSILON) {
            bestDistanceToTarget = distance;
            ticksSinceProgress = 0;
        } else if (walkedAlongPath) {
            ticksSinceProgress = 0;
        } else {
            ticksSinceProgress++;
        }

        ticksInPhase++;
        if (ticksSinceProgress > STUCK_TIMEOUT_TICKS) {
            // 経路があっても辿り着けなかった場合は諦めて次のブロックへ
            ap.stopMovement();
            applyJumping(false);
            unreachableCount++;
            advanceToNextBlock(false);
        }

        return true;
    }

    /** OBSTRUCTION_CLEARINGでの破壊が終わったとき、通常のMOVING処理へ復帰するための後始末。 */
    private void resumeMovingAfterObstructionClear() {
        phase = Phase.MOVING;
        ticksInPhase = 0;
        obstructionClearPos = null;
        // 直前まで静止して破壊していたため、静止判定の基準をリセットして誤判定を防ぐ
        horizontalStillTicks = 0;
        lastStillBlockX = Integer.MIN_VALUE;
        lastStillBlockZ = Integer.MIN_VALUE;
    }

    /**
     * 破壊フェーズが1段階終わったときに呼ぶ。まだ設置が必要なら続けてPLACINGへ、
     * 除去だけで完了なら次のタスクへ進む。
     */
    private boolean onBreakStepFinished(BlockPos pos, BlockState desired) {
        if (taskNeedsPlace) {
            beginPlacing(pos, desired);
            return true;
        }
        return finishTaskAndCheckCompletion(true);
    }

    /** 対象の隣接する固体面へ狙いを定め、設置(PLACING)フェーズを開始する。 */
    private void beginPlacing(BlockPos pos, BlockState desired) {
        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();
        aimingHitResult = buildPlacementHitResult(pos);
        ap.lookAt(aimingHitResult.getLocation());
        equipBlockItem(desired.getBlock());
        phase = Phase.PLACING;
        ticksInPhase = 0;
        placeAimTicksRemaining = PLACE_AIM_TICKS;
    }

    /** 現在のタスクを完了として次へ進める。完了判定・完了メッセージは次のtickの冒頭でまとめて行う。 */
    private boolean finishTaskAndCheckCompletion(boolean pauseAfter) {
        advanceToNextBlock(pauseAfter);
        return true;
    }

    private String buildProgressSummary() {
        return "設置" + placedCount + " / 除去" + clearedCount + " / 仮設撤去" + scaffoldCleanedCount +
                " / 既に正しい" + alreadyCorrectCount + " / 到達不可" + unreachableCount
                + (keptScaffoldCount > 0 ? " / 落下の危険で残した仮設" + keptScaffoldCount : "");
    }

    private void advanceToNextBlock(boolean pauseAfter) {
        index++;
        ticksInPhase = 0;
        phase = pauseAfter ? Phase.PAUSED : Phase.MOVING;
        navigator.reset(); // 経路と「別ルート」の回避情報は対象ごとにリセットする
        stuckEpisodes = 0;
        lastStuckEpisodeY = Integer.MIN_VALUE;
        clearingAttempts = 0;
        ticksOnTask = 0;
        aimingHitResult = null;
        bestDistanceToTarget = Double.MAX_VALUE;
        ticksSinceProgress = 0;
        taskPrepared = false;
        taskNeedsBreak = false;
        taskNeedsPlace = false;
        retireReachableTemporaryBlocks();
    }

    /** 狙いを定め終えた位置に、実際にワールドへ設置しアイテムを1個消費する。成功したら true。 */
    private boolean performPlacement(BlockPos pos, BlockState desired, BlockHitResult hitResult) {
        Level level = target.level();
        if (!level.isEmptyBlock(pos) && level.getBlockState(pos).getFluidState().isEmpty()) {
            return false; // 狙っている間に別の理由で塞がっていた
        }

        int slot = findBlockItemSlotFor(desired.getBlock());
        if (slot < 0) {
            return false; // 狙っている間に資材を使い切った等
        }

        var inventory = target.getInventory();
        inventory.pickSlot(slot);
        ItemStack stack = inventory.getSelectedItem();
        if (stack.isEmpty() || !stack.is(desired.getBlock().asItem())) {
            return false;
        }

        // ピストン本体が「伸びた状態」で指定されていても、ピストンヘッドは置けないので、縮んだ状態で置く
        // (伸びた状態のまま置くと、腕の無い壊れたピストンになる)
        BlockState toPlace = desired;
        if (toPlace.hasProperty(BlockStateProperties.EXTENDED) && toPlace.getValue(BlockStateProperties.EXTENDED)) {
            toPlace = toPlace.setValue(BlockStateProperties.EXTENDED, false);
        }

        // 1回の設置で複数個のアイテムを使うブロック(2段のハーフブロック・ろうそく・雪の層など)は、
        // バニラと同じく、その個数ぶんのアイテムが必要。足りなければ置かない(素材不足として扱われる)
        Item placedItem = desired.getBlock().asItem();
        int required = itemsRequired(toPlace);
        if (required > 1 && countItems(placedItem) < required) {
            return false;
        }

        level.setBlock(pos, toPlace, 3);
        placeCompanionPart(level, pos, toPlace);
        consumeItems(placedItem, required);
        placedByJob.put(pos.immutable(), desired.getBlock());
        scaffoldStateAt.remove(pos); // この位置は建築物になった(足場として扱わない)
        placedCount++;
        target.causeFoodExhaustion(PLACE_EXHAUSTION);

        target.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, desired.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
                (desired.getSoundType().getVolume() + 1.0F) / 2.0F, desired.getSoundType().getPitch() * 0.8F);
        return true;
    }

    /**
     * 1個のアイテムで2マス分が置かれるブロック(ベッド・ドア・背の高い花など)は、バニラと同じく
     * 1回の設置でもう片方も一緒に置く。以前は両方を別々の設置として扱い、アイテムを2個使って
     * 2回に分けて置いていた。
     * もう片方もスキーマティックで同じ種類として指定されていて、まだ空いているときだけ置く
     * (空いていなければ、そのマスのタスクが、通常どおり後から処理する)。
     */
    private void placeCompanionPart(Level level, BlockPos pos, BlockState placed) {
        BlockPos otherPos;
        if (placed.hasProperty(BlockStateProperties.BED_PART) && placed.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction facing = placed.getValue(BlockStateProperties.HORIZONTAL_FACING);
            otherPos = placed.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT
                    ? pos.relative(facing)
                    : pos.relative(facing.getOpposite());
        } else if (placed.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            otherPos = placed.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                    ? pos.above()
                    : pos.below();
        } else {
            return;
        }

        BlockState wanted = desiredNonAirByPos.get(otherPos);
        if (wanted == null || !wanted.is(placed.getBlock()) || !level.isEmptyBlock(otherPos)) {
            return;
        }
        level.setBlock(otherPos, wanted, 3);
        placedByJob.put(otherPos.immutable(), wanted.getBlock());
        scaffoldStateAt.remove(otherPos);
    }

    /**
     * 設置対象そのものではなく、隣接する固体ブロックの面を狙う自然な設置方法で BlockHitResult を組み立てる。
     * 実際のサバイバルプレイヤーが「隣のブロックの面をクリックして設置する」のと同じ考え方
     * (MiningJobの液体シール処理と全く同じトリック)。
     * 都合の良い固体の隣接ブロックが見つからない場合のみ、やむを得ず対象位置自体を狙う。
     */
    private BlockHitResult buildPlacementHitResult(BlockPos pos) {
        Level level = target.level();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            boolean isSolid = !neighborState.getCollisionShape(level, neighbor).isEmpty();
            boolean isLiquid = !neighborState.getFluidState().isEmpty();
            if (isSolid && !isLiquid) {
                Direction faceTowardTarget = dir.getOpposite();
                Vec3 hitLocation = Vec3.atCenterOf(neighbor).add(
                        faceTowardTarget.getStepX() * 0.5,
                        faceTowardTarget.getStepY() * 0.5,
                        faceTowardTarget.getStepZ() * 0.5);
                return new BlockHitResult(hitLocation, faceTowardTarget, neighbor, false);
            }
        }
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /** botの所持品から、指定したブロックのアイテムを手に持ち替える。持っていなければ何もせず false。 */
    private boolean equipBlockItem(Block desiredBlock) {
        int slot = findBlockItemSlotFor(desiredBlock);
        if (slot < 0) {
            return false;
        }
        target.getInventory().pickSlot(slot);
        return true;
    }

    /** botの所持品の中から、このブロックに最も効果的な道具を探して手に持ち替える(mineと共通の処理)。 */
    private void equipBestToolFor(BlockState state) {
        com.toncarpet.mine.ToolSwapUtil.equipBestToolFor(target, state);
    }

    /**
     * このブロックの状態を1か所に置くのに必要なアイテムの個数。
     * 2段のハーフブロックは2個、ろうそく・ウミガメの卵・シーピクルスのような「同じ場所に重ねて置く」ブロックと
     * 雪の層は、その個数(層の数)ぶん。それ以外は1個。
     */
    private static int itemsRequired(BlockState state) {
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
        }
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty integerProperty && STACKED_COUNT_PROPERTIES.contains(property.getName())) {
                return Math.max(1, state.getValue(integerProperty));
            }
        }
        return 1;
    }

    // 「この値の数だけアイテムを使って置く」ブロックの状態名(ろうそく・ウミガメの卵・シーピクルス・雪の層・ピンクの花びら等)
    private static final Set<String> STACKED_COUNT_PROPERTIES =
            Set.of("candles", "eggs", "pickles", "layers", "flower_amount", "segment_amount");

    /** 持ち物に入っている、指定したアイテムの合計の個数。 */
    private int countItems(Item item) {
        var inventory = target.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 持ち物から、指定したアイテムを amount 個使う(今手に持っているスタックから先に使う)。 */
    private void consumeItems(Item item, int amount) {
        var inventory = target.getInventory();
        int remaining = amount;
        ItemStack held = inventory.getSelectedItem();
        if (!held.isEmpty() && held.is(item)) {
            int used = Math.min(remaining, held.getCount());
            held.shrink(used);
            remaining -= used;
        }
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                int used = Math.min(remaining, stack.getCount());
                stack.shrink(used);
                remaining -= used;
            }
        }
    }

    /** botの所持品(インベントリ)の中から、指定したブロックに対応するアイテムが入っているスロット番号を探す。無ければ -1。 */
    private int findBlockItemSlotFor(Block block) {
        Item desiredItem = block.asItem();
        if (desiredItem == Items.AIR) {
            return -1; // アイテム化できないブロック(空気など)は置けない
        }
        var inventory = target.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(desiredItem)) {
                return i;
            }
        }
        return -1;
    }

    /** botの所持品(インベントリ)の中から、仮ブロックとして使ってよい(ScaffoldBlocksで許可された)アイテムが入っているスロット番号を探す。無ければ -1。 */
    private int findAnyBlockItemSlot() {
        var inventory = target.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && com.toncarpet.mine.ScaffoldBlocks.isAllowed(blockItem.getBlock())) {
                return i;
            }
        }
        return -1;
    }

    /** まだ建築物のブロックが置かれていない、これから置く予定のマスか(そこに立つと置けなくなる)。 */
    private boolean isReservedCell(BlockPos pos) {
        BlockState wanted = desiredNonAirByPos.get(pos);
        return wanted != null && !target.level().getBlockState(pos).is(wanted.getBlock());
    }

    /** スキーマティックが指定するブロックすべて(元から正しいものを含む)を設定する。 */
    public void setFullDesired(Map<BlockPos, BlockState> fullDesired) {
        this.fullDesired = fullDesired;
    }

    /** 自分が足場として置いた仮ブロックのままか。建築物のブロックと同じ種類になっていたら、足場ではなく建築物とみなす。 */
    private boolean isOwnScaffold(BlockPos pos) {
        BlockState placed = scaffoldStateAt.get(pos);
        return placed != null && !isProtectedBuildBlock(pos) && target.level().getBlockState(pos).equals(placed);
    }

    /**
     * 足元のブロックを壊して降りるときのコスト(経路探索用)。壊してはいけない・壊せないブロックは負の値。
     *   - 建築物のブロック(置いたもの・置くべき種類のもの)は壊さない
     *   - 自分の足場は安い(回収できる)
     *   - 建築物の範囲内にある地形のブロック(どうせ置き換える/除去する場所)は、少し高いコストで壊してよい
     *   - 範囲外の地形は壊さない
     */
    private double digCost(BlockPos pos) {
        Level level = target.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(level, pos) < 0 || failedClearing.contains(pos)) {
            return -1.0;
        }
        if (isProtectedBuildBlock(pos)) {
            return -1.0;
        }
        if (isOwnScaffold(pos)) {
            return 3.0;
        }
        if (desiredNonAirByPos.containsKey(pos) || removalPositions.contains(pos)) {
            return 6.0;
        }
        return -1.0;
    }

    /**
     * 直前に置いたブロックの上へ、置いた直後に登る。次のすべての条件がそろうときだけ。
     *   - 置いたのは足元と同じ高さの真横のブロック(上面がちょうど足元より1段高く、ジャンプで乗れる)
     *   - その上の3マス(乗る位置・頭・頭上)が空いていて、これから置く予定のマスでもない
     *   - 次の対象が置いたブロックと同じかそれより高い位置にあり、乗った位置から手が届く
     * 階段状・段差状の建築では、建築物自身を足場にして登りながら建てられ、仮ブロックが要らない。
     * 壁や柱のように真上へ積む建築では、乗る位置が「次に置く予定のマス」になるので乗らない。
     */
    private void maybeStartHop() {
        BlockPos placed = hopCandidate;
        hopCandidate = null;
        if (placed == null || index >= placements.size()) {
            return;
        }
        BlockPos feet = target.blockPosition();
        if (placed.getY() != feet.getY()
                || Math.abs(placed.getX() - feet.getX()) + Math.abs(placed.getZ() - feet.getZ()) != 1) {
            return;
        }
        BlockPos cell = placed.above();
        if (isReservedCell(cell) || isReservedCell(cell.above()) || isReservedCell(cell.above(2))) {
            return;
        }
        BlockPos next = placements.get(index).pos();
        if (next.getY() < placed.getY()) {
            return;
        }
        Vec3 eyeOnBlock = new Vec3(cell.getX() + 0.5, cell.getY() + 1.62, cell.getZ() + 0.5);
        if (eyeOnBlock.distanceTo(Vec3.atCenterOf(next)) > REACH_DISTANCE - 0.5) {
            return;
        }
        navigator.startHop(cell);
    }

    /**
     * 指定位置に仮ブロック(橋・足場)を設置する。置ける場所(空気、または水・草・落ち葉など置き換わるブロック)で、
     * 置ける仮ブロックを持っていれば置いて true。建築物のブロックは、置き換え可能な種類でも置き換えない。
     */
    private boolean placeBlockAt(BlockPos pos) {
        Level level = target.level();
        if (!PathFinder.canPlaceScaffoldAt(level, pos) || (!level.isEmptyBlock(pos) && isProtectedBuildBlock(pos))) {
            return false;
        }

        int slot = findAnyBlockItemSlot();
        if (slot < 0) {
            return false;
        }

        var inventory = target.getInventory();
        inventory.pickSlot(slot);
        ItemStack stack = inventory.getSelectedItem();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        BlockState newState = blockItem.getBlock().defaultBlockState();
        level.setBlock(pos, newState, 3);
        stack.shrink(1);

        target.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, newState.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
                (newState.getSoundType().getVolume() + 1.0F) / 2.0F, newState.getSoundType().getPitch() * 0.8F);

        temporaryBlocks.add(new TemporaryBlock(pos, newState));
        scaffoldStateAt.put(pos.immutable(), newState);
        return true;
    }

    private void placeBlockUnderfoot() {
        placeBlockAt(target.blockPosition().below());
    }

    /** 対象が今の足元より高い位置にあり、よじ登る必要があるかどうか。 */
    private boolean needsClimb(BlockPos targetPos) {
        int neededY = targetPos.getY();
        int currentFeetY = target.blockPosition().getY();
        return neededY - currentFeetY >= 1;
    }

    /** 指定位置が、移動をふさぐ固体ブロックかどうか(液体・空気は通り抜けられるのでふさがない)。 */
    private boolean isObstruction(BlockPos pos) {
        Level level = target.level();
        BlockState state = level.getBlockState(pos);
        return !level.isEmptyBlock(pos) && state.getFluidState().isEmpty();
    }

    /**
     * 頭上以外の理由(柵・壁など進行方向の障害物)で詰まっていると判断したとき、
     * 今向かっている方向のマスを「通らない」候補として記録し、経路を再計算させる(=別ルートを探す)。
     * 柵(フェンス)は見た目より当たり判定が高く(1.5マス分)、通常のジャンプでは越えられないため、
     * これが無いと柵の前でずっと足踏みし続けてしまう。
     */
    private void rerouteAroundCurrentDirection() {
        navigator.reroute(); // 今向かっているマスを避けて、次のtickで別ルートを探し直す

        // 経路を切り替えた直後にまた即座に「動いていない」と誤判定しないよう、静止判定をリセットする
        horizontalStillTicks = 0;
        lastStillBlockX = Integer.MIN_VALUE;
        lastStillBlockZ = Integer.MIN_VALUE;
    }

    /**
     * x,z座標が変わらないまま動けない原因になっている位置を、
     * 採掘(/player mine)と全く同じ仕組み(硬さ・道具依存の進捗計算、ひび割れ表示)で破壊し始める。
     */
    private void beginObstructionClearing(BlockPos pos) {
        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();
        ap.stopMovement();
        applyJumping(false);
        ap.lookAt(Vec3.atCenterOf(pos));
        equipBestToolFor(target.level().getBlockState(pos));
        obstructionClearPos = pos;
        obstructionBreakProgress = 0f;
        phase = Phase.OBSTRUCTION_CLEARING;
        ticksInPhase = 0;
        clearingAttempts++;
    }

    /**
     * 頭上の障害物を「壊して進む」価値があるか。
     * 建築物のブロック・壊せない(岩盤など)・以前に壊しきれなかった・道具を持っていても時間がかかりすぎる・
     * この対象で既に何度も試した、のいずれかなら false(壊さずに別ルートを探す/諦める)。
     */
    private boolean canClearObstruction(BlockPos pos) {
        if (isProtectedBuildBlock(pos) || failedClearing.contains(pos) || clearingAttempts >= MAX_CLEARING_ATTEMPTS) {
            return false;
        }
        Level level = target.level();
        BlockState state = level.getBlockState(pos);
        if (state.getDestroySpeed(level, pos) < 0) {
            return false;
        }
        equipBestToolFor(state); // 実際に壊すときと同じ道具で、かかる時間を見積もる
        float perTick = state.getDestroyProgress(target, level, pos);
        return perTick > 0f && (1.0f / perTick) <= MAX_CLEARING_TICKS;
    }

    /** x,z座標(ブロック単位)が前tickから変化していないtick数を数える。物理的な微小な揺れの影響を受けない。 */
    private void updateHorizontalStillness() {
        BlockPos p = target.blockPosition();
        if (p.getX() == lastStillBlockX && p.getZ() == lastStillBlockZ) {
            horizontalStillTicks++;
        } else {
            horizontalStillTicks = 0;
            lastStillBlockX = p.getX();
            lastStillBlockZ = p.getZ();
        }
    }

    /**
     * 仮ブロックが溜まりすぎている(SOFT_LIMIT超え)とき、今いる場所から手が届くものだけを撤去タスクにする。
     * 歩いて戻る必要が無い(その場で壊せる)ものだけなので、下へ降りて壊しに行くことは無い。
     * 足元の支えになっているブロックは壊さない。
     */
    private void retireReachableTemporaryBlocks() {
        if (temporaryBlocks.size() <= SOFT_LIMIT_SCAFFOLD_BLOCKS) {
            return;
        }
        Vec3 eye = target.getEyePosition();
        BlockPos feet = target.blockPosition();
        List<TemporaryBlock> reachable = new ArrayList<>();
        for (TemporaryBlock tb : temporaryBlocks) {
            BlockPos p = tb.pos();
            if (eye.distanceTo(Vec3.atCenterOf(p)) > REACH_DISTANCE - 0.7) {
                continue;
            }
            boolean underFeet = p.getY() < feet.getY()
                    && Math.abs(p.getX() - feet.getX()) <= 1 && Math.abs(p.getZ() - feet.getZ()) <= 1;
            if (underFeet) {
                continue;
            }
            reachable.add(tb);
        }
        temporaryBlocks.removeAll(reachable);
        int insertOffset = 0;
        for (TemporaryBlock tb : reachable) {
            insertOffset = retireTemporaryBlock(tb, insertOffset);
        }
    }

    /**
     * 指定位置が、建築物のブロックとして壊してはいけないものか。
     * このジョブが置いたブロック、またはスキーマティックでその位置に置くべき種類のブロックなら true。
     * 状態(向き以外の、隣の影響で変わる性質)の違いは無視して、ブロックの種類で判定する。
     */
    private boolean isProtectedBuildBlock(BlockPos pos) {
        BlockState current = target.level().getBlockState(pos);
        if (current.isAir()) {
            return false;
        }
        Block placed = placedByJob.get(pos);
        if (placed != null && current.is(placed)) {
            return true;
        }
        // タスクにならなかった「元から正しいブロック」も、スキーマティック全体の指定から判定する
        BlockState specified = fullDesired.get(pos);
        if (specified != null && current.is(specified.getBlock())) {
            return true;
        }
        BlockState wanted = desiredNonAirByPos.get(pos);
        return wanted != null && current.is(wanted.getBlock());
    }

    /**
     * buildの完了間際、まだ残っている仮ブロックをすべて片付け対象にする。
     * 上のブロックから順に(同じ高さでは今いる場所から遠いものから)壊すので、柱は上から順に崩れる。
     */
    private void flushAllRemainingTemporaryBlocks() {
        if (temporaryBlocks.isEmpty()) {
            return;
        }
        List<TemporaryBlock> remaining = new ArrayList<>(temporaryBlocks);
        temporaryBlocks.clear();
        Vec3 me = target.position();
        remaining.sort((a, b) -> {
            int byHeight = Integer.compare(b.pos().getY(), a.pos().getY());
            if (byHeight != 0) return byHeight;
            return Double.compare(horizontalDistSq(me, b.pos()), horizontalDistSq(me, a.pos()));
        });
        int insertOffset = 0;
        for (TemporaryBlock tb : remaining) {
            insertOffset = retireTemporaryBlock(tb, insertOffset);
        }
    }

    private static double horizontalDistSq(Vec3 from, BlockPos to) {
        double dx = to.getX() + 0.5 - from.x;
        double dz = to.getZ() + 0.5 - from.z;
        return dx * dx + dz * dz;
    }

    /**
     * build完了間際、これまで扱った全タスクの位置をもう一度見直し、実際に目標通りになっているかを
     * 確認する。途中で何らかの理由(他プレイヤーの干渉、想定外の不整合など)でズレていた箇所が
     * あれば、末尾に積み直して修正させる。1箇所でも直すべき箇所が見つかれば true を返す。
     */
    private boolean enqueueVerificationMismatches() {
        Level level = target.level();
        List<Placement> mismatches = new ArrayList<>();
        for (Placement task : placements) {
            BlockPos pos = task.pos();
            BlockState desired = task.state();
            boolean desiredIsAir = (desired == null);
            BlockState current = level.getBlockState(pos);
            // 仮ブロックの撤去タスクは、その場所にスキーマティック本来のブロックがあるなら「正しい」とみなす。
            // (これを確認対象にすると、完成した建築物のブロックを撤去タスクとして壊し直してしまう)
            if (desiredIsAir && (desiredNonAirByPos.containsKey(pos) || placedByJob.containsKey(pos))) {
                continue;
            }
            // 自分で置いたブロックが、隣の影響で状態だけ変わった場合は「正しい」とみなす
            if (!desiredIsAir && current.is(desired.getBlock()) && placedByJob.get(pos) == current.getBlock()) {
                continue;
            }
            boolean matches = desiredIsAir ? level.isEmptyBlock(pos) : current.equals(desired);
            if (!matches) {
                mismatches.add(task);
            }
        }
        if (mismatches.isEmpty()) {
            return false;
        }
        placements.addAll(mismatches);
        return true;
    }

    /**
     * 仮ブロックを1個、除去タスクとして割り込ませる。
     * 置いたときから状態が変わっていなければ対象にし、既にスキーマティック本来のブロックとして
     * 使われていた(desiredNonAirByPosと一致する)場合は壊さずそのまま残す。
     * 次に使うべきinsertOffsetを返す(実際に挿入した場合のみ1増える)。
     */
    private int retireTemporaryBlock(TemporaryBlock candidate, int insertOffset) {
        Level level = target.level();
        BlockPos pos = candidate.pos();
        if (!level.getBlockState(pos).equals(candidate.placedState())) {
            return insertOffset; // 置いた後に何か別の理由で変わっていた: 触らない
        }
        BlockState desired = desiredNonAirByPos.get(pos);
        if (desired != null && desired.equals(candidate.placedState())) {
            return insertOffset; // 偶然スキーマティック本来のブロックと同じだった: 壊さず残す
        }

        scaffoldPositions.add(pos);
        int insertAt = Math.min(index + insertOffset, placements.size());
        placements.add(insertAt, new Placement(pos, null));
        return insertOffset + 1;
    }

    private boolean isInLiquid() {
        return target.isInWater() || target.isInLava();
    }

    private void applyJumping(boolean wantJump) {
        target.setJumping(wantJump || isInLiquid());
    }

    /**
     * 資材が足りないブロックに遭遇したとき、そのブロックをスキップして続行するのではなく、
     * buildそのものを停止し、不足している旨をすぐにログ(チャット)へ出す。
     */
    private boolean stopDueToMissingMaterial(Block block) {
        missingMaterials.merge(block, 1, Integer::sum);
        stopBot();
        source.sendFailure(Component.literal(
                target.getScoreboardName() + " の build を素材不足のため停止しました: " +
                        block.getName().getString() + " が足りません(" + buildProgressSummary() + ")"));
        reportMissingMaterialsIfAny();
        return false;
    }

    /** 素材不足があれば、完了・中断時にまとめて報告する。 */
    private void reportMissingMaterialsIfAny() {
        if (missingMaterials.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("素材が足りず設置できなかったブロック: ");
        boolean first = true;
        for (Map.Entry<Block, Integer> entry : missingMaterials.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey().getName().getString()).append(" x").append(entry.getValue());
        }
        String message = sb.toString();
        source.sendFailure(Component.literal(message));
    }

    private void stopBot() {
        ((ServerPlayerInterface) target).getActionPack().stopMovement();
        target.setJumping(false);
        navigator.release(); // 開けたままのドア/ゲートを閉じる
    }
}
