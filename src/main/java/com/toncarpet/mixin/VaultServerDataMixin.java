package com.toncarpet.mixin;

import com.toncarpet.rules.TonCarpetSettings;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.vault.VaultServerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.UUID;

/**
 * The "already rewarded" check is duplicated in two places:
 *
 * 1. hasRewardedPlayer(player) - checked directly by tryInsertKey().
 * 2. getRewardedPlayers() - the raw set, checked by
 *    VaultSharedData#updateConnectedPlayersWithinRange() to decide who
 *    counts as a "connected player", which in turn drives the vault's
 *    automatic INACTIVE <-> ACTIVE state cycling (and its visuals).
 *
 * We need to override both, or the vault's normal tick-driven state machine
 * never sees the player as "connected" again and stays INACTIVE forever,
 * even though hasRewardedPlayer() alone would have let a manual key-insert
 * through.
 */
@Mixin(VaultServerData.class)
public class VaultServerDataMixin {

    @Inject(method = "hasRewardedPlayer", at = @At("HEAD"), cancellable = true)
    private void toncarpet$unlimitedRewards(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (TonCarpetSettings.vaultUnlimitedRewards) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getRewardedPlayers", at = @At("HEAD"), cancellable = true)
    private void toncarpet$noRewardedPlayers(CallbackInfoReturnable<Set<UUID>> cir) {
        if (TonCarpetSettings.vaultUnlimitedRewards) {
            cir.setReturnValue(Set.of());
        }
    }
}
