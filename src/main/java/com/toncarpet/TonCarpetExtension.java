package com.toncarpet;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import com.toncarpet.rules.TonCarpetSettings;

import java.util.Map;

/**
 * このMod(Ton Carpet)をCarpet本家の拡張として登録するためのクラス。
 *
 * 独自の SettingsManager(=独自の /toncarpet のような別コマンド)を作るのではなく、
 * Carpet本体が使っている CarpetServer.settingsManager に直接 parseSettingsClass() することで、
 * TonCarpetSettings に定義した @Rule フィールドが本家の "/carpet" コマンドそのものに
 * 統合される(別コマンドとして分かれることがない)。
 *
 * 注意: Carpetはルールの説明文をMinecraft標準の言語ファイル(assets 以下の lang フォルダ)からは
 * 自動で読み込まない。canHasTranslations() をオーバーライドして、キーと説明文の対応表を
 * 直接返す必要がある(そうしないと "No language key provided" で世界の読み込みに失敗する)。
 */
public class TonCarpetExtension implements CarpetExtension {

    // canHasTranslations() は呼ばれるたびに毎回組み立てるのではなく、
    // 起動時に1度だけ構築して使い回す(内容は不変なのでキャッシュして問題ない)。
    // キーのプレフィックスは、登録先である CarpetServer.settingsManager の識別子 "carpet" に合わせる
    // (独自の識別子 "ton-carpet" ではなく、本家と同じ "carpet.rule.xxx.desc" でないと見つからない)。
    private static final Map<String, String> TRANSLATIONS_EN = Map.ofEntries(
            // カテゴリ名と、補足説明(/carpet <ルール名> で desc の下に出る行)
            Map.entry("carpet.category.TonCarpet", "Ton Carpet"),
            Map.entry("carpet.rule.commandPlayerActionmine.extra.0", "This is a subcommand of /player, so the user also needs access to /player itself (see the commandPlayer rule)."),
            Map.entry("carpet.rule.commandPlayerActionbuild.extra.0", "This is a subcommand of /player, so the user also needs access to /player itself (see the commandPlayer rule)."),
            Map.entry("carpet.rule.commandHat.extra.0", "The default is ops (operators only). Use true to allow everyone, or false to disable it."),
            Map.entry("carpet.rule.commandHat.desc", "Who can use the /hat command: true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Puts one item from your main hand on your head and returns your old headwear to your inventory (with an empty hand it takes the headwear off). Not allowed with Curse of Binding on your current headwear, a totem of undying, or a filled shulker box."),
            Map.entry("carpet.rule.commandSit.desc", "Who can use the /sit command: true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Sit down on an invisible seat while standing on the ground; sneak to stand up."),
            Map.entry("carpet.rule.netherPortalInEnd.desc", "If true, lighting flint & steel on an obsidian frame in the End creates a working Nether portal, just like in the Overworld/Nether."),
            Map.entry("carpet.rule.itemFrameInvisibleRename.desc", "If true, naming an item frame \"invisible\" with a name tag hides its background (the item inside stays visible)."),
            Map.entry("carpet.rule.commandEnderChest.desc", "Who can use /enderChest (or /ec): true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Opens your own ender chest even without a real one."),
            Map.entry("carpet.rule.commandCraftingTable.desc", "Who can use /craftingtable (or /ct): true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Opens a crafting table even without a real one."),
            Map.entry("carpet.rule.noTrialSpawnerCooldown.desc", "If true, trial spawners with a wool block directly below them skip their cooldown period (it becomes 1 tick) and can be reactivated immediately."),
            Map.entry("carpet.rule.vaultUnlimitedRewards.desc", "If true, vaults can be opened by the same player any number of times, ignoring the normal once-per-player limit."),
            Map.entry("carpet.rule.commandPlayerActionmine.desc", "Who can use the /player <name> mine subcommand: true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Switching it to false also stops any mining that is running."),
            Map.entry("carpet.rule.commandPlayerActionbuild.desc", "Who can use the /player <name> build <file> [region <regionName>] subcommand: true (everyone), ops (operators), 0-4 (that permission level or higher), or false (nobody). Builds a schematic currently loaded in Litematica (singleplayer / same game folder only). Switching it to false also stops any build that is running.")
    );

    private static final Map<String, String> TRANSLATIONS_JA = Map.ofEntries(
            // カテゴリ名と、補足説明(/carpet <ルール名> で desc の下に出る行)
            Map.entry("carpet.category.TonCarpet", "Ton Carpet"),
            Map.entry("carpet.rule.commandPlayerActionmine.extra.0", "/player のサブコマンドなので、/player そのものを使える権限(commandPlayer ルール)も必要です。"),
            Map.entry("carpet.rule.commandPlayerActionbuild.extra.0", "/player のサブコマンドなので、/player そのものを使える権限(commandPlayer ルール)も必要です。"),
            Map.entry("carpet.rule.commandHat.extra.0", "初期値は ops(OPのみ)です。全員に許可するときは true、使えなくするときは false にします。"),
            Map.entry("carpet.rule.commandHat.desc", "/hat コマンドを使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。メインハンドのアイテムを1個だけ頭に被り、今被っていたものは持ち物へ戻ります(手が空なら、被っているものを外します)。今の頭の装備に束縛の呪いがかかっている・不死のトーテム・中身の入ったシュルカーボックスのときは使えません。"),
            Map.entry("carpet.rule.commandSit.desc", "/sit コマンドを使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。地面の上に立っているときに、透明な椅子に座ります。スニークで立ち上がります。"),
            Map.entry("carpet.rule.netherPortalInEnd.desc", "trueの場合、エンドで黒曜石の枠にフリント&スチールで着火すると、オーバーワールド/ネザーと同じようにネザーポータルが機能します。"),
            Map.entry("carpet.rule.itemFrameInvisibleRename.desc", "trueの場合、額縁に名札で「invisible」と名付けると、額縁の背景が非表示になります(中のアイテムは表示されたままです)。"),
            Map.entry("carpet.rule.commandEnderChest.desc", "/enderChest(または /ec)を使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。本物のエンダーチェストが無くても自分専用のエンダーチェストを開けます。"),
            Map.entry("carpet.rule.commandCraftingTable.desc", "/craftingtable(または /ct)を使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。本物の作業台が無くても作業台を開けます。"),
            Map.entry("carpet.rule.noTrialSpawnerCooldown.desc", "trueの場合、真下に羊毛ブロックがある試練の湧き出し場は、クールダウンを1tickに短縮してすぐ再起動できます。"),
            Map.entry("carpet.rule.vaultUnlimitedRewards.desc", "trueの場合、同じプレイヤーが金庫を何度でも開けられるようになります(通常の「1人1回まで」の制限を無視します)。"),
            Map.entry("carpet.rule.commandPlayerActionmine.desc", "/player <名前> mine サブコマンドを使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。falseにすると、動作中のmineも止まります。"),
            Map.entry("carpet.rule.commandPlayerActionbuild.desc", "/player <名前> build <ファイル名> [region <リージョン名>] サブコマンドを使える人: true(全員)、ops(OP)、0〜4(その権限レベル以上)、false(誰も使えない)。Litematicaで読み込み中のスキーマティックを建築します(シングルプレイ・Litematicaと同じゲームフォルダの場合のみ)。falseにすると、動作中のbuildも止まります。")
    );

    @Override
    public void onGameStarted() {
        // Carpet本体の /carpet 用 SettingsManager に、このModのルールをそのまま登録する
        CarpetServer.settingsManager.parseSettingsClass(TonCarpetSettings.class);
    }

    @Override
    public Map<String, String> canHasTranslations(String lang) {
        return "ja_jp".equalsIgnoreCase(lang) ? TRANSLATIONS_JA : TRANSLATIONS_EN;
    }
}
