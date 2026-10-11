package com.toncarpet.mixin;

import com.toncarpet.rules.TonCarpetSettings;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's BaseFireBlock (the parent class of FireBlock and SoulFireBlock)
 * has a private method:
 *
 *   private static boolean inPortalDimension(Level level) {
 *       return level.dimension() == Level.OVERWORLD || level.dimension() == Level.NETHER;
 *   }
 *
 * This is the ONLY place that decides whether lighting flint & steel on an
 * obsidian frame is allowed to turn into a working Nether portal. We inject
 * at the very start (HEAD) and, if our rule is on and we're in the End,
 * immediately return true - overriding vanilla's decision.
 */
@Mixin(BaseFireBlock.class)
public class FireBlockMixin {

    @Inject(method = "inPortalDimension", at = @At("HEAD"), cancellable = true)
    private static void toncarpet$allowEndNetherPortal(Level level, CallbackInfoReturnable<Boolean> cir) {
        if (TonCarpetSettings.netherPortalInEnd && level.dimension() == Level.END) {
            cir.setReturnValue(true);
        }
    }
}
