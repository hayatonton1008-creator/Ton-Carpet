package com.toncarpet.rules;

import carpet.api.settings.CarpetRule;
import carpet.api.settings.Validator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * ルール(コマンドの有効/無効を切り替えるもの)の値が変わった直後、
 * 全プレイヤーへコマンドツリーを再送信する。
 *
 * Minecraftはログイン時にコマンドツリー(タブ補完で使われる一覧)をクライアントへ
 * 一度だけ送って以降キャッシュするため、実行時の .requires() チェック自体は
 * 正しく更新されていても、再送信しない限りタブ補完には反映されない
 * (「trueにしても補完に出ないが、コマンドは実行できる」不具合の原因)。
 *
 * 同じtick内で複数のルールが変わっても、再送信(全プレイヤー分のコマンドツリー構築+送信)は
 * 1回にまとめる。
 */
public class CommandTreeResyncValidator extends Validator<String> {

    // すでに再送信を予約済みのサーバー。サーバー単位で持つことで、予約が実行されないまま
    // サーバーが停止しても(シングルプレイで別ワールドに入り直しても)次回の予約を妨げない。
    private static MinecraftServer queuedFor = null;

    /** サーバー停止時に、予約中のサーバーへの参照を手放す(ワールドを出入りしても古いサーバーを抱え続けないため)。 */
    public static void registerEvents() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> queuedFor = null);
    }

    @Override
    public String validate(CommandSourceStack source, CarpetRule<String> changingRule, String newValue, String userInput) {
        requestResync(source);
        return newValue;
    }

    /**
     * 全プレイヤーへのコマンドツリー再送信を予約する(同じtick内の複数回は1回にまとめる)。
     * コマンドのルールの値が変わるときに呼ぶ。
     */
    public static void requestResync(CommandSourceStack source) {
        // Carpetは、サーバー停止時にルールを初期値へ戻すときなど、コマンドの実行者(source)が無い状態で
        // バリデータを呼ぶことがある。そのときはクライアントへ再送信する相手も居ないので、何もしない。
        if (source == null) {
            return;
        }
        MinecraftServer server = source.getServer();
        if (server == null || !server.isRunning()) {
            return; // 停止処理中・起動途中は再送信しない
        }

        if (queuedFor != server) {
            queuedFor = server;

            // この時点ではまだルールの実際の値が新しい値に書き換わっていない可能性があるため、
            // 現在のtickの処理が終わった後(値が確実に更新された後)に再送信するよう1回遅らせる
            server.execute(() -> {
                queuedFor = null;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    server.getCommands().sendCommands(player);
                }
            });
        }
    }
}
