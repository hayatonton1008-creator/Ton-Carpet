package com.toncarpet.rules;

import carpet.api.settings.CarpetRule;
import carpet.api.settings.Validator;
import com.toncarpet.mine.MiningManager;
import net.minecraft.commands.CommandSourceStack;

/**
 * /player <名前> mine のルールの値が変わるときの処理。
 *   - コマンドツリーを全プレイヤーへ再送信する(タブ補完へすぐ反映させる)
 *   - 値が "false"(誰も使えない)になったら、動作中の mine をすべて止める
 *     (ルールを切ったのに、すでに動いているbotが作業を続けないようにする)
 */
public class MineCommandValidator extends Validator<String> {

    @Override
    public String validate(CommandSourceStack source, CarpetRule<String> changingRule, String newValue, String userInput) {
        if (CommandAccess.isDisabled(newValue)) {
            MiningManager.stopAllJobs();
        }
        CommandTreeResyncValidator.requestResync(source);
        return newValue;
    }
}
