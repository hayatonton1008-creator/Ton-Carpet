package com.toncarpet.mine;

import carpet.fakes.ServerPlayerInterface;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * mine と build が共通で使う、botの自動食事。
 * 空腹ゲージが閾値以下なら、所持している食料を手に持ち、動かずに時間をかけて(約1.6秒)食べる。
 * 食料が無ければ何もしない。ジョブごとに1つ作り、毎tick {@link #tick()} を呼ぶ。
 */
public final class BotEater {

    // 空腹ゲージがこの値以下になったら、所持している食料を自動で食べる(20点満点中)
    private static final int HUNGER_EAT_THRESHOLD = 14;
    // 食事にかける時間(バニラの通常食料と同じ約1.6秒=32tick)。この間は動かない。
    private static final int EAT_DURATION_TICKS = 32;
    // 連続で一気に食べ過ぎないよう、食べ終わってから次に食べるまで空ける間隔(1秒)
    private static final int EAT_COOLDOWN_TICKS = 20;

    private final ServerPlayer bot;
    private int cooldown = 0;
    private boolean eating = false;
    private int ticksRemaining = 0;

    public BotEater(ServerPlayer bot) {
        this.bot = bot;
    }

    /** 毎tick呼ぶ。食事中(=他の行動を止めておくべき間)なら true を返す。 */
    public boolean tick() {
        if (eating) {
            ((ServerPlayerInterface) bot).getActionPack().stopMovement();
            bot.setJumping(bot.isInWater() || bot.isInLava()); // 液体の中では、浮かぶためにジャンプし続ける
            ticksRemaining--;
            if (ticksRemaining > 0) {
                return true; // まだ食べている最中
            }
            finishEating();
            return false;
        }

        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        if (bot.getFoodData().getFoodLevel() >= HUNGER_EAT_THRESHOLD) {
            return false;
        }

        int slot = findFoodSlot();
        if (slot < 0) {
            return false; // 食料を持っていない
        }

        bot.getInventory().pickSlot(slot); // 食料を実際に手に持つ
        ((ServerPlayerInterface) bot).getActionPack().stopMovement();
        eating = true;
        ticksRemaining = EAT_DURATION_TICKS;
        return true;
    }

    /** 食事の時間が経過したら実際に食べる(空腹回復・アイテム消費・腕振り)。 */
    private void finishEating() {
        eating = false;
        cooldown = EAT_COOLDOWN_TICKS;

        ItemStack stack = bot.getInventory().getSelectedItem();
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null) {
            return; // 食事中に持ち替えられたなど、想定外の場合は諦める
        }

        bot.getFoodData().eat(food.nutrition(), food.saturation());
        stack.shrink(1);
        bot.swing(InteractionHand.MAIN_HAND, true);
    }

    /** 食料アイテムが入っているスロット番号。無ければ -1。 */
    private int findFoodSlot() {
        Inventory inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.has(DataComponents.FOOD)) {
                return i;
            }
        }
        return -1;
    }
}
