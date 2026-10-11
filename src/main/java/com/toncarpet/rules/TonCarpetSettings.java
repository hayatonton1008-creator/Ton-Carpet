package com.toncarpet.rules;

import carpet.api.settings.Rule;

/**
 * このMod(Ton Carpet)のルール定義。
 *
 * 独自の別システムではなく、Carpet本家が提供している公式の @Rule アノテーションを使う。
 * こうすることで、本家の "/carpet" コマンド(タブ補完も含む)からそのまま扱えるようになる。
 * 以前は独自の /carpet コマンドを別途登録していたが、これが本家の /carpet と名前が
 * 衝突し(ログの "Failed to add settings command for carpet. It is masking previous command."
 * の原因)、本家の他のルールが補完に出てこなくなる不具合を起こしていた。
 *
 * 注意: このバージョンの @Rule は category(単数)ではなく categories(複数形)を使う。
 */
public class TonCarpetSettings {

    // コマンドのルールの値は Carpet 本家と同じ: true(全員) / false(誰も使えない) / ops(OP) / 0〜4(その権限レベル以上)。
    // 値を変更した瞬間に全プレイヤーへコマンドツリーを再送信し、タブ補完へすぐ反映させる。
    // 判定は CommandAccess.canUse(...) で行う。

    // /hat コマンドを使える人
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = CommandTreeResyncValidator.class)
    public static String commandHat = "ops";

    // /sit コマンドを使える人
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = CommandTreeResyncValidator.class)
    public static String commandSit = "ops";

    // エンドで黒曜石の枠にフリント&スチールで着火するとネザーポータルが機能するかどうか
    @Rule(categories = "TonCarpet")
    public static boolean netherPortalInEnd = false;

    // 額縁に名札で"invisible"と名付けると背景が非表示になるかどうか
    @Rule(categories = "TonCarpet")
    public static boolean itemFrameInvisibleRename = false;

    // /enderChest (/ec) コマンドを使える人
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = CommandTreeResyncValidator.class)
    public static String commandEnderChest = "ops";

    // /craftingtable (/ct) コマンドを使える人
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = CommandTreeResyncValidator.class)
    public static String commandCraftingTable = "ops";

    // 試練の湧き出し場のクールダウンをスキップするかどうか
    @Rule(categories = "TonCarpet")
    public static boolean noTrialSpawnerCooldown = false;

    // 金庫を同じプレイヤーが何度でも開けられるようにするかどうか
    @Rule(categories = "TonCarpet")
    public static boolean vaultUnlimitedRewards = false;

    // /player <名前> mine ... サブコマンドを使える人(無効にすると、動作中のmineも止まる)
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = MineCommandValidator.class)
    public static String commandPlayerActionmine = "ops";

    // /player <名前> build ... サブコマンドを使える人(無効にすると、動作中のbuildも止まる)
    @Rule(categories = "TonCarpet", options = {"true", "false", "ops", "0", "1", "2", "3", "4"}, validators = BuildCommandValidator.class)
    public static String commandPlayerActionbuild = "ops";
}
