package com.toncarpet.mine;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * プレイヤー(bot)が実際に歩ける経路を、ブロック単位のグリッド上でA*探索する。
 *
 * ノード = プレイヤーが立てるブロック座標(足元)。
 * 移動の種類:
 *   - 水平4方向+斜め4方向(斜めは、両隣のマスも体ごと通れるときだけ。角を削ると当たり判定で引っかかるため)
 *   - 1段登る(頭上にジャンプできる空間があるときだけ)
 *   - 最大3段まで飛び降りる(落下ダメージが出ない高さ。高い所ほど遠回りより不利になるようコストを上げる)
 *   - 閉じた木のドア・フェンスゲートを開けて通る(ボットが通ったあと閉じ直す。開閉は BotNavigator が行う)
 *   - 橋を架ける(allowBridge=true のときだけ。仮ブロックがもったいないので重いコストを掛け、
 *     「他に歩ける道が無いときの最後の手段」になるようにしている)
 *   - その場で足場を積んで1段登る(allowPillar=true のときだけ。ジャンプしながら足元に仮ブロックを置く。
 *     高い建築の上のほうに手を届かせるための手段で、橋と同じく重いコストを掛けている)
 *   - 足元のブロックを壊して1段降りる(diggable が「壊してよい」とした位置だけ。自分が積んだ足場の塔から降りたり、
 *     建築・採掘の範囲内で下へ掘り進んだりするための手段。壊すのに時間がかかるので、歩いて降りられるときは使わない)
 *
 * 当たり判定の高さを見て「立てる/通れる」を決める:
 *   - フェンス・壁・フェンスゲートは見た目は1マスでも当たり判定が1.5マス分ある。ジャンプ(約1.25マス)では
 *     登れないので、床としては使わず、通り道としても使わない(迂回する)。
 *   - カーペット・下半分のハーフブロックなど、低い当たり判定(0.6マス以下)は段差として自動で乗れるので、足元のマスとして通れる。
 *   - 溶岩・火・マグマブロック・サボテン・スイートベリー・クモの巣などの危険なマスは通らない。
 *
 * 最適化(重み付きA* + 判定結果のキャッシュ):
 * ヒューリスティックに軽い重み(HEURISTIC_WEIGHT)を掛けることで探索をゴール方向へ絞り込み、
 * ノード展開数を大きく減らしている。1回の探索中は各マスの判定結果をキャッシュして、
 * 隣接ノード同士で同じマスを何度も判定し直さない。また、読み込まれていないチャンクは
 * 判定しない(遠くの探索でチャンクロードを引き起こさないため)。
 */
public class PathFinder {

    // 探索するノード数の上限。これを超えたら経路なし(=直線移動にフォールバック)として諦める。
    private static final int MAX_NODES = 5000;
    // ヒューリスティックに掛ける重み(> 1.0 で「ゴールに近そうな方向」を強く優先し、探索を高速化する)
    private static final double HEURISTIC_WEIGHT = 1.3;
    // ゴールは対象ブロックの周囲数マスに広がっているので、その分だけ距離の見積もりを甘くする(過大評価を避ける)
    private static final double HEURISTIC_SLACK = 3.0;

    // 落下ダメージを受けずに飛び降りられる最大の高さ
    private static final int MAX_FALL = 3;
    private static final double[] FALL_COST = {0.0, 0.2, 1.0, 2.0};
    private static final double STEP_UP_EXTRA_COST = 0.5;
    // 橋を1マス架けるコスト(歩く1マス分に加算)。この値ぶんの遠回りまでなら、橋を作らずに歩いて迂回する。
    private static final double BRIDGE_PENALTY = 6.0;
    private static final double OPEN_DOOR_COST = 1.5;
    // 足場を積んで1段登るコスト(歩く1マス分に加算)。橋と同じく、歩いて行ける道があればそちらが選ばれる。
    private static final double PILLAR_PENALTY = 6.0;

    // 足元のマスとして「段差として自動で乗れる」当たり判定の高さの上限(プレイヤーの段差の高さ 0.6)
    private static final double LOW_COLLISION_MAX = 0.6;
    // 床として扱える当たり判定の高さの範囲(ソウルサンド 0.875 〜 通常のブロック 1.0)。
    // フェンスや壁(1.5)は範囲外なので、その上には立てない。
    private static final double FLOOR_TOP_MIN = 0.875;
    private static final double FLOOR_TOP_MAX = 1.0 + 1.0E-6;

    // マスの分類
    private static final int BLOCKED = 0;   // 通れない(固体・危険物)
    private static final int FREE = 1;      // 何も無い(当たり判定なし)
    private static final int OPENABLE = 2;  // 閉じているが、開けて通れる(木のドア・フェンスゲート)
    private static final int LOW = 3;       // 低い当たり判定(カーペット等)。足元としてなら通れる

    // 移動の種類(経路の各マスがどうやって到達されるか)
    private static final int KIND_WALK = 0;
    private static final int KIND_BRIDGE = 1;
    private static final int KIND_OPEN = 2;
    private static final int KIND_PILLAR = 3;
    private static final int KIND_DIG = 4;

    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * 探索結果。
     *   waypoints  ... start自身を含まない、次に進むべきマス以降の並び
     *   bridgeTiles... waypointsのうち「床が無いので、到着前に足元へ仮ブロックを置く必要があるマス」
     *   openTiles  ... waypointsのうち「閉じたドア/ゲートを開ける必要があるマス」
     *   pillarTiles... waypointsのうち「真下(自分が今いるマス)に足場を置いて登る先のマス」
     *   digTiles   ... waypointsのうち「その位置にある自分の仮ブロックを壊して降りる先のマス」
     */
    public record PathResult(List<BlockPos> waypoints, Set<BlockPos> bridgeTiles, Set<BlockPos> openTiles,
                             Set<BlockPos> pillarTiles, Set<BlockPos> digTiles) {}

    /**
     * 探索の条件。
     *   allowBridge ... 橋を架ける移動を候補にするか
     *   allowPillar ... 足場を積んで登る移動を候補にするか(どちらも仮ブロックを消費する)
     *   reserved    ... 「これから建築物のブロックを置く予定のマス」かどうか(null可)。
     *                   そこには立たない(置く場所に体が入ってしまい、置けなくなるのを防ぐ)
     *   diggable    ... その位置のブロックを壊して1段降りるコスト。壊してはいけない位置・壊せない位置は負の値(null可)
     */
    public record Options(boolean allowBridge, boolean allowPillar, Predicate<BlockPos> reserved,
                          ToDoubleFunction<BlockPos> diggable) {
        public static final Options NONE = new Options(false, false, null, null);
    }

    private record Node(BlockPos pos, double g, double f, Node parent, int kind) {}

    private record Candidate(BlockPos pos, double cost, int kind) {}

    // ------------------------------------------------------------------
    // 公開API
    // ------------------------------------------------------------------

    /**
     * start から goals のいずれかまで歩ける経路を探す。
     * heuristicTarget は、A*のヒューリスティック計算に使う代表点(通常は対象ブロックの座標そのもの)。
     * avoid に含まれるマスは(たとえ立てるマスであっても)通らない。
     * options で、橋・足場積み・足場を壊しての降下を候補にするかなどを指定する。
     * 見つからなければ null。
     */
    public static PathResult findPath(Level level, BlockPos start, Set<BlockPos> goals, BlockPos heuristicTarget,
                                      Set<BlockPos> avoid, Options options) {
        if (goals.isEmpty()) {
            return null;
        }
        if (goals.contains(start)) {
            return new PathResult(new ArrayList<>(), Set.of(), Set.of(), Set.of(), Set.of());
        }

        SearchContext ctx = new SearchContext(level, options);
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        // 座標を long(BlockPos.asLong)にして、プリミティブ専用のコレクションで管理する
        // (BlockPos や Double を箱詰めして生成せず、探索中のメモリ確保とGCを減らす)
        Long2DoubleOpenHashMap bestG = new Long2DoubleOpenHashMap();
        bestG.defaultReturnValue(Double.MAX_VALUE);
        LongOpenHashSet closed = new LongOpenHashSet();
        List<Candidate> candidates = new ArrayList<>(12);

        open.add(new Node(start, 0, heuristic(start, heuristicTarget), null, KIND_WALK));
        bestG.put(start.asLong(), 0.0);

        int visited = 0;
        while (!open.isEmpty() && visited < MAX_NODES) {
            Node current = open.poll();
            if (!closed.add(current.pos().asLong())) {
                continue; // 既に確定済みのノード
            }
            visited++;

            if (goals.contains(current.pos())) {
                return reconstruct(current);
            }

            neighbors(ctx, current.pos(), candidates);
            for (Candidate cand : candidates) {
                long candKey = cand.pos().asLong();
                if (closed.contains(candKey) || avoid.contains(cand.pos())) {
                    continue;
                }
                double newG = current.g() + cand.cost();
                if (newG < bestG.get(candKey)) {
                    bestG.put(candKey, newG);
                    double f = newG + heuristic(cand.pos(), heuristicTarget) * HEURISTIC_WEIGHT;
                    open.add(new Node(cand.pos(), newG, f, current, cand.kind()));
                }
            }
        }

        return null; // 見つからなかった(または探索上限に達した)
    }

    /**
     * targetBlock に「手が届く」位置(目の位置から対象ブロック中心までの距離が maxEyeDistance 以内)に立てるマスを、
     * すべてゴール候補として集める。隣接マスだけでなく届く範囲全体をゴールにすることで、
     * 対象が地形の内側にあっても遠くから届くなら近づかずに済み、歩く距離も橋の数も減る。
     * includeAir が true なら、床の無い空中のマス(足場を積めば立てる場所)も候補に含める。
     */
    public static Set<BlockPos> goalsWithinReach(Level level, BlockPos targetBlock, Set<BlockPos> avoid, double maxEyeDistance,
                                                 Predicate<BlockPos> reserved, boolean includeAir) {
        Set<BlockPos> goals = new HashSet<>();
        SearchContext ctx = new SearchContext(level, Options.NONE);
        int r = (int) Math.ceil(maxEyeDistance);
        double maxSq = maxEyeDistance * maxEyeDistance;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -r - 1; dy <= r; dy++) {
                    // 立っているマスの足元から目までの高さは約1.62。対象ブロックの中心は +0.5。
                    double ey = dy + 1.12;
                    if (dx * dx + ey * ey + dz * dz > maxSq) {
                        continue;
                    }
                    BlockPos c = targetBlock.offset(dx, dy, dz);
                    // 対象のマス自身に体が重なる位置には立たない(設置するマスに立ってしまわないように)
                    if (c.equals(targetBlock) || c.above().equals(targetBlock)) {
                        continue;
                    }
                    if (avoid.contains(c)) {
                        continue;
                    }
                    // これから建築物のブロックを置く予定のマスには立たない(体が入ると、そこへ置けなくなる)
                    if (reserved != null && (reserved.test(c) || reserved.test(c.above()))) {
                        continue;
                    }
                    int standable = ctx.standable(c.getX(), c.getY(), c.getZ());
                    if (standable == 1) {
                        goals.add(c);
                    } else if (includeAir && standable == 0 && ctx.bodyClear(c.getX(), c.getY(), c.getZ())) {
                        // まだ床が無い空中のマス。歩いて行ける場所が無いときに限って、足場を積んで登る先の候補にする
                        goals.add(c);
                    }
                }
            }
        }

        // 他に候補があるなら、対象ブロックの真上(=掘るブロックを踏んで立つ位置)は避ける
        if (goals.size() > 1) {
            goals.remove(targetBlock.above());
        }
        return goals;
    }

    /**
     * 仮ブロック(橋・足場)をそこに置けるか。空気だけでなく、水・溶岩・草・落ち葉・雪など、
     * ブロックを置くと置き換わる「置き換え可能」なブロックでもよい(バニラと同じ)。
     * 以前は空気だけを置ける場所として扱っていたため、水の中や草・落ち葉の上に立っていると足場を置けなかった。
     */
    public static boolean canPlaceScaffoldAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /** そのマスにプレイヤーが立てるか(足元が床で、体2マス分の空間があるか。開けられるドア/ゲートも可)。 */
    public static boolean isStandable(Level level, BlockPos pos) {
        return new SearchContext(level, Options.NONE).standable(pos.getX(), pos.getY(), pos.getZ()) != 0;
    }

    /** 閉じている木のドア・フェンスゲート(ボットが手で開けられるもの)かどうか。 */
    public static boolean isClosedOpenable(BlockState state) {
        if (state.getBlock() instanceof FenceGateBlock) {
            return !state.getValue(BlockStateProperties.OPEN);
        }
        if (state.getBlock() instanceof DoorBlock && state.is(BlockTags.WOODEN_DOORS)) {
            return !state.getValue(BlockStateProperties.OPEN);
        }
        return false;
    }

    /**
     * ahead(足元の高さのマス)へ踏み出したとき、落下ダメージを受けない範囲(3マス以内)に安全な着地先があるか。
     * 橋を架けるかどうかの判断に使う。着地先があるなら橋は不要(飛び降りれば済む)。
     */
    public static boolean hasSafeLanding(Level level, BlockPos ahead) {
        SearchContext ctx = new SearchContext(level, Options.NONE);
        for (int k = 1; k <= MAX_FALL + 1; k++) {
            BlockPos below = ahead.below(k);
            BlockState state = level.getBlockState(below);
            if (isDangerous(state)) {
                return false;
            }
            if (!state.getFluidState().isEmpty()) {
                return true; // 水に着水する(溶岩は上で除外済み)
            }
            if (ctx.isFloor(below.getX(), below.getY(), below.getZ())) {
                return true;
            }
            if (!state.getCollisionShape(level, below).isEmpty()) {
                return false; // 床としては使えない当たり判定(フェンス等)に当たる
            }
        }
        return false;
    }

    /**
     * 足元のブロック(support)を壊したとき、落下ダメージを受けない範囲(3マス以内)に着地できる床があるか。
     * 仮ブロックの後片付けで、自分が立っているブロックを壊して高所から落ちないための判定に使う。
     */
    public static boolean canDropSafely(Level level, BlockPos support) {
        for (int k = 1; k <= MAX_FALL; k++) {
            BlockPos below = support.below(k);
            BlockState state = level.getBlockState(below);
            if (isDangerous(state)) {
                return false;
            }
            if (!state.getFluidState().isEmpty()) {
                return true; // 水に着水する(溶岩は上で除外済み)
            }
            if (!state.getCollisionShape(level, below).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 探索の内部
    // ------------------------------------------------------------------

    private static void neighbors(SearchContext ctx, BlockPos p, List<Candidate> out) {
        out.clear();
        int px = p.getX();
        int py = p.getY();
        int pz = p.getZ();

        for (int[] d : DIRECTIONS) {
            int dx = d[0];
            int dz = d[1];
            boolean diagonal = dx != 0 && dz != 0;
            double horiz = diagonal ? 1.4142 : 1.0;
            int nx = px + dx;
            int nz = pz + dz;

            // 斜め移動は、角を削って通ると当たり判定で引っかかるので、両隣のマスも体ごと通れることを要求する
            if (diagonal && (!ctx.bodyClear(nx, py, pz) || !ctx.bodyClear(px, py, nz))) {
                continue;
            }

            // 1. 同じ高さで移動(閉じたドア/ゲートは、正面から通るとき=斜めでないときだけ)
            int same = ctx.standable(nx, py, nz);
            if (same == 1 || (same == 2 && !diagonal)) {
                boolean needsOpen = same == 2;
                out.add(new Candidate(new BlockPos(nx, py, nz),
                        horiz + (needsOpen ? OPEN_DOOR_COST : 0.0), needsOpen ? KIND_OPEN : KIND_WALK));
                continue;
            }

            // 2. 1段登る(今いる場所の頭上にジャンプできる空間が必要。斜めなら登った先の両隣も通れること)
            if (ctx.classify(px, py + 2, pz) == FREE
                    && (!diagonal || (ctx.bodyClear(nx, py + 1, pz) && ctx.bodyClear(px, py + 1, nz)))) {
                if (ctx.standable(nx, py + 1, nz) == 1) {
                    out.add(new Candidate(new BlockPos(nx, py + 1, nz), horiz + STEP_UP_EXTRA_COST, KIND_WALK));
                    continue;
                }
            }

            // 3. 飛び降りる(最大 MAX_FALL マス。踏み出すマスと落下する筋道が空いていること)
            if (ctx.bodyClear(nx, py, nz)) {
                for (int k = 1; k <= MAX_FALL; k++) {
                    if (ctx.standable(nx, py - k, nz) == 1) {
                        out.add(new Candidate(new BlockPos(nx, py - k, nz), horiz + FALL_COST[k], KIND_WALK));
                        break;
                    }
                    if (ctx.classify(nx, py - k, nz) != FREE) {
                        break; // その先へは落ちていけない
                    }
                }
            }

            // 4. 橋を架ける(4方向のみ。歩ける道より必ず高くつくようにして、本当に必要なときだけ選ばれる)
            if (ctx.options.allowBridge() && !diagonal
                    && ctx.classify(nx, py, nz) == FREE && ctx.classify(nx, py + 1, nz) == FREE
                    && ctx.isBridgeFloorSpot(nx, py - 1, nz)) {
                out.add(new Candidate(new BlockPos(nx, py, nz), horiz + BRIDGE_PENALTY, KIND_BRIDGE));
            }
        }

        // 5. その場で足場を積んで1段登る。ジャンプして、体が1マス上がったところで、今いるマスに仮ブロックを置く。
        //    登った先の頭の高さまで空いていること(ジャンプの余裕も含む)と、そこが建築予定のマスでないことが条件。
        if (ctx.options.allowPillar()
                && ctx.classify(px, py + 1, pz) == FREE && ctx.classify(px, py + 2, pz) == FREE
                && !ctx.isReserved(px, py + 1, pz) && !ctx.isReserved(px, py + 2, pz)
                && ctx.isScaffoldBaseSpot(px, py, pz)) {
            out.add(new Candidate(new BlockPos(px, py + 1, pz), 1.0 + PILLAR_PENALTY, KIND_PILLAR));
        }

        // 6. 足元のブロックを壊して1段降りる(その下にさらに床があるときだけ。壊した先でそのまま立てる)
        if (ctx.options.diggable() != null && ctx.isFloor(px, py - 2, pz)) {
            double digCost = ctx.options.diggable().applyAsDouble(p.below());
            if (digCost >= 0) {
                out.add(new Candidate(p.below(), digCost, KIND_DIG));
            }
        }
    }

    private static PathResult reconstruct(Node end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        Set<BlockPos> bridges = new HashSet<>();
        Set<BlockPos> opens = new HashSet<>();
        Set<BlockPos> pillars = new HashSet<>();
        Set<BlockPos> digs = new HashSet<>();
        Node cur = end;
        while (cur.parent() != null) { // startノード自身は含めない
            path.addFirst(cur.pos());
            if (cur.kind() == KIND_BRIDGE) {
                bridges.add(cur.pos());
            } else if (cur.kind() == KIND_OPEN) {
                opens.add(cur.pos());
            } else if (cur.kind() == KIND_PILLAR) {
                pillars.add(cur.pos());
            } else if (cur.kind() == KIND_DIG) {
                digs.add(cur.pos());
            }
            cur = cur.parent();
        }
        return new PathResult(new ArrayList<>(path), bridges, opens, pillars, digs);
    }

    private static double heuristic(BlockPos pos, BlockPos target) {
        return Math.max(0.0, Math.sqrt(pos.distSqr(target)) - HEURISTIC_SLACK);
    }

    /** 危険なブロック(溶岩・火・マグマ・サボテン・ベリー・クモの巣など)。体が入るマスにも、床にも使わない。 */
    private static boolean isDangerous(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA)
                || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.COBWEB)
                || state.is(Blocks.POWDER_SNOW);
    }

    /**
     * 1回の探索の間だけ使う判定キャッシュ。同じマスを何度も getBlockState / 当たり判定の計算をしないようにする。
     * キーは BlockPos.asLong (オブジェクトを生成せずに済む)。
     */
    private static final class SearchContext {
        private final Level level;
        final Options options;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 判定結果のキャッシュ(キー=BlockPos.asLong、値=0〜3の小さな数)。使われるまで作らない
        // (hasSafeLanding / isStandable のような単発の判定で、毎回マップを確保しないため)。-1 = 未計算
        private Long2ByteOpenHashMap classCache;
        private Long2ByteOpenHashMap standCache;

        SearchContext(Level level, Options options) {
            this.level = level;
            this.options = options;
        }

        /** これから建築物のブロックを置く予定のマスか。 */
        boolean isReserved(int x, int y, int z) {
            return options.reserved() != null && options.reserved().test(new BlockPos(x, y, z));
        }

        /** マスの分類(BLOCKED / FREE / OPENABLE / LOW)。 */
        int classify(int x, int y, int z) {
            if (classCache == null) {
                classCache = new Long2ByteOpenHashMap();
                classCache.defaultReturnValue((byte) -1);
            }
            long key = BlockPos.asLong(x, y, z);
            byte cached = classCache.get(key);
            if (cached >= 0) {
                return cached;
            }
            int value = computeClass(x, y, z);
            classCache.put(key, (byte) value);
            return value;
        }

        private int computeClass(int x, int y, int z) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (isDangerous(state)) {
                return BLOCKED;
            }
            // 開いているドアは、板が縁に寄るだけで体は通れる(当たり判定は残っているが通り道として扱う)
            if (state.getBlock() instanceof DoorBlock && state.getValue(BlockStateProperties.OPEN)) {
                return FREE;
            }
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (shape.isEmpty()) {
                return FREE;
            }
            if (isClosedOpenable(state)) {
                return OPENABLE;
            }
            if (shape.max(Direction.Axis.Y) <= LOW_COLLISION_MAX) {
                return LOW;
            }
            return BLOCKED;
        }

        /** 足元が床として使えるか(当たり判定の高さがほぼ1マスで、危険物でないこと)。 */
        boolean isFloor(int x, int y, int z) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (isDangerous(state)) {
                return false;
            }
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (shape.isEmpty()) {
                return false;
            }
            double top = shape.max(Direction.Axis.Y);
            return top >= FLOOR_TOP_MIN && top <= FLOOR_TOP_MAX;
        }

        /** 橋の床を置けるマスか(置き換え可能で、当たり判定が無い=今は床として使えないマス)。 */
        boolean isBridgeFloorSpot(int x, int y, int z) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            return (state.isAir() || state.canBeReplaced()) && state.getCollisionShape(level, cursor).isEmpty();
        }

        /** 足場(柱)の土台になる、今いるマス自身に仮ブロックを置けるか(置き換え可能か)。 */
        boolean isScaffoldBaseSpot(int x, int y, int z) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            return state.isAir() || state.canBeReplaced();
        }

        /** 体ごと通れるか(足元は空きか低い段差、頭の高さは空き)。ドア類は含まない。 */
        boolean bodyClear(int x, int y, int z) {
            int feet = classify(x, y, z);
            return (feet == FREE || feet == LOW) && classify(x, y + 1, z) == FREE;
        }

        /** そのマスに立てるか。0=立てない / 1=立てる / 2=閉じたドア・ゲートを開ければ立てる。 */
        int standable(int x, int y, int z) {
            if (standCache == null) {
                standCache = new Long2ByteOpenHashMap();
                standCache.defaultReturnValue((byte) -1);
            }
            long key = BlockPos.asLong(x, y, z);
            byte cached = standCache.get(key);
            if (cached >= 0) {
                return cached;
            }
            int value = computeStandable(x, y, z);
            standCache.put(key, (byte) value);
            return value;
        }

        private int computeStandable(int x, int y, int z) {
            cursor.set(x, y, z);
            if (!level.hasChunkAt(cursor)) {
                return 0; // 読み込まれていないチャンクは調べない(チャンクロードを引き起こさない)
            }
            int feet = classify(x, y, z);
            int head = classify(x, y + 1, z);
            if (feet == BLOCKED || head == BLOCKED || head == LOW) {
                return 0;
            }
            // 足元が低い段差の上に立つと頭の位置も少し上がるので、その上も空いている必要がある
            if (feet == LOW && classify(x, y + 2, z) != FREE) {
                return 0;
            }
            if (!isFloor(x, y - 1, z)) {
                return 0;
            }
            return (feet == OPENABLE || head == OPENABLE) ? 2 : 1;
        }
    }
}
