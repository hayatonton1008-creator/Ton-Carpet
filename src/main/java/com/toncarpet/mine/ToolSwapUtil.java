package com.toncarpet.mine;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 手に持っている道具が壊れる寸前になったら、新しいものに持ち替える(botの道具の管理)。
 *
 * Tweakeroo の「tweakSwapAlmostBrokenTools」(壊れそうな道具を新しいものに持ち替える機能)と同じ仕組みで、
 * メインハンドとオフハンドの両方を、毎tick確認する:
 *   1. 残り耐久値が「しきい値」以下になったら、持ち物の中から、同じ種類で、しきい値より耐久値が残っていて、
 *      同じような(シルクタッチ・幸運が下がらない)エンチャントの道具を探して、手に持ち替える。
 *   2. 無ければ、持ち物の空きスロットと入れ替えて、壊れそうな道具を手から外す(手が空になる)。
 *   3. 空きも無ければ、耐久値の無いアイテム(ダミー)と入れ替えて、壊れそうな道具を手から外す。
 *   4. どれも無ければ、そのまま使い続ける。
 * しきい値は、{@link BotFeatures#getDurabilityThreshold()}(初期値20)。ただし、耐久値の最大が100以下の道具は、
 * しきい値が最大値の8%を超えないようにする。エンチャントの無い道具も、エンチャント付きと同じように持ち替える。
 * この機能全体は、他のMODから {@link BotFeatures#setToolSwapEnabled(boolean)} で切り替える(Carpetのルールは無い)。
 *
 * 壊れそうな道具を手から外したあと、道具選び(MiningJob/BuildJob の equipBestToolFor)が、
 * その道具をまた手に持ってしまわないよう、そちらも {@link #isLowDurability(ItemStack)} で同じ基準を使う。
 */
public final class ToolSwapUtil {

    // 耐久値の最大が少ない道具で、しきい値がこの割合を超えないようにする
    private static final double SMALL_TOOL_MAX_FRACTION = 0.08;

    private ToolSwapUtil() {
    }

    /** メインハンドとオフハンドの、壊れそうな道具を持ち替える。毎tick呼ぶ。notice には持ち替えたことを知らせる。 */
    public static void trySwapAlmostBrokenTools(ServerPlayer bot, Consumer<Component> notice) {
        if (!BotFeatures.isToolSwapEnabled()) {
            return;
        }
        if (bot.containerMenu != bot.inventoryMenu) {
            return; // 別の画面(チェスト等)を開いている間は、持ち物の入れ替えをしない
        }
        trySwapHand(bot, InteractionHand.MAIN_HAND, notice);
        trySwapHand(bot, InteractionHand.OFF_HAND, notice);
    }

    /** 残り耐久値が少なく、そろそろ壊れそうな道具か(耐久値の概念が無いアイテムは常に false)。 */
    public static boolean isLowDurability(ItemStack stack) {
        return !stack.isEmpty() && isItemAtLowDurability(stack, getMinDurability(stack));
    }

    /**
     * botの所持品の中から、このブロックに最も効果的な道具を探し、手に持ち替える(無ければ何もしない)。
     * mine と build が共通で使う。
     *   - ブロックが「正しい道具でないとドロップしない」もの(鉱石・石など)なら、正しい道具を優先する
     *     (金のツルハシのように、掘る速さは速くても、鉄鉱石などはドロップしない道具を選ばないため)。
     *   - 同じ条件の道具の間では、掘る速さが最も速いものを選ぶ。
     *   - 残り耐久値が少ない(壊れそうな)道具は、他に選べるものがある限り避ける。
     */
    public static void equipBestToolFor(ServerPlayer bot, BlockState state) {
        Inventory inventory = bot.getInventory();
        boolean needsCorrectTool = state.requiresCorrectToolForDrops();
        int bestSlot = -1;
        boolean bestIsCorrect = !needsCorrectTool; // 素手の基準(正しい道具が要らないブロックなら、素手も「正しい」)
        float bestSpeed = 1.0f; // 素手の基準速度。これを上回る道具が見つかれば持ち替える

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || isLowDurability(stack)) {
                continue; // 壊れそうな道具は使わない(持ち替えで手から外したものを、また持ってしまわないように)
            }
            float speed = stack.getDestroySpeed(state);
            boolean correct = !needsCorrectTool || stack.isCorrectToolForDrops(state);
            boolean better = (correct && !bestIsCorrect) || (correct == bestIsCorrect && speed > bestSpeed);
            if (better) {
                bestIsCorrect = correct;
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        // 耐久値に余裕のある道具が見つかればそれを使う。無ければ素手のまま
        if (bestSlot < 0 || bestSlot == inventory.getSelectedSlot()) {
            return; // 素手より効果的な道具が無い、または既に持っている
        }
        // 手動でスロットを入れ替えるのではなく、バニラの「ホットバーと入れ替える」標準処理を使う
        // (これによりアイテムが消えたり重複したりする心配がない)
        inventory.pickSlot(bestSlot);
    }

    // ------------------------------------------------------------------

    private static void trySwapHand(ServerPlayer bot, InteractionHand hand, Consumer<Component> notice) {
        ItemStack stack = bot.getItemInHand(hand);
        if (stack.isEmpty()) {
            return;
        }
        int minDurability = getMinDurability(stack);
        if (isItemAtLowDurability(stack, minDurability)) {
            swapItemWithHigherDurabilityToHand(bot, hand, stack, minDurability + 1, notice);
        }
    }

    private static boolean isItemAtLowDurability(ItemStack stack, int minDurability) {
        return stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= minDurability;
    }

    /** 「この残り耐久値以下なら持ち替える」というしきい値。0 なら、壊れるまで持ち替えない。 */
    private static int getMinDurability(ItemStack stack) {
        if (!BotFeatures.isToolSwapEnabled()) {
            return 0;
        }
        int threshold = BotFeatures.getDurabilityThreshold();
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 100 && threshold <= 20 && (double) threshold / maxDamage > SMALL_TOOL_MAX_FRACTION) {
            threshold = (int) Math.ceil(maxDamage * SMALL_TOOL_MAX_FRACTION);
        }
        return threshold;
    }

    private static void swapItemWithHigherDurabilityToHand(ServerPlayer bot, InteractionHand hand, ItemStack stack,
                                                           int minDurability, Consumer<Component> notice) {
        AbstractContainerMenu menu = bot.inventoryMenu;
        String name = bot.getScoreboardName();

        int slot = findSlotWithSuitableReplacementToolWithDurabilityLeft(menu, stack, minDurability);
        if (slot != -1) {
            swapItemToHand(bot, hand, slot);
            notice.accept(Component.literal(name + ": 耐久値の少ない道具を、耐久値の多いものに持ち替えました"));
            return;
        }

        slot = findEmptySlotInPlayerInventory(menu);
        if (slot != -1) {
            swapItemToHand(bot, hand, slot);
            notice.accept(Component.literal(name + ": 耐久値の少ない道具を手から外しました"));
            return;
        }

        slot = findSuitableSlot(menu, candidate -> !candidate.isDamageableItem());
        if (slot != -1) {
            swapItemToHand(bot, hand, slot);
            notice.accept(Component.literal(name + ": 耐久値の少ない道具を、ダミーのアイテムに持ち替えました"));
        }
    }

    /** 持ち物(メイン9〜35・ホットバー36〜44。オフハンドは除く)から、持ち替え先になる道具のスロット番号を探す。無ければ -1。 */
    private static int findSlotWithSuitableReplacementToolWithDurabilityLeft(AbstractContainerMenu menu, ItemStack reference,
                                                                            int minDurability) {
        for (int i = 9; i < 45 && i < menu.slots.size(); i++) {
            ItemStack candidate = menu.getSlot(i).getItem();
            if (ItemStack.isSameItem(candidate, reference)
                    && candidate.getMaxDamage() - candidate.getDamageValue() >= minDurability
                    && hasSameIshEnchantments(reference, candidate)) {
                return i;
            }
        }
        return -1;
    }

    /** 持ち物(オフハンドは除く)の最初の空きスロット番号。無ければ -1。 */
    private static int findEmptySlotInPlayerInventory(AbstractContainerMenu menu) {
        for (int i = 9; i < 45 && i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** 持ち物とオフハンド(9番以降のすべて)から、条件に合う最初のスロット番号を探す。無ければ -1。 */
    private static int findSuitableSlot(AbstractContainerMenu menu, Predicate<ItemStack> predicate) {
        for (int i = 9; i < menu.slots.size(); i++) {
            if (predicate.test(menu.getSlot(i).getItem())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 持ち替え先が、元の道具と同じような(シルクタッチ・幸運が下がらない)エンチャントか。
     * 元の道具にシルクタッチがあれば、同じかそれ以上のシルクタッチ。無くて幸運があれば、同じかそれ以上の幸運。どちらも無ければ何でもよい。
     */
    private static boolean hasSameIshEnchantments(ItemStack reference, ItemStack candidate) {
        int level = enchantmentLevel(reference, Enchantments.SILK_TOUCH);
        if (level > 0) {
            return enchantmentLevel(candidate, Enchantments.SILK_TOUCH) >= level;
        }
        level = enchantmentLevel(reference, Enchantments.FORTUNE);
        if (level > 0) {
            return enchantmentLevel(candidate, Enchantments.FORTUNE) >= level;
        }
        return true;
    }

    private static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            if (enchantment.is(key)) {
                return enchantments.getLevel(enchantment);
            }
        }
        return 0;
    }

    /**
     * 指定したスロットの道具を手に持つ。
     *   メインハンド: そのスロットがホットバーなら、選択スロットをそこへ切り替える。それ以外(持ち物の上の段)なら、
     *                 今選択しているホットバーのスロットと中身を入れ替える。
     *   オフハンド  : そのスロットとオフハンドの中身を入れ替える。
     */
    private static void swapItemToHand(ServerPlayer bot, InteractionHand hand, int slot) {
        AbstractContainerMenu menu = bot.inventoryMenu;
        Inventory inventory = bot.getInventory();
        if (hand == InteractionHand.MAIN_HAND) {
            if (slot >= 36 && slot < 45) {
                inventory.setSelectedSlot(slot - 36);
            } else {
                menu.clicked(slot, inventory.getSelectedSlot(), ClickType.SWAP, bot);
            }
        } else {
            menu.clicked(slot, 40, ClickType.SWAP, bot);
        }
        menu.broadcastChanges();
    }
}
