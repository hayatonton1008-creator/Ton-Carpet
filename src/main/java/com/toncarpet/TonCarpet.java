package com.toncarpet;

import net.fabricmc.api.ModInitializer;

import carpet.CarpetServer;
import com.toncarpet.commands.CraftingTableCommand;
import com.toncarpet.commands.EnderChestCommand;
import com.toncarpet.commands.HatCommand;
import com.toncarpet.commands.SitCommand;
import com.toncarpet.build.BuildManager;
import com.toncarpet.events.ItemFrameEvents;
import com.toncarpet.mine.MiningManager;
import com.toncarpet.rules.CommandTreeResyncValidator;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TonCarpet implements ModInitializer {
	public static final String MOD_ID = "ton-carpet";

	// コンソールとログファイルへ書き出すためのロガー(名前はMOD ID)。
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Carpet本家の拡張として登録する。これによりこのModのルールが本家の
		// "/carpet" コマンドに統合される(Carpetの起動より前に呼ぶ必要がある)。
		CarpetServer.manageExtension(new TonCarpetExtension());

		// /hat と /sit コマンドを登録する。
		HatCommand.registerCommands();
		SitCommand.registerCommands();
		CommandTreeResyncValidator.registerEvents();

		// 額縁に名札で「invisible」と名付けると、背景が非表示になる。
		ItemFrameEvents.registerEvents();

		// /enderChest (/ec) と /craftingtable (/ct) コマンドを登録する。
		EnderChestCommand.registerCommands();
		CraftingTableCommand.registerCommands();

		// "/player <名前> mine ..." が予約したジョブを毎tick進める。
		MiningManager.registerEvents();

		// "/player <名前> build ..." が予約したジョブを毎tick進める。
		BuildManager.registerEvents();
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

