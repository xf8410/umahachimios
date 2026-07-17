package com.umahachimios.plugin;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.umahachimios.plugin.engine.DecisionEngine;
import com.umahachimios.plugin.engine.GameState;

import org.json.JSONObject;

/**
 * 赛马娘哈基米SO插件主服务
 *
 * 功能：
 * 1. 连接hlpatch(18765)获取游戏数据
 * 2. 运行AI决策引擎
 * 3. 启动HTTP服务(18766)提供AI推荐
 *
 * 安装方式：
 * - 作为Android Service运行
 * - 通过哈基米框架加载
 */
public class UmaHachimiPlugin extends Service {

    private static final String TAG = "UmaHachimiPlugin";

    /** hlpatch数据客户端 */
    private HachimiHttpClient hachimiClient;
    /** AI决策引擎 */
    private DecisionEngine decisionEngine;
    /** AI推荐HTTP服务 */
    private AiHttpServer aiServer;

    /** 数据轮询间隔(毫秒) */
    private static final int POLL_INTERVAL_MS = 1000;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "UmaHachimiPlugin creating...");

        // 初始化决策引擎
        decisionEngine = new DecisionEngine();

        // 初始化hlpatch客户端
        hachimiClient = new HachimiHttpClient();
        hachimiClient.setCallback(new HachimiHttpClient.DataCallback() {
            @Override
            public void onSummaryData(JSONObject summary) {
                // 数据到达，决策引擎会自动处理
                int turn = summary.optInt("turn", 0);
                int actionCount = summary.optInt("action_count", 0);
                Log.d(TAG, "Summary data received, turn=" + turn + ", actions=" + actionCount);
            }

            @Override
            public void onConnectionLost(String reason) {
                Log.w(TAG, "hlpatch connection lost: " + reason);
            }

            @Override
            public void onConnectionRestored() {
                Log.i(TAG, "hlpatch connection restored");
            }
        });

        // 启动数据轮询（从hlpatch获取游戏数据）
        hachimiClient.startPolling(POLL_INTERVAL_MS);
        Log.i(TAG, "Started polling hlpatch at " + POLL_INTERVAL_MS + "ms");

        // 启动AI HTTP服务（向浮窗App提供推荐）
        aiServer = new AiHttpServer(decisionEngine, hachimiClient);
        aiServer.start();

        Log.i(TAG, "UmaHachimiPlugin started, AI server on port " + AiHttpServer.PORT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand flags=" + flags + " startId=" + startId);

        // 处理外部传入的剧本设置
        if (intent != null) {
            if (intent.hasExtra("scenario")) {
                String scenario = intent.getStringExtra("scenario");
                if (scenario != null) {
                    Log.i(TAG, "Scenario set to: " + scenario);
                }
            }
            if (intent.hasExtra("poll_interval")) {
                int interval = intent.getIntExtra("poll_interval", POLL_INTERVAL_MS);
                Log.i(TAG, "Poll interval set to: " + interval + "ms");
            }
        }

        // START_STICKY: 如果被系统杀死，会自动重启
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "UmaHachimiPlugin destroying...");

        // 关闭数据客户端
        if (hachimiClient != null) {
            hachimiClient.shutdown();
            hachimiClient = null;
        }

        // 关闭HTTP服务
        if (aiServer != null) {
            aiServer.stop();
            aiServer = null;
        }

        Log.i(TAG, "UmaHachimiPlugin destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        // 本插件不支持绑定模式
        return null;
    }
}
