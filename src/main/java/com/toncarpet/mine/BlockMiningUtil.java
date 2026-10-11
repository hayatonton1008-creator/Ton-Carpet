package com.toncarpet.mine;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * ブロックを破壊した際、本来ワールドに落ちるはずのドロップアイテムを地面に落とさず、
 * 即座にプレイヤーの所持品へ渡すための共通処理。MiningJob・BuildJobの両方から使われる。
 *
 * 実装方針: ブロック破壊自体は通常通り ServerPlayerGameMode.destroyBlock(pos) に任せる
 * (経験値・実績・統計・破壊音/パーティクルなど、バニラの副作用をすべてそのまま活かすため)。
 * ドロップの振り分け先だけを、{@link InstantPickup} で「今このプレイヤーが破壊している」と
 * 目印を立てておくことで、{@link com.toncarpet.mixin.BlockPopResourceMixin} が
 * Block.popResource(ドロップが必ず通る最下層のメソッド)の時点で直接インベントリへ渡す。
 * 破壊した位置の周囲を後から範囲探索する方式(以前の実装)と違い、無関係な近くのアイテムを
 * 誤って拾ってしまう心配が無い(essentialCarefulDrop/EssentialAddonsと同じ設計方針)。
 */
public final class BlockMiningUtil {

    private BlockMiningUtil() {
    }

    /** ブロックを破壊し、そのドロップを即座に手元へ回収する。破壊できた場合は true。 */
    public static boolean destroyBlockAndCollect(ServerPlayer player, Level level, BlockPos pos) {
        InstantPickup.set(player);
        try {
            return player.gameMode.destroyBlock(pos);
        } finally {
            InstantPickup.clear();
        }
    }
}
