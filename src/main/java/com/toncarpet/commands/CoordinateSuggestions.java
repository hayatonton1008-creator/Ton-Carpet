package com.toncarpet.commands;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Locale;

/**
 * mine / build の座標引数(x y z)のタブ補完。
 *
 * コマンド実行者が見ているブロックの座標を、本家の座標補完(~ ~ ~)と同じ考え方で出す:
 *   - 1つ目(x)の位置では「x y z」を3つまとめて1つの候補として出す(Tabで一度に全部入る)。
 *     x だけの候補も出すので、1つずつ順番に入力していくこともできる。
 *   - 2つ目(y)の位置では、残りの「y z」をまとめた候補と、y だけの候補を出す。
 *   - 3つ目(z)の位置では z だけを出す。
 * ブロックを見ていない場合は何も出さない。入力途中の文字で始まる候補だけを出す。
 */
public final class CoordinateSuggestions {

    private CoordinateSuggestions() {
    }

    /** axisIndex: この引数が3つ組の何番目か(0=x, 1=y, 2=z)。 */
    public static SuggestionProvider<CommandSourceStack> lookedAt(int axisIndex) {
        return (context, builder) -> {
            BlockPos pos = lookedAtBlock(context.getSource());
            if (pos != null) {
                int[] values = {pos.getX(), pos.getY(), pos.getZ()};
                String typed = builder.getRemaining().toLowerCase(Locale.ROOT);

                if (axisIndex < 2) {
                    StringBuilder rest = new StringBuilder();
                    for (int i = axisIndex; i < 3; i++) {
                        if (i > axisIndex) {
                            rest.append(' ');
                        }
                        rest.append(values[i]);
                    }
                    suggestIfMatches(builder, rest.toString(), typed);
                }
                suggestIfMatches(builder, String.valueOf(values[axisIndex]), typed);
            }
            return builder.buildFuture();
        };
    }

    /** 候補を何も出さないプロバイダ(手入力はできるが、補完は出したくない位置に使う)。 */
    public static SuggestionProvider<CommandSourceStack> none() {
        return (context, builder) -> builder.buildFuture();
    }

    private static void suggestIfMatches(SuggestionsBuilder builder, String candidate, String typedLowerCase) {
        if (candidate.toLowerCase(Locale.ROOT).startsWith(typedLowerCase)) {
            builder.suggest(candidate);
        }
    }

    /** コマンド実行者が視線の先(20ブロックまで)で見ているブロックの座標。見ていなければ null。 */
    private static BlockPos lookedAtBlock(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (!(entity instanceof Player player)) {
            return null;
        }
        HitResult hit = player.pick(20.0, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return ((BlockHitResult) hit).getBlockPos();
    }
}
