package com.toncarpet.mixin;

import com.toncarpet.mine.InstantPickup;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * バニラの Block.popResource(Level, BlockPos, ItemStack) は、通常のブロック破壊はもちろん、
 * dropResources・playerDestroy・爆発など、ブロックのドロップが最終的にワールドへ
 * アイテムエンティティとして出現するあらゆる経路が必ず通る、最下層の静的ユーティリティである。
 *
 * ここをHEADでフックし、InstantPickup に「今このプレイヤーが破壊している」という目印が
 * セットされていれば、そのドロップをワールドへ落とす前にプレイヤーの所持品へ直接入れる。
 * 全量が入りきればワールドには何も出現させない(呼び出し自体をキャンセルする)。
 * 入りきらなかった分だけ、stack(参照先は同じオブジェクトなので、減らした個数がそのまま
 * 反映される)を携えてバニラの処理へ進ませ、通常通りワールドへ落とす。
 *
 * essentialCarefulDrop(EssentialAddons)の「ThreadLocalで破壊者を記録し、ドロップ時に
 * まずインベントリへ振り分ける」という設計を、ブロック採掘向けに同じ考え方で実装したもの。
 */
@Mixin(Block.class)
public class BlockPopResourceMixin {

    @Inject(
            method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void toncarpet$redirectToInventory(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
        ServerPlayer collector = InstantPickup.get();
        if (collector == null || stack.isEmpty()) {
            return;
        }

        collector.getInventory().add(stack);
        if (stack.isEmpty()) {
            ci.cancel(); // 全部インベントリに収まった: ワールドには何も落とさない
        }
        // 入りきらなかった分が残っている場合は、そのままバニラの処理へ進ませてワールドへ落とす
    }
}
