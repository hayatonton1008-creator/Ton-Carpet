package com.toncarpet.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * mine と build (と mine の液体埋め) が共通で使う「目的のブロックの近くまで歩いていく」ための部品。
 *
 * 以前は各ジョブが、経路の計算・ウェイポイントの消化・橋の設置をそれぞれ持っていて、
 *   - 段差を降りるたびに橋を架けてしまう / 液体を埋めに行くときは経路を使わず直進しながら橋を架け続ける
 *   - 一度計算した経路を、地形が変わっても(自分で床を掘っても)ずっと使い続ける
 * といった無駄が起きていた。ここでは次のように振る舞う:
 *   - 対象に「手が届く」場所までの経路をA*で探す(PathFinder)。経路にある床の無いマスにだけ、
 *     その手前で仮ブロックを置く(計画された橋)。歩ける道があるなら橋は作らない。
 *   - 経路は、次のマスに立てなくなった・大きくコースから外れた・経路が見つからなかった、というときに
 *     一定間隔で探し直す。
 *   - 経路上の閉じた木のドア/フェンスゲートは、近づいたときに開け、通り過ぎて離れたら閉じ直す。
 *   - 経路が全く見つからないとき(空中に浮いた対象へ向かうときなど)だけ、直進しながら
 *     「安全に降りられる床が無い」ときに限って橋を架ける。
 *   - 高い建築の上のほうなど、歩いて行ける場所が無いときは、足場を積んで登る(ジャンプしながら足元に仮ブロックを置く)
 *     経路を計画する。降りるときは、歩いて降りられなければ足元のブロック(自分の足場、または建築・採掘の範囲内の
 *     ブロック)を壊して1段ずつ降りる。壊す作業そのものは、ジョブ側がサバイバルと同じ速度で行う(takePendingDig)。
 *   - 直前に置いたブロックの上へ乗れる状況なら、置いた直後にそこへ登る(startHop / tickHop)。
 */
public final class BotNavigator {

    /** 足元の1マス下などに仮ブロックを置く処理。置けたら true(材料が無いなどで置けなければ false)。 */
    public interface BlockPlacer {
        boolean place(BlockPos pos);
    }

    // 置いた直後にそのブロックへ登る動きの制限時間(tick)
    private static final int HOP_MAX_TICKS = 30;

    // 対象ブロックに手が届くとみなす、目の位置からの距離。ジョブ側の到達判定(4.5)より少し余裕を持たせて、
    // ゴールに着いたのに「まだ届かない」と判定される取りこぼしを防ぐ。
    private static final double GOAL_REACH = 4.0;
    // ウェイポイントに「着いた」とみなす水平距離
    private static final double WAYPOINT_ARRIVAL_DISTANCE = 0.4;
    // 経路を探し直す最短間隔(tick)。探索は重いので、毎tickは行わない。
    private static final int REPLAN_MIN_INTERVAL_TICKS = 10;
    // 経路が見つからない(直線移動中の)ときに、探し直す最初の間隔(tick)。失敗が続くたびに倍にしていき、
    // NO_PATH_RETRY_MAX_TICKS で頭打ちにする(見つからない探索は重いので、同じ失敗を高頻度で繰り返さない)
    private static final int NO_PATH_RETRY_TICKS = 20;
    private static final int NO_PATH_RETRY_MAX_TICKS = 200;
    // 次のウェイポイントからこれ以上離れたら、コースから外れたとみなして探し直す
    private static final double MAX_DEVIATION = 3.5;
    // 橋を架けるマスに、この水平距離まで近づいたら仮ブロックを置く
    private static final double BRIDGE_PLACE_DISTANCE = 1.9;
    // ドア/ゲートに、この水平距離まで近づいたら開ける
    private static final double DOOR_OPEN_DISTANCE = 2.2;
    // 開けたドア/ゲートから、この水平距離より離れたら閉じ直す
    private static final double DOOR_CLOSE_DISTANCE = 2.8;

    private final ServerPlayer bot;
    private final BlockPlacer placer;
    private final BooleanSupplier hasScaffold;
    // これから建築物を置く予定のマスか(null可)。そこには立たない。
    private final Predicate<BlockPos> reserved;
    // その位置のブロックを壊して降りるコスト。壊してはいけない・壊せない位置は負の値(null可)
    private final ToDoubleFunction<BlockPos> diggable;

    // 柵・壁など、詰まったときに「通らない」とみなすマス(別ルートを探させるため)
    private final Set<BlockPos> avoidCells = new HashSet<>();
    // ボットが開けたドア/ゲート(閉じ直すために覚えておく)
    private final List<BlockPos> openedDoors = new ArrayList<>();

    private List<BlockPos> path; // 次に向かうべきマスが先頭。null = 経路なし
    private Set<BlockPos> bridgeTiles = Set.of();
    private Set<BlockPos> openTiles = Set.of();
    private Set<BlockPos> pillarTiles = Set.of();
    private Set<BlockPos> digTiles = Set.of();
    private BlockPos pendingDig = null; // 降りるために、ジョブに壊してもらいたい足元のブロック(takePendingDigで受け取る)
    private List<BlockPos> hopPath; // 置いた直後にそのブロックへ登る短い経路(無ければnull)
    private int hopTicks = 0;
    private BlockPos plannedFor; // path を計算した対象
    private int ticksSincePlan = 0;
    private int noPathRetryTicks = NO_PATH_RETRY_TICKS; // 現在の「経路なし」再探索の間隔(失敗のたびに延びる)
    private boolean forceReplan = false;
    private boolean bridgingDisabled = false; // 仮ブロックを置けなかったら、以降は橋を計画に含めない
    private int waypointsReached = 0; // 前回 pollProgress() を呼んでから、ウェイポイントに到達した回数

    public BotNavigator(ServerPlayer bot, BlockPlacer placer, BooleanSupplier hasScaffold,
                        Predicate<BlockPos> reserved, ToDoubleFunction<BlockPos> diggable) {
        this.bot = bot;
        this.placer = placer;
        this.hasScaffold = hasScaffold;
        this.reserved = reserved;
        this.diggable = diggable;
    }

    /** 対象が変わったとき(次のブロックへ進むとき)に呼ぶ。経路と「通らない」記録を捨てる。 */
    public void reset() {
        path = null;
        plannedFor = null;
        bridgeTiles = Set.of();
        openTiles = Set.of();
        pillarTiles = Set.of();
        digTiles = Set.of();
        hopPath = null;
        avoidCells.clear();
        forceReplan = false;
        bridgingDisabled = false;
        pendingDig = null;
        waypointsReached = 0;
        ticksSincePlan = 0;
        noPathRetryTicks = NO_PATH_RETRY_TICKS;
    }

    /** 経路に沿って歩いている最中か(経路が見つかっていて、まだ残りのウェイポイントがあるか)。 */
    public boolean isFollowingPath() {
        return path != null && !path.isEmpty();
    }

    /**
     * 前回呼んだときからウェイポイントに到達していれば true。
     * 「対象との直線距離が縮まらない」だけでは、遠回りの経路(柵や穴を迂回する等)を進んでいる間も
     * 行き詰まりと誤判定してしまうため、経路上を着実に進んでいることを進歩として扱うために使う。
     */
    public boolean pollProgress() {
        boolean progressed = waypointsReached > 0;
        waypointsReached = 0;
        return progressed;
    }

    /** ボットが point から水平方向に range マス未満の距離にいるか。 */
    public boolean isHorizontallyNear(Vec3 point, double range) {
        return horizontalDistance(bot.position(), point) < range;
    }

    /**
     * 降りるために壊すべき足元のブロックがあれば返す(受け取ると空になる)。
     * ジョブ側は、これを受け取ったら、サバイバルと同じ速度でそのブロックを壊す(障害物を壊す処理と同じ)。
     */
    public BlockPos takePendingDig() {
        BlockPos result = pendingDig;
        pendingDig = null;
        return result;
    }

    /** 次の一手が「その場で足場を積んで登る」か。このとき前へ歩いてはいけない(足場の上から落ちてしまう)。 */
    public boolean isPillarStep() {
        return isFollowingPath() && pillarTiles.contains(path.get(0));
    }

    /**
     * 直前に置いたブロックの上(cell)へ登る短い経路を作る。登れるなら true。
     * 1〜2歩で着ける場所に限る(遠いなら、わざわざ寄り道して登る意味がない)。
     */
    public boolean startHop(BlockPos cell) {
        PathFinder.PathResult r = PathFinder.findPath(bot.level(), bot.blockPosition(), Set.of(cell), cell,
                Set.of(), new PathFinder.Options(false, false, reserved, null));
        if (r == null || r.waypoints().isEmpty() || r.waypoints().size() > 2) {
            return false;
        }
        hopPath = new ArrayList<>(r.waypoints());
        hopTicks = 0;
        return true;
    }

    /** 登っている最中なら、向かうべき地点を返す。登り終えた・時間切れ・登っていないなら null。 */
    public Vec3 tickHop() {
        if (hopPath == null) {
            return null;
        }
        if (++hopTicks > HOP_MAX_TICKS || hopPath.isEmpty()) {
            hopPath = null;
            return null;
        }
        BlockPos wp = hopPath.get(0);
        Vec3 wpVec = Vec3.atBottomCenterOf(wp);
        if (horizontalDistance(bot.position(), wpVec) < 0.35 && bot.getY() >= wp.getY() - 0.05) {
            hopPath.remove(0);
            if (hopPath.isEmpty()) {
                hopPath = null;
                return null;
            }
            wp = hopPath.get(0);
            wpVec = Vec3.atBottomCenterOf(wp);
        }
        return wpVec;
    }

    /** 登っている最中に、今ジャンプが必要か(次のマスが今より高いとき)。 */
    public boolean hopNeedsJump() {
        return hopPath != null && !hopPath.isEmpty() && hopPath.get(0).getY() > bot.blockPosition().getY();
    }

    /** 次のウェイポイントが今より高い位置にあり、ジャンプして登る必要があるか。 */
    public boolean needsStepUp() {
        return isFollowingPath() && path.get(0).getY() > bot.blockPosition().getY();
    }

    /**
     * 毎tick(対象に手が届かず、歩く必要があるときに)呼ぶ。今向かうべき地点を返す。
     * 経路があればそのウェイポイント、無ければ fallbackPoint(対象そのものへ直進)。
     * 必要に応じて経路の探し直し・橋の設置・ドアの開閉も行う。
     */
    public Vec3 update(BlockPos targetBlock, Vec3 fallbackPoint) {
        Level level = bot.level();
        ticksSincePlan++;

        if (!targetBlock.equals(plannedFor)) {
            avoidCells.clear();
            bridgingDisabled = false;
            plan(level, targetBlock);
        } else if (forceReplan) {
            plan(level, targetBlock);
        } else if (!isFollowingPath()) {
            if (ticksSincePlan >= noPathRetryTicks) {
                plan(level, targetBlock);
                // また見つからなければ、次の再探索までの間隔を倍にする(見つかれば plan の中で元に戻る)
                if (!isFollowingPath()) {
                    noPathRetryTicks = Math.min(noPathRetryTicks * 2, NO_PATH_RETRY_MAX_TICKS);
                }
            }
        } else if (ticksSincePlan >= REPLAN_MIN_INTERVAL_TICKS && pathIsBroken(level)) {
            plan(level, targetBlock);
        }

        if (!isFollowingPath()) {
            return fallbackPoint;
        }

        BlockPos waypoint = path.get(0);
        Vec3 waypointVec = Vec3.atBottomCenterOf(waypoint);
        if (hasArrived(level, waypoint, waypointVec)) {
            path.remove(0);
            waypointsReached++;
            if (!path.isEmpty()) {
                waypoint = path.get(0);
                waypointVec = Vec3.atBottomCenterOf(waypoint);
            }
        }
        if (!path.isEmpty()) {
            prepareWaypoint(level, waypoint);
        }
        return waypointVec;
    }

    /**
     * 詰まってしまったときに呼ぶ。今向かっているマスを「通らない」ことにして、次のtickで別ルートを探し直させる。
     */
    public void reroute() {
        if (isFollowingPath()) {
            avoidCells.add(path.get(0));
        } else {
            Vec3 look = bot.getLookAngle();
            int dx = (int) Math.round(look.x);
            int dz = (int) Math.round(look.z);
            if (dx != 0 || dz != 0) {
                avoidCells.add(bot.blockPosition().offset(dx, 0, dz));
            }
        }
        forceReplan = true;
    }

    /**
     * 経路が無く、moveTarget へ直進しているときだけ使う。進行方向の足元に床が無く、
     * しかも落下ダメージを受けない範囲に安全な着地先も無い場合に限って、橋を1マス架ける。
     * (経路があるときは、計画された橋以外は絶対に置かない)
     */
    public void bridgeAheadIfNoPath(Vec3 moveTarget) {
        if (isFollowingPath() || bridgingDisabled) {
            return;
        }
        Vec3 pos = bot.position();
        double vx = moveTarget.x - pos.x;
        double vz = moveTarget.z - pos.z;
        if (Math.abs(vx) < 0.3 && Math.abs(vz) < 0.3) {
            return;
        }
        // 斜めには架けず、より大きく動く軸の方向にまっすぐ架ける(橋が斜めにつながらないようにする)
        int dx = 0;
        int dz = 0;
        if (Math.abs(vx) >= Math.abs(vz)) {
            dx = vx > 0 ? 1 : -1;
        } else {
            dz = vz > 0 ? 1 : -1;
        }

        Level level = bot.level();
        BlockPos ahead = bot.blockPosition().offset(dx, 0, dz);
        BlockPos floor = ahead.below();
        boolean aheadFree = level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
        boolean floorMissing = level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();

        // 仮ブロックを持っているかの確認(所持品の走査)は、本当に置くことになったときだけ行う(毎tick走らせない)
        if (aheadFree && floorMissing && !PathFinder.hasSafeLanding(level, ahead) && hasScaffold.getAsBoolean()) {
            if (PathFinder.canPlaceScaffoldAt(level, floor) && !placer.place(floor)) {
                bridgingDisabled = true; // 材料が無い
            }
        }
    }

    /** 毎tick呼ぶ。通り過ぎて離れたドア/ゲートを閉じ直す。 */
    public void tickDoors() {
        if (openedDoors.isEmpty()) {
            return;
        }
        Level level = bot.level();
        Vec3 pos = bot.position();
        openedDoors.removeIf(p -> {
            boolean near = horizontalDistance(pos, Vec3.atBottomCenterOf(p)) < DOOR_CLOSE_DISTANCE
                    && Math.abs(pos.y - p.getY()) < 3.0;
            if (near) {
                return false;
            }
            closeDoor(level, p);
            return true;
        });
    }

    /** ジョブの終了・中断時に呼ぶ。まだ開けたままのドア/ゲートをすべて閉じる。 */
    public void release() {
        Level level = bot.level();
        for (BlockPos p : openedDoors) {
            closeDoor(level, p);
        }
        openedDoors.clear();
    }

    // ------------------------------------------------------------------

    private void plan(Level level, BlockPos targetBlock) {
        plannedFor = targetBlock;
        forceReplan = false;
        ticksSincePlan = 0;

        // 仮ブロックを持っていれば、橋と足場積みを候補に含める。足元の仮ブロックを壊す降下は、持っていなくても使える。
        boolean scaffold = !bridgingDisabled && hasScaffold.getAsBoolean();
        PathFinder.Options options = new PathFinder.Options(scaffold, scaffold, reserved,
                diggable);

        // まず、今ある床の上に立てる場所だけをゴールにして探す(歩いて行ける道があるなら、それが一番安い)
        Set<BlockPos> goals = PathFinder.goalsWithinReach(level, targetBlock, avoidCells, GOAL_REACH, reserved, false);
        PathFinder.PathResult result = PathFinder.findPath(
                level, bot.blockPosition(), goals, targetBlock, avoidCells, options);

        // 見つからなければ、床の無い空中のマス(足場を積めば立てる場所)もゴールに含めて探し直す
        if (result == null && scaffold) {
            Set<BlockPos> withAir = PathFinder.goalsWithinReach(level, targetBlock, avoidCells, GOAL_REACH, reserved, true);
            result = PathFinder.findPath(level, bot.blockPosition(), withAir, targetBlock, avoidCells, options);
        }

        if (result != null) {
            noPathRetryTicks = NO_PATH_RETRY_TICKS;
        }
        if (result == null) {
            path = null;
            bridgeTiles = Set.of();
            openTiles = Set.of();
            pillarTiles = Set.of();
            digTiles = Set.of();
        } else {
            path = new ArrayList<>(result.waypoints());
            bridgeTiles = result.bridgeTiles();
            openTiles = result.openTiles();
            pillarTiles = result.pillarTiles();
            digTiles = result.digTiles();
        }
    }

    /**
     * ウェイポイントに着いたか。普通のマスは水平距離だけで判定する。
     * 足場を積んで登るマスは、実際に足場の上まで体が上がって足場が置けているまで着いたことにしない。
     * 足元の仮ブロックを壊して降りるマスは、実際に1段降りるまで着いたことにしない。
     */
    private boolean hasArrived(Level level, BlockPos waypoint, Vec3 waypointVec) {
        double horiz = horizontalDistance(bot.position(), waypointVec);
        if (pillarTiles.contains(waypoint)) {
            // 足場が実際に置かれて(当たり判定のあるブロックになって)いることが条件。水や草のままでは足場とは言えない
            BlockPos base = waypoint.below();
            return horiz < 0.5 && bot.getY() >= waypoint.getY() - 0.05
                    && !level.getBlockState(base).getCollisionShape(level, base).isEmpty();
        }
        if (digTiles.contains(waypoint)) {
            return horiz < 0.5 && bot.getY() <= waypoint.getY() + 0.3;
        }
        return horiz < WAYPOINT_ARRIVAL_DISTANCE;
    }

    /** 次のウェイポイントに立てなくなった(床を掘った等)・大きくコースから外れた、ときに true。 */
    private boolean pathIsBroken(Level level) {
        BlockPos waypoint = path.get(0);
        double horiz = horizontalDistance(bot.position(), Vec3.atBottomCenterOf(waypoint));
        if (horiz > MAX_DEVIATION || Math.abs(waypoint.getY() - bot.blockPosition().getY()) > 4) {
            return true;
        }
        if (bridgeTiles.contains(waypoint) || openTiles.contains(waypoint)
                || pillarTiles.contains(waypoint) || digTiles.contains(waypoint)) {
            return false; // まだ床/ドアの準備前でも、計画済みなので正常
        }
        return !PathFinder.isStandable(level, waypoint);
    }

    /** ウェイポイントに近づいたら、計画された橋の仮ブロックを置く / 閉じたドアを開ける。 */
    private void prepareWaypoint(Level level, BlockPos waypoint) {
        double horiz = horizontalDistance(bot.position(), Vec3.atBottomCenterOf(waypoint));

        if (horiz < DOOR_OPEN_DISTANCE) {
            openIfClosed(level, waypoint);
            openIfClosed(level, waypoint.above());
        }

        // 足場を積んで登る: 体が上がって、今いたマスの上端より足が高くなったら、そのマスに仮ブロックを置く
        if (pillarTiles.contains(waypoint) && horiz < 0.6) {
            BlockPos base = waypoint.below();
            if (PathFinder.canPlaceScaffoldAt(level, base) && bot.getY() >= base.getY() + 1.0 - 0.02 && !placer.place(base)) {
                bridgingDisabled = true; // 仮ブロックが無くて置けなかった: 足場積みを含まない経路を探し直させる
                forceReplan = true;
            }
        }

        // 降下: 足元のブロックを壊して1段降りる。壊す作業はジョブ側に任せる(takePendingDig)。
        // 経路を計画した後に状況が変わって(別のブロックに置き換わった等)、壊してはいけないブロックになっていないか、
        // 壊す直前にもう一度確認する。
        if (digTiles.contains(waypoint) && horiz < 0.6 && !level.isEmptyBlock(waypoint)) {
            if (diggable != null && diggable.applyAsDouble(waypoint) >= 0) {
                pendingDig = waypoint;
            } else {
                forceReplan = true; // 壊せなくなっていた: 探し直す(新しい判定では降下の候補から外れる)
            }
        }

        if (horiz < BRIDGE_PLACE_DISTANCE && bridgeTiles.contains(waypoint)) {
            BlockPos floor = waypoint.below();
            if (PathFinder.canPlaceScaffoldAt(level, floor) && !placer.place(floor)) {
                // 仮ブロックが無くて置けなかった: 橋を含まない経路を探し直させる
                bridgingDisabled = true;
                forceReplan = true;
            }
        }
    }

    private void openIfClosed(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!PathFinder.isClosedOpenable(state)) {
            return;
        }
        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, true), 10);
        level.playSound(null, pos,
                state.getBlock() instanceof FenceGateBlock ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.WOODEN_DOOR_OPEN,
                SoundSource.BLOCKS, 1.0F, 1.0F);
        openedDoors.add(pos.immutable());
    }

    private static void closeDoor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // ボットが開けた位置が、その後別のブロックに置き換わっていたら何もしない
        boolean gateOrDoor = state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock;
        if (!gateOrDoor || !state.getValue(BlockStateProperties.OPEN)) {
            return;
        }
        level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, false), 10);
        level.playSound(null, pos,
                state.getBlock() instanceof FenceGateBlock ? SoundEvents.FENCE_GATE_CLOSE : SoundEvents.WOODEN_DOOR_CLOSE,
                SoundSource.BLOCKS, 1.0F, 1.0F);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
