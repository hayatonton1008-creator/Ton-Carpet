package com.toncarpet.mine;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * "/player mine" と "/player build" の両方が、橋を架けたり柱を登ったりするために
 * 仮に設置してよいブロックの一覧。
 *
 * ここでは一覧を絞り込むだけに留めている(何を許可するかのデフォルト値と、それを問い合わせるAPIのみ)。
 * 他のMODが独自の判定ロジックを持ち込みたい場合に備えて、{@link #allow(Block)} で一覧に追加できるように
 * 公開してあるが、この一覧の持ち方自体(単純なSetによる許可制)は今後変わる可能性がある。
 */
public final class ScaffoldBlocks {

    private static final Set<Block> ALLOWED = new LinkedHashSet<>();

    static {
        ALLOWED.add(Blocks.PALE_MOSS_BLOCK);
        ALLOWED.add(Blocks.MOSS_BLOCK);
    }

    private ScaffoldBlocks() {
    }

    /** このブロックを仮ブロックとして使ってよいか。 */
    public static boolean isAllowed(Block block) {
        return ALLOWED.contains(block);
    }

    /** 許可リストへブロックを追加する(他のMODから拡張するためのAPI)。 */
    public static void allow(Block block) {
        ALLOWED.add(block);
    }

    /** 現在許可されているブロックの一覧(読み取り専用)。 */
    public static Set<Block> allowedBlocks() {
        return Collections.unmodifiableSet(ALLOWED);
    }
}
