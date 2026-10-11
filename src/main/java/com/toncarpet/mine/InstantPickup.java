package com.toncarpet.mine;

import net.minecraft.server.level.ServerPlayer;

/**
 * "/player mine" "/player build" がブロックを破壊している間、そのドロップを地面に落とさず
 * 直接インベントリへ振り分けるための目印。essentialCarefulDrop(EssentialAddons)の
 * ThreadLocalによる「今誰が破壊しているか」の記録と同じ考え方。
 *
 * BlockMiningUtil.destroyBlockAndCollect が破壊の直前にセットし、直後に必ずクリアする。
 * 実際のドロップの振り分けは com.toncarpet.mixin.BlockPopResourceMixin が
 * Block.popResource(生成された各ドロップが必ず通る最下層のメソッド)をフックして行う。
 */
public final class InstantPickup {

    private static final ThreadLocal<ServerPlayer> CURRENT_COLLECTOR = new ThreadLocal<>();

    private InstantPickup() {
    }

    public static void set(ServerPlayer player) {
        // 他のMODから careful break が OFF にされている間は、目印を付けない(ドロップは通常どおり地面へ落ちる)
        if (!BotFeatures.isCarefulBreakEnabled()) {
            CURRENT_COLLECTOR.remove();
            return;
        }
        CURRENT_COLLECTOR.set(player);
    }

    public static void clear() {
        CURRENT_COLLECTOR.remove();
    }

    public static ServerPlayer get() {
        return CURRENT_COLLECTOR.get();
    }
}
