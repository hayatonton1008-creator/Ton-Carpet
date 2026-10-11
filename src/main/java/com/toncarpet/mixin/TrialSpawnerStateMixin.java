package com.toncarpet.mixin;

import com.toncarpet.rules.TonCarpetSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects every call to TrialSpawner#getTargetCooldownLength() made from
 * within TrialSpawnerState#tickAndGetNext(). Unlike TrialSpawner itself,
 * tickAndGetNext receives the spawner's BlockPos and ServerLevel as
 * parameters, so - unlike our old approach - we can actually check the
 * block directly below the spawner from here.
 *
 * If the "noTrialSpawnerCooldown" rule is on AND there's a wool block right
 * below the trial spawner, the cooldown becomes effectively instant
 * (1 tick). Otherwise, the original vanilla value is used, unchanged.
 */
@Mixin(TrialSpawnerState.class)
public class TrialSpawnerStateMixin {

    @Redirect(
            method = "tickAndGetNext",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/trialspawner/TrialSpawner;getTargetCooldownLength()I"
            )
    )
    private int toncarpet$conditionalNoCooldown(
            TrialSpawner trialSpawner,      // the receiver of the redirected call (trialSpawner.getTargetCooldownLength())
            BlockPos blockPos,              // tickAndGetNext's own 1st parameter
            TrialSpawner trialSpawnerParam, // tickAndGetNext's own 2nd parameter (same object as the receiver above)
            ServerLevel serverLevel         // tickAndGetNext's own 3rd parameter
    ) {
        // ルールがOFFの間はブロック参照(getBlockState / below() のBlockPos生成)を一切行わず、
        // 毎tick呼ばれるこの経路のコストをバニラと同等に保つため、ルール判定を先に行う。
        if (TonCarpetSettings.noTrialSpawnerCooldown
                && serverLevel.getBlockState(blockPos.below()).is(BlockTags.WOOL)) {
            return 1;
        }

        return trialSpawner.getTargetCooldownLength();
    }
}
