package com.umahachimios.plugin;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * hlpatch HTTP客户端 — 从SO插件获取实时游戏数据
 *
 * 数据来源: http://127.0.0.1:18765
 * 端点:
 *   GET /summary — 完整育成状态
 *   GET /status  — 插件运行状态
 *   GET /data    — 原始训练数据
 */
public class HachimiHttpClient {

    private static final String TAG = "HachimiClient";
    private static final String BASE_URL = "http://127.0.0.1:18765";
    private static final int CONNECT_TIMEOUT = 3000;
    private static final int READ_TIMEOUT = 3000;

    /** 单次任务执行器，用于异步操作 */
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 定时调度器，用于轮询 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 当前轮询任务 */
    private ScheduledFuture<?> pollTask;
    /** 是否正在运行 */
    private volatile boolean running = false;
    /** 数据回调接口 */
    private DataCallback callback;
    /** 上次成功获取的summary数据 */
    private JSONObject lastSummary = null;
    /** 游戏是否已初始化（检测到有效数据） */
    private volatile boolean gameInitialized = false;

    /**
     * 数据回调接口
     */
    public interface DataCallback {
        /** 收到summary数据 */
        void onSummaryData(JSONObject summary);
        /** 连接断开 */
        void onConnectionLost(String reason);
        /** 连接恢复 */
        void onConnectionRestored();
    }

    /**
     * 设置数据回调
     * @param cb 回调实例
     */
    public void setCallback(DataCallback cb) {
        this.callback = cb;
    }

    /**
     * 开始轮询hlpatch数据
     * @param intervalMs 轮询间隔(毫秒)
     */
    public void startPolling(int intervalMs) {
        if (running) {
            Log.w(TAG, "Polling already running, ignore start request");
            return;
        }
        running = true;

        pollTask = scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject summary = fetchSummary();
                    if (summary != null) {
                        boolean wasInit = gameInitialized;
                        // 如果包含error字段则认为游戏未初始化
                        gameInitialized = !summary.has("error");

                        if (gameInitialized && !wasInit && callback != null) {
                            callback.onConnectionRestored();
                        }

                        if (gameInitialized) {
                            lastSummary = summary;
                            if (callback != null) {
                                callback.onSummaryData(summary);
                            }
                        }
                    } else {
                        if (gameInitialized) {
                            gameInitialized = false;
                            if (callback != null) {
                                callback.onConnectionLost("fetch returned null");
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Poll error: " + e.getMessage());
                    gameInitialized = false;
                    if (callback != null) {
                        callback.onConnectionLost("exception: " + e.getMessage());
                    }
                }
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        Log.i(TAG, "Started polling hlpatch at " + intervalMs + "ms interval");
    }

    /**
     * 停止轮询
     */
    public void stopPolling() {
        running = false;
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
        Log.i(TAG, "Polling stopped");
    }

    /**
     * 获取 /summary 端点数据（完整育成状态）
     * @return JSON对象，失败返回null
     */
    public JSONObject fetchSummary() {
        return httpGet("/summary");
    }

    /**
     * 获取 /status 端点数据（插件运行状态）
     * @return JSON对象，失败返回null
     */
    public JSONObject fetchStatus() {
        return httpGet("/status");
    }

    /**
     * 获取 /data 端点数据（原始训练数据）
     * @return JSON对象，失败返回null
     */
    public JSONObject fetchData() {
        return httpGet("/data");
    }

    /**
     * 执行HTTP GET请求
     * @param path 请求路径（如 /summary）
     * @return JSON响应对象，失败返回null
     */
    private JSONObject httpGet(String path) {
        HttpURLConnection conn = null;
        InputStream inputStream = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(BASE_URL + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoInput(true);

            int code = conn.getResponseCode();
            if (code == 200) {
                inputStream = conn.getInputStream();
                reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                String responseStr = sb.toString();
                if (responseStr.length() > 0) {
                    return new JSONObject(responseStr);
                }
            } else {
                Log.d(TAG, "HTTP " + code + " for " + path);
            }
        } catch (java.net.SocketTimeoutException e) {
            // 超时静默失败，下次轮询重试
        } catch (java.net.ConnectException e) {
            // 连接失败，hlpatch可能未启动
        } catch (Exception e) {
            Log.d(TAG, "HTTP error for " + path + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
        } finally {
            // 按相反顺序关闭资源
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) {}
            }
            if (inputStream != null) {
                try { inputStream.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * 获取上次成功获取的summary数据
     * @return 上次成功的summary JSON，无数据返回null
     */
    public JSONObject getLastSummary() {
        return lastSummary;
    }

    /**
     * 游戏是否已检测到有效数据
     * @return true表示已获取有效游戏数据
     */
    public boolean isGameInitialized() {
        return gameInitialized;
    }

    /**
     * 完全关闭客户端，释放所有资源
     */
    public void shutdown() {
        stopPolling();
        scheduler.shutdown();
        executor.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
        }
        Log.i(TAG, "HachimiHttpClient shutdown complete");
    }
}
