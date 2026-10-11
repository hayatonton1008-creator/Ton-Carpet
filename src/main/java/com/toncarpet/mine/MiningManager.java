package com.toncarpet.mine;

import com.toncarpet.TonCarpet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 実行中の "/player <名前> mine ..." ジョブをすべて保持し、
 * 毎サーバーtickごとに1歩ずつ進める。
 */
public class MiningManager {

    private static final List<MiningJob> ACTIVE_JOBS = new ArrayList<>();

    public static void submit(MiningJob job) {
        ACTIVE_JOBS.add(job);
    }

    /**
     * 指定したプレイヤー(UUID)が実行中のmineジョブがあればキャンセルする。
     * Carpet本家の "/player <名前> stop" (EntityPlayerActionPack.stopAll()) と連動させるために使う。
     */
    public static void cancelJobsFor(UUID playerId) {
        ACTIVE_JOBS.removeIf(job -> {
            if (job.getTargetUUID().equals(playerId)) {
                job.onExternalStop();
                return true;
            }
            return false;
        });
    }

    public static void registerEvents() {
        // 毎サーバーtick(20分の1秒ごと)に呼ばれる。
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ACTIVE_JOBS.isEmpty()) return;

            ACTIVE_JOBS.removeIf(job -> {
                try {
                    return !job.tick();
                } catch (RuntimeException e) {
                    // ジョブ1つの不具合で、サーバー全体(他のbotのジョブ・ゲームそのもの)を巻き込んで止めない。
                    // 原因はログに残し、このジョブだけ止めて外す。
                    TonCarpet.LOGGER.error("mine のジョブでエラーが起きたため、そのジョブを停止しました", e);
                    try {
                        job.onExternalStop();
                    } catch (RuntimeException ignored) {
                        // 後片付けでも失敗した場合は、これ以上何もしない
                    }
                    return true;
                }
            });
        });

        // サーバー停止の直前に、実行中のジョブをすべて止めて片付ける(ボットが開けたドアを閉じる等)。
        // 静的に保持しているので、これをしないとワールドを出入りしたときに古いジョブが残り続ける。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopAllJobs());
    }

    /**
     * 実行中のジョブをすべて止めて片付ける。サーバー停止時と、ルールを無効にしたときに呼ぶ。
     * 例外が出ても、停止処理・ルール変更を失敗させないよう、ここで握りつぶす。
     */
    public static void stopAllJobs() {
        for (MiningJob job : new ArrayList<>(ACTIVE_JOBS)) {
            try {
                job.onExternalStop();
            } catch (Exception ignored) {
                // 停止中・設定変更中なので無視する
            }
        }
        ACTIVE_JOBS.clear();
    }
}
