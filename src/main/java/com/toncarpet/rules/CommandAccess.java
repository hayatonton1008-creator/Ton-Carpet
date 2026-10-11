package com.toncarpet.rules;

import carpet.utils.CommandHelper;
import net.minecraft.commands.CommandSourceStack;

/**
 * コマンドのルールの値(true / false / ops / 0〜4)から、そのコマンドの実行者が使えるかを判定する。
 *
 * Carpet本家のコマンドのルールと同じ規則で判定する(本家の CommandHelper に任せる):
 *   true  ... 全員が使える
 *   false ... 誰も使えない
 *   ops   ... OP(権限レベル2以上)だけが使える
 *   0〜4  ... その権限レベル以上の人だけが使える
 *
 * 判定を1か所にまとめてあるのは、Carpetのバージョンによって判定の関数が変わっても、
 * このファイルだけを直せば済むようにするため。
 */
public final class CommandAccess {

    private CommandAccess() {
    }

    public static boolean canUse(CommandSourceStack source, String ruleValue) {
        return CommandHelper.canUseCommand(source, ruleValue);
    }

    /** ルールの値が「誰も使えない」("false")か。 */
    public static boolean isDisabled(String ruleValue) {
        return "false".equalsIgnoreCase(ruleValue);
    }
}
