package com.toncarpet.mixin;

import carpet.helpers.EntityPlayerActionPack;
import com.toncarpet.build.BuildManager;
import com.toncarpet.mine.MiningManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Carpet本家の "/player <名前> stop" は内部で EntityPlayerActionPack.stopAll() を呼んでいる。
 * ここに割り込み、そのプレイヤーが実行中の mine ジョブがあれば一緒に停止させる。
 *
 * 注意1: stopAll() はフェイクプレイヤー生成時のコンストラクタからも呼ばれるが、
 * その時点ではまだ mine / build ジョブは存在しないので何も起きず問題ない。
 * 注意2: stopAll() は void ではなく EntityPlayerActionPack 自身を返す
 * (メソッドチェーン用)ため、@Inject のハンドラは CallbackInfoReturnable を使う必要がある。
 */
@Mixin(EntityPlayerActionPack.class)
public class ActionPackStopMixin {

    @Shadow
    private ServerPlayer player;

    @Inject(method = "stopAll", at = @At("TAIL"))
    private void toncarpet$onStopAll(CallbackInfoReturnable<EntityPlayerActionPack> cir) {
        if (player == null) {
            return;
        }
        MiningManager.cancelJobsFor(player.getUUID());
        BuildManager.cancelJobsFor(player.getUUID());
    }
}
