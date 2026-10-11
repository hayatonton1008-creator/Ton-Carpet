package com.toncarpet.mine;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "/player <名前> mine ..." 1回分の実行を表す。
 * サバイバルの採掘と同じように、対象ブロックが手の届く範囲(リーチ)外なら
 * A*で経路を探してそちらへ歩いて向かい、視線を合わせてから
 * 硬さ・道具に応じた時間をかけて破壊する。
 *
 * 状態遷移:
 *   MOVING        ... 対象ブロックへ向かって(経路のウェイポイントを辿りながら)歩いている
 *   BREAKING      ... リーチ内で採掘中(BlockState.getDestroyProgress で毎tick進捗を計算)
 *   OBSTRUCTION_CLEARING... x,z座標が変わらないまま(頭上や柵・壁などで)動けなくなっている場合に、
 *                     同じ仕組みでその場のブロックを破壊している
 *   PAUSED        ... 1つ壊した後、次のブロックに取り掛かる前の小休止
 */
public class MiningJob {

    private enum Phase { MOVING, BREAKING, OBSTRUCTION_CLEARING, PAUSED }

    // サバイバルのブロック破壊リーチ(手が届く距離)にほぼ合わせた値
    private static final double REACH_DISTANCE = 4.5;
    // 「近づけていない(進歩が無い)」とみなすまでの猶予tick数(5秒)。
    // 単純な経過時間ではなく、対象との距離が縮まっているかどうかで判定する(高い柱を登る等、
    // 時間はかかるが着実に近づいているケースを誤って諦めないようにするため)。buildと同じ方式。
    private static final int STUCK_TIMEOUT_TICKS = 100;
    private static final double PROGRESS_EPSILON = 0.05; // このくらい縮まっていれば「進歩あり」とみなす
    private double bestDistanceToTarget = Double.MAX_VALUE;
    private int ticksSinceProgress = 0;
    // ジャンプし続けても状況が変わらない(壁にぶつかり続けている)tick数がこれを超えたら、
    // 足元にブロックを設置して足場を作る(柱を立てて段差を突破する)
    private static final int STUCK_JUMP_TICKS = 20;
    private int jumpStuckTicks = 0; // 採掘のための移動中に使うカウンタ
    private int sealJumpStuckTicks = 0; // 液体設置場所への移動中に使うカウンタ
    // 液体設置場所への接近も、採掘対象への接近と同じ「進歩が無ければ諦める」方式で判定する
    private double sealBestDistance = Double.MAX_VALUE;
    private int sealTicksSinceProgress = 0;

    // 柱登り中、x,z座標が変化していないと判定するまでの猶予tick数(ブロック単位の座標で判定する。buildと同じ方式)
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
    // 対象の近くまで歩いていく処理(経路探索・計画された橋・ドアの開閉)は BotNavigator にまとめてある
    private final BotNavigator navigator;

    // 橋・柱として置いた仮ブロックの記録(位置 -> 置いた状態)。採掘範囲の内外を問わず全部覚えておき、
    // 掘り終わったあとにまだ残っているものを、上から順にすべて撤去する。
    private final Map<BlockPos, BlockState> placedScaffolds = new LinkedHashMap<>();
    // 撤去のために採掘キューへ追加した仮ブロックの位置
    private final Set<BlockPos> cleanupTargets = new HashSet<>();
    private int cleanupRounds = 0;
    private static final int MAX_CLEANUP_ROUNDS = 3; // 撤去中に橋を架け直した分まで、最大この回数だけ繰り返す
    private int scaffoldCleanedCount = 0;
    private int keptScaffoldCount = 0; // 落下の危険があるため撤去せずに残した数

    // 1つのブロックを採掘するのに許容する最大tick数(30秒)。
    // 岩盤など理論上壊せないブロックを狙ってしまった場合の無限ループ防止。
    private static final int MAX_BREAK_TICKS = 600;
    // ウェイポイントに「着いた」とみなす水平距離
    private static final double WAYPOINT_ARRIVAL_DISTANCE = 0.4;

    // 1ブロック採掘するごとの空腹消費(exhaustion)。
    // バニラ本来の値(0.005)だと約800ブロック掘らないと空腹ゲージが動かず体感できないため、
    // 動きが分かりやすいよう大きめの値にしている(この値だと約20ブロックで1目盛り減る計算)。
    private static final float MINING_EXHAUSTION = 0.2F;

    private final ServerPlayer target;
    private final CommandSourceStack source;
    private final List<BlockPos> positions;
    private final int pauseTicksAfterBreak; // 1個壊してから次に向かうまでの待ち時間(tick)

    // 採掘範囲(液体シール処理でX/Zの範囲を全走査するために保持)
    private final int minX;
    private final int maxX;
    private final int minY;
    private final int maxY;
    private final int minZ;
    private final int maxZ;

    // 何tickごとに液体の検出(再チェック)を行うか。
    // 一度埋めても、近くの水源から再度流れ込んでくることがあるため、continuously再チェックする。
    private static final int RESEAL_INTERVAL_TICKS = 5;
    // 液体の再チェックは、範囲の一番上からではなく「現在の層より少し上」までの
    // 一定の範囲(ウィンドウ)だけを見る。採掘が深く進んでも負荷が増え続けないようにするため。
    private static final int RESCAN_WINDOW_ABOVE = 5;
    private int resealCooldown = 0;

    // 検出はしたがまだ設置していない液体の位置(1tickに1個ずつ処理する)
    private final List<BlockPos> pendingSeals = new ArrayList<>();
    // pendingSealsへの二重登録を防ぐための既知集合
    private final Set<BlockPos> knownLiquidPositions = new HashSet<>();

    // 設置前に「狙いを定める」時間(採掘時に一度止まって狙いを定めるのと同じ考え方)
    private static final int SEAL_AIM_TICKS = 10;
    private BlockPos aimingSealPos; // 現在狙いを定めている(まだ設置していない)液体の位置
    private BlockHitResult aimingSealHitResult;
    private int sealAimTicksRemaining;
    private boolean sealMoving; // まだ設置場所に近づいている最中かどうか

    // 空腹ゲージが低ければ、所持している食料を自動で食べる(buildと共通)
    private final BotEater eater;

    private int index = 0;
    private Phase phase = Phase.MOVING;
    private int ticksInPhase = 0;
    private int destroyedCount = 0;
    private float breakProgress = 0f; // 現在のブロックの採掘進捗(0.0〜1.0で破壊)


    public MiningJob(ServerPlayer target, CommandSourceStack source, List<BlockPos> positions, int pauseTicksAfterBreak,
                      int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.target = target;
        this.source = source;
        this.positions = positions;
        this.pauseTicksAfterBreak = Math.max(1, pauseTicksAfterBreak);
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
        this.eater = new BotEater(target);
        this.navigator = new BotNavigator(target, this::placeBlockAt, () -> findScaffoldBlockItemSlot() >= 0,
                null, this::digCost);
    }

    public int totalBlocks() {
        return positions.size();
    }

    public java.util.UUID getTargetUUID() {
        return target.getUUID();
    }

    /** Carpet本家の "/player <名前> stop" から呼ばれる。ボットの動きを止め、mineを中断する。 */
    public void onExternalStop() {
        stopBot();
        if (phase == Phase.BREAKING && index < positions.size()) {
            target.level().destroyBlockProgress(target.getId(), positions.get(index), -1); // ひび割れ表示を消す
        } else if (phase == Phase.OBSTRUCTION_CLEARING && obstructionClearPos != null) {
            target.level().destroyBlockProgress(target.getId(), obstructionClearPos, -1); // ひび割れ表示を消す
        }
        source.sendSuccess(() -> Component.literal(
                target.getScoreboardName() + " のmineを停止しました(" + destroyedCount + "個破壊済み)"), true);
    }

    /**
     * 毎tick呼ばれる。ジョブが継続中なら true、終わった(または中断した)なら false を返す。
     */
    public boolean tick() {
        boolean stillOnline = source.getServer().getPlayerList().getPlayer(target.getUUID()) != null;
        if (!stillOnline || target.isRemoved()) {
            stopBot();
            source.sendFailure(Component.literal(
                    target.getScoreboardName() + " がいなくなったため mine を中断しました(" + destroyedCount + "個破壊済み)"));
            return false;
        }

        // 通り過ぎて離れたドア/ゲートを閉じ直す
        navigator.tickDoors();

        // 手に持っている道具が壊れる寸前なら、新しいものに持ち替える(Tweakerooの tweakSwapAlmostBrokenTools と同じ仕組み)
        ToolSwapUtil.trySwapAlmostBrokenTools(target, message -> source.sendSuccess(() -> message, false));

        // 空腹ゲージが低ければ、所持している食料を優先して食べる(採掘や液体処理より先に処理する)
        if (eater.tick()) {
            return true; // 食事中は他の行動(移動・採掘・液体処理)を一時停止する
        }

        final Level level = target.level(); // このtick内で何度も参照するので1回だけ取得しておく

        // 液体の検出(間引きして定期的に)。液体の有無にかかわらず、常に現在のYより
        // 1層下までを検知範囲に含める(先読みして、掘り進む前に埋められるようにする)。
        // 採掘が深く進んでも負荷が増え続けないよう、範囲の一番上からではなく
        // 「現在の層より少し上」までの一定範囲(ウィンドウ)だけを毎回スキャンする。
        // 見つけたものはpendingSealsに登録するだけで、実際の設置は毎tick1個ずつ行う。
        resealCooldown--;
        if (resealCooldown <= 0) {
            int currentY = (index < positions.size()) ? positions.get(index).getY() : minY;
            int scanFromY = Math.min(maxY, currentY + RESCAN_WINDOW_ABOVE);
            int scanToY = Math.max(minY, currentY - 1);
            scanLiquidsInLayers(scanFromY, scanToY);
            resealCooldown = RESEAL_INTERVAL_TICKS;
        }

        // 液体を検出中/設置中は、採掘(移動・破壊)を一時停止して同時に動かないようにする
        if (aimingSealPos != null || !pendingSeals.isEmpty()) {
            ((ServerPlayerInterface) target).getActionPack().stopMovement();
            processPendingSeal();
            return true;
        }

        if (index >= positions.size() && !enqueueScaffoldCleanup()) {
            // 最後のブロックが既に無かった・壊せなかった等で、破壊の完了を経ずにここへ来た場合も、
            // botを止めて(歩き続けないように)完了を知らせる
            finishJob();
            return false;
        }

        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();
        BlockPos pos = positions.get(index);

        // 既に(誰か/何かによって)壊されているブロックは、歩み寄らずスキップする
        if (level.isEmptyBlock(pos)) {
            cleanupTargets.remove(pos);
            advanceToNextBlock(false);
            return true;
        }

        // 壊せないブロック(岩盤など)は、狙い続けても永遠に壊れないのでスキップする
        if (level.getBlockState(pos).getDestroySpeed(level, pos) < 0) {
            cleanupTargets.remove(pos);
            advanceToNextBlock(false);
            return true;
        }

        // 仮ブロックの撤去で、自分が立っているブロックを壊すと高所から落ちてしまう場合は、壊さずに残す
        if (cleanupTargets.contains(pos) && pos.equals(target.blockPosition().below())
                && !PathFinder.canDropSafely(level, pos)) {
            cleanupTargets.remove(pos);
            keptScaffoldCount++;
            advanceToNextBlock(false);
            return true;
        }

        Vec3 blockCenter = Vec3.atCenterOf(pos);

        if (phase == Phase.PAUSED) {
            ticksInPhase++;
            if (ticksInPhase >= pauseTicksAfterBreak) {
                phase = Phase.MOVING;
                ticksInPhase = 0;
            }
            return true;
        }

        // 1つの対象へ向かって歩く・障害物を壊す時間が長すぎるなら、その対象は諦めて次へ進む
        if (phase == Phase.MOVING || phase == Phase.OBSTRUCTION_CLEARING) {
            ticksOnTask++;
            if (ticksOnTask > MAX_TICKS_ON_TASK) {
                if (phase == Phase.OBSTRUCTION_CLEARING && obstructionClearPos != null) {
                    level.destroyBlockProgress(target.getId(), obstructionClearPos, -1); // ひび割れ表示を消す
                    obstructionClearPos = null;
                }
                ap.stopMovement();
                applyJumping(false);
                advanceToNextBlock(false);
                return true;
            }
        }

        if (phase == Phase.OBSTRUCTION_CLEARING) {
            tickObstructionClearing();
            return true;
        }

        if (phase == Phase.BREAKING) {
            // ブロックが動いていないか(壊れていないか)、視線だけ随時合わせ続ける
            ap.lookAt(blockCenter);

            BlockState state = level.getBlockState(pos);
            float increment = state.getDestroyProgress(target, level, pos);
            breakProgress += increment;
            ticksInPhase++;

            // 近くのプレイヤーにひび割れアニメーションを表示する(採掘中の見た目)
            int stage = (int) (Math.min(breakProgress, 1.0f) * 9.0f);
            level.destroyBlockProgress(target.getId(), pos, stage);

            if (breakProgress >= 1.0f || ticksInPhase > MAX_BREAK_TICKS) {
                level.destroyBlockProgress(target.getId(), pos, -1); // ひび割れ表示を消す

                if (breakProgress >= 1.0f && BlockMiningUtil.destroyBlockAndCollect(target, level, pos)) {
                    destroyedCount++;
                    if (cleanupTargets.remove(pos)) {
                        scaffoldCleanedCount++;
                    }
                    target.causeFoodExhaustion(MINING_EXHAUSTION); // 採掘による空腹消費
                }

                advanceToNextBlock(true);

                // 採掘が終わったら、橋・柱として置いた仮ブロックがまだ残っていれば、続けてすべて撤去する
                if (index >= positions.size() && !enqueueScaffoldCleanup()) {
                    finishJob();
                    return false;
                }
            }
            return true;
        }

        // phase == MOVING
        double distance = target.getEyePosition().distanceTo(blockCenter);

        if (distance <= REACH_DISTANCE) {
            // 届く距離に入った: 止まって狙いを定め、採掘(BREAKING)を開始する
            ap.stopMovement();
            applyJumping(false);
            ap.lookAt(blockCenter);
            equipBestToolFor(level.getBlockState(pos));
            phase = Phase.BREAKING;
            ticksInPhase = 0;
            breakProgress = 0f;
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

        // 経路が無く直進しているときだけ、安全に降りられる床も無い場合に限って橋を架ける
        navigator.bridgeAheadIfNoPath(moveTarget);

        // x,z座標が実際に動いているかどうかを記録しておく(詰まっているかどうかの判定に使う)
        updateHorizontalStillness();

        // x,z座標が変わらないまま動けなくなっている場合: 頭上のブロックが原因なら
        // 採掘と全く同じ仕組みで破壊するが、それ以外(柵・壁など進行方向の障害物)が
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
                // 別ルートを探しても抜けられない: この対象は今は諦めて次へ進む
                ap.stopMovement();
                applyJumping(false);
                advanceToNextBlock(false);
                return true;
            }

            BlockPos above = target.blockPosition().above(2);
            if (isObstruction(above) && canClearObstruction(above)) {
                beginObstructionClearing(above);
                return true;
            }
            rerouteAroundCurrentDirection();
        }

        // 既存の地形だけでは辿り着けない場合(=A*経路が見つからず直線移動中)、
        // 対象が今より高い位置にあるなら、足元にブロックを継ぎ足しながらジャンプして
        // その場で柱・橋を築きつつよじ登っていく
        // 経路が無く、対象の真下付近(水平3マス未満)まで来ているときだけ、足元にブロックを継ぎ足して登る
        // (遠くから対象へ向かっている最中に、その場で柱を立て始めて仮ブロックを無駄にしないため)
        boolean climbNeeded = !followingPath && navigator.isHorizontallyNear(blockCenter, 3.0) && needsClimb(pos);
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
                // ジャンプし続けても状況が変わらない: 足元にブロックを設置して足場を作る
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
            advanceToNextBlock(false);
        }

        return true;
    }

    /** mineが完了したときの後始末: botを止めて(ドアも閉じて)、結果を知らせる。 */
    private void finishJob() {
        stopBot();
        String scaffoldNote = scaffoldCleanedCount > 0 ? "、仮ブロック" + scaffoldCleanedCount + "個を撤去" : "";
        String keptNote = keptScaffoldCount > 0 ? "、落下の危険があるため" + keptScaffoldCount + "個は残しました" : "";
        source.sendSuccess(() -> Component.literal(
                target.getScoreboardName() + " が " + destroyedCount + " 個のブロックを破壊しました(mine完了"
                        + scaffoldNote + keptNote + ")"), true);
    }

    private void advanceToNextBlock(boolean pauseAfter) {
        index++;
        ticksInPhase = 0;
        breakProgress = 0f;
        phase = pauseAfter ? Phase.PAUSED : Phase.MOVING;
        navigator.reset(); // 経路と「別ルート」の回避情報は対象ごとにリセットする
        stuckEpisodes = 0;
        lastStuckEpisodeY = Integer.MIN_VALUE;
        clearingAttempts = 0;
        ticksOnTask = 0;
        bestDistanceToTarget = Double.MAX_VALUE;
        ticksSinceProgress = 0;
    }

    /**
     * fromY(上の層)から toY(下の層、こちらも含む)まで、範囲内のすべてのYの層について
     * 液体を検出し、pendingSealsに登録する(実際の設置はここでは行わない)。
     * 層全体が液体で、キューに一度も登場しなかった層もこれで確実に検出できる。
     */
    private void scanLiquidsInLayers(int fromY, int toY) {
        for (int y = fromY; y >= toY; y--) {
            scanLiquidsInLayer(y);
        }
    }

    /** 指定したY座標の層にある液体ブロックを検出し、pendingSealsに登録する。
     *  採掘範囲のすぐ外側(壁の向こう)に置かれた液体も検出できるよう、外側に1ブロックだけ余分に見る。 */
    private void scanLiquidsInLayer(int y) {
        Level level = target.level();
        // 走査用に使い回すMutableBlockPos。実際に液体が見つかった時だけimmutable化してキューに入れる
        // (毎マスごとにオブジェクトを生成しない = ガベージコレクションの負荷を減らす)
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                cursor.set(x, y, z);
                if (!level.getBlockState(cursor).getFluidState().isEmpty()) {
                    queuePendingSeal(cursor.immutable());
                }
            }
        }
    }

    private void queuePendingSeal(BlockPos p) {
        if (knownLiquidPositions.add(p)) {
            pendingSeals.add(p);
        }
    }

    /**
     * 液体の設置を進める: まだ狙いを定めている最中ならそれを継続し、狙い終わっていれば設置する。
     * 狙っているものが無ければ、pendingSeals から新たに1個取り出して狙い始める。
     * 採掘(MOVING→BREAKING)と同じく「まず視線を合わせて少し待ってから行動する」形にしている。
     */
    private void processPendingSeal() {
        if (phase == Phase.OBSTRUCTION_CLEARING) {
            tickObstructionClearing();
            return;
        }

        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();

        if (aimingSealPos != null) {
            Level level = target.level();
            if (level.getBlockState(aimingSealPos).getFluidState().isEmpty()) {
                aimingSealPos = null; // 既に何か別の理由で埋まっている
                sealMoving = false;
                return;
            }

            Vec3 aimPoint = aimingSealHitResult.getLocation();
            double distance = target.getEyePosition().distanceTo(aimPoint);

            if (sealMoving) {
                if (distance <= REACH_DISTANCE) {
                    sealMoving = false; // 到着したので狙いを定めるフェーズへ進む
                    sealBestDistance = Double.MAX_VALUE;
                    sealTicksSinceProgress = 0;
                    ap.stopMovement();
                    applyJumping(false);
                } else {
                    // まだ設置場所に近づいている最中
                    // 液体の埋め立て場所へも、採掘と同じ経路探索で近づく(以前は直進しながら橋を架け続けていた)
                    Vec3 sealMoveTarget = navigator.update(aimingSealPos, aimPoint);
                    // 降りるために足元のブロックを壊す必要があるとき(採掘と同じ処理で、サバイバルと同じ速度で壊す)
                    BlockPos sealDigPos = navigator.takePendingDig();
                    if (sealDigPos != null) {
                        int attemptsBefore = clearingAttempts;
                        beginObstructionClearing(sealDigPos);
                        clearingAttempts = attemptsBefore;
                        return;
                    }
                    boolean sealPillarStep = navigator.isPillarStep();
                    ap.lookAt(sealPillarStep ? aimPoint : sealMoveTarget);
                    ap.setForward(sealPillarStep ? 0 : 1);
                    navigator.bridgeAheadIfNoPath(sealMoveTarget);
                    updateHorizontalStillness();

                    // x,z座標が変わらないまま動けなくなっている場合: 頭上のブロックが原因なら
                    // 採掘と全く同じ仕組みで破壊するが、それ以外が原因の場合は別ルートを探させる。
                    if (horizontalStillTicks >= HORIZONTAL_STILL_THRESHOLD_TICKS && !sealPillarStep) {
                        int feetY = target.blockPosition().getY();
                        if (feetY != lastStuckEpisodeY) {
                            stuckEpisodes = 0;
                            lastStuckEpisodeY = feetY;
                        }
                        if (++stuckEpisodes > MAX_STUCK_EPISODES) {
                            // 別ルートを探しても抜けられない: この液体は諦めて次へ進む
                            ap.stopMovement();
                            applyJumping(false);
                            aimingSealPos = null;
                            sealMoving = false;
                            sealBestDistance = Double.MAX_VALUE;
                            sealTicksSinceProgress = 0;
                            return;
                        }

                        BlockPos above = target.blockPosition().above(2);
                        if (isObstruction(above) && canClearObstruction(above)) {
                            beginObstructionClearing(above);
                            return;
                        }
                        // (ここで「進歩なし」のカウントを0に戻すと、詰まるたびに諦めるまでの時間が延びて
                        //  永久に抜け出せなくなるので戻さない)
                        rerouteAroundCurrentDirection();
                    }

                    applyJumping(target.horizontalCollision || navigator.needsStepUp());
                    if (target.horizontalCollision) {
                        sealJumpStuckTicks++;
                        if (sealJumpStuckTicks >= STUCK_JUMP_TICKS) {
                            placeBlockUnderfoot();
                            sealJumpStuckTicks = 0;
                        }
                    } else {
                        sealJumpStuckTicks = 0;
                    }

                    // 対象との距離が縮まっているかどうかで「本当に詰まっているか」を判定する(採掘と同じ方式)
                    boolean sealWalkedAlongPath = navigator.pollProgress();
                    if (distance < sealBestDistance - PROGRESS_EPSILON) {
                        sealBestDistance = distance;
                        sealTicksSinceProgress = 0;
                    } else if (sealWalkedAlongPath) {
                        sealTicksSinceProgress = 0;
                    } else {
                        sealTicksSinceProgress++;
                    }

                    if (sealTicksSinceProgress > STUCK_TIMEOUT_TICKS) {
                        // 辿り着けない場合は、この液体は諦めて次へ進む
                        ap.stopMovement();
                        applyJumping(false);
                        aimingSealPos = null;
                        sealMoving = false;
                        sealBestDistance = Double.MAX_VALUE;
                        sealTicksSinceProgress = 0;
                    }
                    return;
                }
            }

            ap.lookAt(aimPoint);
            sealAimTicksRemaining--;
            if (sealAimTicksRemaining > 0) {
                return; // まだ狙いを定めている最中
            }

            placeSealedBlock(aimingSealPos, aimingSealHitResult);
            aimingSealPos = null;
            return;
        }

        if (pendingSeals.isEmpty()) {
            return;
        }

        BlockPos pos = pendingSeals.remove(0);
        stuckEpisodes = 0; // 新しい液体ごとに、詰まった回数・壊しにいった回数を数え直す
        lastStuckEpisodeY = Integer.MIN_VALUE;
        clearingAttempts = 0;
        Level level = target.level();

        if (level.getBlockState(pos).getFluidState().isEmpty()) {
            return; // 既に何か別の理由で埋まっている
        }

        if (findScaffoldBlockItemSlot() < 0) {
            return; // 置ける仮ブロックが無い(黙って諦める)
        }

        // まず対象に近づき始める(この時点ではまだ狙いを定めていない・設置もしない)
        aimingSealPos = pos;
        aimingSealHitResult = buildPlacementHitResult(pos);
        sealAimTicksRemaining = SEAL_AIM_TICKS;
        sealMoving = true;
        sealBestDistance = Double.MAX_VALUE;
        sealTicksSinceProgress = 0;
        sealJumpStuckTicks = 0;
    }

    /** 狙いを定め終えたブロックを、実際にワールドへ設置しアイテムを1個消費する。 */
    private void placeSealedBlock(BlockPos pos, BlockHitResult hitResult) {
        Level level = target.level();
        if (level.getBlockState(pos).getFluidState().isEmpty()) {
            return; // 狙っている間に何か別の理由で埋まっていた
        }

        int slot = findScaffoldBlockItemSlot();
        if (slot < 0) {
            return; // 置ける仮ブロックが無い(黙って諦める)
        }

        var inventory = target.getInventory();
        inventory.pickSlot(slot); // 実際に手に持つ
        ItemStack stack = inventory.getSelectedItem();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        Block block = blockItem.getBlock();
        BlockState newState = block.defaultBlockState();

        level.setBlock(pos, newState, 3);
        stack.shrink(1);

        // 実際に「置く」動作: 腕を振り、設置音を鳴らす
        target.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, newState.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
                (newState.getSoundType().getVolume() + 1.0F) / 2.0F, newState.getSoundType().getPitch() * 0.8F);

        if (isWithinMiningBounds(pos)) {
            insertByYDescending(pos);
        }
    }

    /**
     * 液体ブロックそのものを狙うのではなく、隣接する固体ブロックの面を狙う自然な設置方法で
     * BlockHitResult を組み立てる。実際のサバイバルプレイヤーが水たまりを埋めるときのように、
     * 隣の固体ブロックの面をクリックして、その先(=液体があった場所)に設置する形にする。
     * 都合の良い固体の隣接ブロックが見つからない場合のみ、やむを得ず液体自体を狙う。
     */
    private BlockHitResult buildPlacementHitResult(BlockPos pos) {
        Level level = target.level();
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            boolean isSolid = !neighborState.getCollisionShape(level, neighbor).isEmpty();
            boolean isLiquid = !neighborState.getFluidState().isEmpty();
            if (isSolid && !isLiquid) {
                // neighbor の、pos側を向いた面をクリックすることにする
                Direction faceTowardTarget = dir.getOpposite();
                Vec3 hitLocation = Vec3.atCenterOf(neighbor).add(
                        faceTowardTarget.getStepX() * 0.5,
                        faceTowardTarget.getStepY() * 0.5,
                        faceTowardTarget.getStepZ() * 0.5);
                return new BlockHitResult(hitLocation, faceTowardTarget, neighbor, false);
            }
        }
        // 固体の隣接ブロックが見つからなかった場合のみ、やむを得ず液体自体を狙う
        return new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
    }

    /**
     * botの所持品(インベントリ)から、橋・足場・水/溶岩埋めの仮ブロックとして使ってよい
     * (ScaffoldBlocksで許可された)アイテムが入っているスロット番号を探す。無ければ -1。
     */
    private int findScaffoldBlockItemSlot() {
        var inventory = target.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem
                    && ScaffoldBlocks.isAllowed(blockItem.getBlock())) {
                return i;
            }
        }
        return -1;
    }

    /** 指定位置が、コマンドで指定された採掘範囲(マージンを含まない本来の範囲)内かどうか */
    private boolean isWithinMiningBounds(BlockPos p) {
        return p.getX() >= minX && p.getX() <= maxX
                && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    /**
     * 採掘キューが空になったとき、置いたままの仮ブロックが残っていれば、撤去のためにキューへ追加する。
     * 追加したら true(まだ続きがある)、撤去するものが無ければ false(本当に完了)。
     * 上のブロックから順に(同じ高さでは今いる場所から遠いものから)壊すので、柱は上から順に崩れる。
     * 撤去のために歩く途中でさらに橋を架けた場合に備え、数回まで繰り返す。
     */
    private boolean enqueueScaffoldCleanup() {
        if (cleanupRounds >= MAX_CLEANUP_ROUNDS || placedScaffolds.isEmpty()) {
            return false;
        }
        Level level = target.level();
        List<BlockPos> remaining = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : placedScaffolds.entrySet()) {
            // 置いたときのまま残っているものだけを対象にする(別のものに変わっていたら触らない)
            if (level.getBlockState(e.getKey()).equals(e.getValue())) {
                remaining.add(e.getKey());
            }
        }
        placedScaffolds.clear();
        if (remaining.isEmpty()) {
            return false;
        }

        cleanupRounds++;
        Vec3 me = target.position();
        remaining.sort((a, b) -> {
            int byHeight = Integer.compare(b.getY(), a.getY());
            if (byHeight != 0) return byHeight;
            double da = (a.getX() + 0.5 - me.x) * (a.getX() + 0.5 - me.x) + (a.getZ() + 0.5 - me.z) * (a.getZ() + 0.5 - me.z);
            double db = (b.getX() + 0.5 - me.x) * (b.getX() + 0.5 - me.x) + (b.getZ() + 0.5 - me.z) * (b.getZ() + 0.5 - me.z);
            return Double.compare(db, da);
        });
        positions.addAll(remaining);
        cleanupTargets.addAll(remaining);
        return true;
    }

    /**
     * 残りの採掘キュー(index以降)の中で、Y座標の降順(高い方から低い方へ)が保たれるように
     * 新しいブロックを適切な位置へ挿入する。
     */
    private void insertByYDescending(BlockPos p) {
        int insertAt = positions.size();
        for (int i = index; i < positions.size(); i++) {
            if (positions.get(i).getY() < p.getY()) {
                insertAt = i;
                break;
            }
        }
        positions.add(insertAt, p);
    }

    /** botの所持品の中から、このブロックに最も効果的な道具を探して手に持ち替える(buildと共通の処理)。 */
    private void equipBestToolFor(BlockState state) {
        ToolSwapUtil.equipBestToolFor(target, state);
    }

    /** 自分が足場として置いた仮ブロックのままか。 */
    private boolean isOwnScaffold(BlockPos pos) {
        BlockState placed = placedScaffolds.get(pos);
        return placed != null && target.level().getBlockState(pos).equals(placed);
    }

    /**
     * 足元のブロックを壊して降りるときのコスト(経路探索用)。壊してはいけない・壊せないブロックは負の値。
     *   - 自分の足場は安い(回収できる)
     *   - 採掘範囲内のブロックは、どうせ掘るので、少し高いコストで壊して下へ掘り進んでよい
     *   - 採掘範囲の外は壊さない
     */
    private double digCost(BlockPos pos) {
        Level level = target.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(level, pos) < 0 || failedClearing.contains(pos)) {
            return -1.0;
        }
        if (isOwnScaffold(pos)) {
            return 3.0;
        }
        if (isWithinMiningBounds(pos)) {
            return 6.0;
        }
        return -1.0;
    }

    /**
     * 指定位置に仮ブロック(橋・足場)を設置する。置ける場所(空気、または水・草・落ち葉など置き換わるブロック)で、
     * 置ける仮ブロックを持っていれば置いて true。
     */
    private boolean placeBlockAt(BlockPos pos) {
        Level level = target.level();
        if (!PathFinder.canPlaceScaffoldAt(level, pos)) {
            return false; // 置き換えられない何かがある場合は何もしない
        }

        int slot = findScaffoldBlockItemSlot();
        if (slot < 0) {
            return false; // 置ける仮ブロックが無ければ諦める
        }

        var inventory = target.getInventory();
        inventory.pickSlot(slot); // 実際に手に持つ
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

        placedScaffolds.put(pos.immutable(), newState); // 終了時にまとめて撤去するために覚えておく

        // 橋・足場として設置したブロックも、採掘範囲内であれば後で採掘対象に加える
        if (isWithinMiningBounds(pos)) {
            insertByYDescending(pos);
        }
        return true;
    }

    /** ジャンプしても状況が変わらないとき、足元(の1マス下)にブロックを設置して足場を作る(柱を立てて突破する)。 */
    private void placeBlockUnderfoot() {
        placeBlockAt(target.blockPosition().below());
    }

    /** 液体に入っている間はずっとジャンプし続けて水面/溶岩面に留まろうとする。 */
    private boolean isInLiquid() {
        return target.isInWater() || target.isInLava();
    }

    /** target.setJumping() の代わりに使う。液体中にいる間は常にジャンプを優先する。 */
    private void applyJumping(boolean wantJump) {
        target.setJumping(wantJump || isInLiquid());
    }

    /** 対象が今の足元より高い位置にあり、よじ登る必要があるかどうか。(buildと同じ方式) */
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
     * これが無いと柵の前でずっと足踏みし続けてしまう。(buildと同じ方式)
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
     * 硬さ・道具依存の進捗計算・ひび割れ表示で破壊し始める。(buildのOBSTRUCTION_CLEARINGと同じ方式)
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
     * 壊せない(岩盤など)・以前に壊しきれなかった・道具を持っていても時間がかかりすぎる・
     * この対象で既に何度も試した、のいずれかなら false(壊さずに別ルートを探す/諦める)。
     */
    private boolean canClearObstruction(BlockPos pos) {
        if (failedClearing.contains(pos) || clearingAttempts >= MAX_CLEARING_ATTEMPTS) {
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

    /**
     * OBSTRUCTION_CLEARINGフェーズの1tick分の処理(硬さ・道具依存の進捗計算、ひび割れ表示)。
     * 採掘対象への移動中・液体設置場所への移動中のどちらから来た場合でも、この1箇所で処理する。
     */
    private void tickObstructionClearing() {
        Level level = target.level();
        EntityPlayerActionPack ap = ((ServerPlayerInterface) target).getActionPack();
        ap.lookAt(Vec3.atCenterOf(obstructionClearPos));

        if (level.isEmptyBlock(obstructionClearPos)) {
            // 狙いを定めている間に何らかの理由で既に無くなっていた: 破壊完了扱いで移動を再開する
            level.destroyBlockProgress(target.getId(), obstructionClearPos, -1);
            resumeMovingAfterObstructionClear();
            return;
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
            if (obstructionBreakProgress >= 1.0f && BlockMiningUtil.destroyBlockAndCollect(target, level, obstructionClearPos)) {
                placedScaffolds.remove(obstructionClearPos);
                cleanupTargets.remove(obstructionClearPos);
                destroyedCount++;
                target.causeFoodExhaustion(MINING_EXHAUSTION);
            }
            resumeMovingAfterObstructionClear();
        }
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

    private void stopBot() {
        ((ServerPlayerInterface) target).getActionPack().stopMovement();
        target.setJumping(false);
        navigator.release(); // 開けたままのドア/ゲートを閉じる
    }
}
