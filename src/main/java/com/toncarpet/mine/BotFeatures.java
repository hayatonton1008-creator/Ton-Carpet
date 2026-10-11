package com.toncarpet.mine;

/**
 * botの動作のうち、他のMODからON/OFFや値を切り替えられる機能の一覧。
 * {@link ScaffoldBlocks} と同じく、他のMODから呼び出せる公開APIとして置いている(Carpetのルールは作っていない)。
 *
 * 初期値は、道具の持ち替え・careful break ともに ON、しきい値は20。
 * 値はサーバーが動いている間だけ保持される(保存はしない)。
 */
public final class BotFeatures {

    /** 持ち替えるしきい値(残りの耐久値)として設定できる範囲。Tweakerooの itemSwapDurabilityThreshold と同じ。 */
    public static final int MIN_DURABILITY_THRESHOLD = 5;
    public static final int MAX_DURABILITY_THRESHOLD = 10000;

    private static volatile boolean toolSwapEnabled = true;
    private static volatile int durabilityThreshold = 20;
    private static volatile boolean carefulBreakEnabled = true;

    private BotFeatures() {
    }

    /** 壊れそうな道具の持ち替え(Tweakerooの tweakSwapAlmostBrokenTools と同じ機能)が有効か。 */
    public static boolean isToolSwapEnabled() {
        return toolSwapEnabled;
    }

    /** 壊れそうな道具の持ち替えを ON/OFF する。 */
    public static void setToolSwapEnabled(boolean enabled) {
        toolSwapEnabled = enabled;
    }

    /** 道具を持ち替える、残りの耐久値(あと何回使えるか)のしきい値。 */
    public static int getDurabilityThreshold() {
        return durabilityThreshold;
    }

    /** しきい値を設定する。範囲({@link #MIN_DURABILITY_THRESHOLD}〜{@link #MAX_DURABILITY_THRESHOLD})の外の値は、範囲内に丸める。 */
    public static void setDurabilityThreshold(int threshold) {
        durabilityThreshold = Math.max(MIN_DURABILITY_THRESHOLD, Math.min(MAX_DURABILITY_THRESHOLD, threshold));
    }

    /** careful break(採掘したブロックのドロップが、すぐ手持ち=インベントリに入る)が有効か。 */
    public static boolean isCarefulBreakEnabled() {
        return carefulBreakEnabled;
    }

    /** careful break を ON/OFF する。OFFの間は、ドロップは通常どおり地面に落ちる。 */
    public static void setCarefulBreakEnabled(boolean enabled) {
        carefulBreakEnabled = enabled;
    }
}
