package com.toncarpet.rules;

import carpet.api.settings.CarpetRule;
import carpet.api.settings.Validator;
import com.toncarpet.build.BuildManager;
import net.minecraft.commands.CommandSourceStack;

/**
 * /player <名前> build のルールの値が変わるときの処理。
 *   - コマンドツリーを全プレイヤーへ再送信する(タブ補完へすぐ反映させる)
 *   - 値が "false"(誰も使えない)になったら、動作中の build をすべて止める
 *     (ルールを切ったのに、すでに動いているbotが作業を続けないようにする)
 */
public class BuildCommandValidator extends Validator<String> {

    @Override
    public String validate(CommandSourceStack source, CarpetRule<String> changingRule, String newValue, String userInput) {
        if (CommandAccess.isDisabled(newValue)) {
            BuildManager.stopAllJobs();
        }
        CommandTreeResyncValidator.requestResync(source);
        return newValue;
    }
}
