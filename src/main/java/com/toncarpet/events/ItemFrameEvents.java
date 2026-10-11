package com.toncarpet.events;

import com.toncarpet.rules.TonCarpetSettings;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Rename the ITEM FRAME ITEM to "invisible" on an anvil, then place it, and
 * its background disappears - the item inside stays fully visible. Vanilla
 * already transfers an item's custom name onto the entity it spawns, so we
 * just react to that when the frame loads into the world.
 *
 * Naming it something else (before placing) leaves it visible as normal.
 * Only active while the "itemFrameInvisibleRename" rule is true - see TonCarpetSettings.
 */
public class ItemFrameEvents {

    private static final String INVISIBLE_KEYWORD = "invisible";

    public static void registerEvents() {
        // ENTITY_LOAD は全エンティティ(モブやアイテム含む)のロードごとに呼ばれるため、
        // 軽い判定から順に行い、該当しないものはすぐ抜ける。
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (!TonCarpetSettings.itemFrameInvisibleRename) return;
            if (!(entity instanceof ItemFrame itemFrame)) return;
            if (itemFrame.isInvisible()) return; // すでに不可視なら何もしない

            // 「invisible」と名付けられた額縁だけを不可視にする。
            // 名前が違う額縁の不可視を解除しない(他の手段で不可視にされた額縁を勝手に戻さないため)。
            Component name = itemFrame.getCustomName();
            if (name != null && INVISIBLE_KEYWORD.equalsIgnoreCase(name.getString())) {
                itemFrame.setInvisible(true);
            }
        });
    }
}
