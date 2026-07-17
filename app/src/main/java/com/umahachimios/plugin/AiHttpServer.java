package com.umahachimios.plugin;

import android.util.Log;

import com.umahachimios.plugin.engine.DecisionEngine;
import com.umahachimios.plugin.engine.GameState;
import com.umahachimios.plugin.engine.TrainingEvaluator;
import com.umahachimios.plugin.engine.RaceEvaluator;
import com.umahachimios.plugin.engine.SkillEvaluator;
import com.umahachimios.plugin.stochastic.SeedTracker;
import com.umahachimios.plugin.stochastic.SLFilter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI决策HTTP服务 — 向浮窗App提供AI推荐
 *
 * 端口: 18766
 * 端点:
 *   GET  /recommend       — 获取当前最佳行动推荐
 *   GET  /evaluate/train  — 获取训练评分详情
 *   GET  /evaluate/race   — 获取比赛评估
 *   GET  /evaluate/skill  — 获取技能评估
 *   GET  /sl/check        — SL过滤检查
 *   GET  /sl/status       — SL过滤状态
 *   GET  /factor/score    — 因子评分
 *   GET  /health          — 健康检查
 *   POST /config          — 更新配置
 */
public class AiHttpServer {

    private static final String TAG = "AiHttpServer";
    public static final int PORT = 18766;

    /** 服务端Socket */
    private ServerSocket serverSocket;
    /** 客户端请求线程池 */
    private ExecutorService executor;
    /** 服务运行标志 */
    private volatile boolean running = false;

    // 核心组件
    /** AI决策引擎 */
    private DecisionEngine decisionEngine;
    /** hlpatch数据客户端 */
    private HachimiHttpClient hachimiClient;
    /** 种子追踪器 */
    private SeedTracker seedTracker;
    /** SL过滤器 */
    private SLFilter slFilter;

    // 配置
    /** 当前剧本名称 */
    private String currentScenario = "URA";
    /** SL过滤是否启用 */
    private boolean slFilterEnabled = false;
    /** SL过滤策略 */
    private String slFilterStrategy = "balanced";

    /**
     * 构造函数
     * @param engine 决策引擎
     * @param client hlpatch数据客户端
     */
    public AiHttpServer(DecisionEngine engine, HachimiHttpClient client) {
        this.decisionEngine = engine;
        this.hachimiClient = client;
        this.seedTracker = new SeedTracker();
    }

    /**
     * 启动HTTP服务
     */
    public void start() {
        if (running) {
            Log.w(TAG, "Server already running on port " + PORT);
            return;
        }
        running = true;
        executor = Executors.newCachedThreadPool();

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    serverSocket = new ServerSocket(PORT);
                    Log.i(TAG, "AI HTTP Server started on port " + PORT);

                    while (running) {
                        try {
                            Socket client = serverSocket.accept();
                            executor.execute(new Runnable() {
                                @Override
                                public void run() {
                                    handleRequest(client);
                                }
                            });
                        } catch (IOException e) {
                            if (running) {
                                Log.e(TAG, "Accept error: " + e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Server socket error: " + e.getMessage());
                }
            }
        }, "AiHttpServer-Main");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    /**
     * 停止HTTP服务
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Stop error: " + e.getMessage());
        }
        if (executor != null) {
            executor.shutdown();
        }
        Log.i(TAG, "AI HTTP Server stopped");
    }

    /**
     * 处理单个客户端请求
     * @param client 客户端Socket
     */
    private void handleRequest(Socket client) {
        InputStream inputStream = null;
        OutputStream outputStream = null;
        BufferedReader reader = null;
        try {
            inputStream = client.getInputStream();
            outputStream = client.getOutputStream();
            reader = new BufferedReader(new InputStreamReader(inputStream));

            // 解析请求行
            String requestLine = reader.readLine();
            if (requestLine == null) {
                sendResponse(outputStream, 400, "{\"error\":\"empty request\"}");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                sendResponse(outputStream, 400, "{\"error\":\"bad request line\"}");
                return;
            }

            String method = parts[0];
            String path = parts[1];

            // 读取headers
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    headers.put(line.substring(0, idx).trim().toLowerCase(),
                                line.substring(idx + 1).trim());
                }
            }

            // 路由处理
            String response = null;
            int statusCode = 200;

            if ("/recommend".equals(path) && "GET".equals(method)) {
                response = handleRecommend();
            } else if ("/evaluate/train".equals(path) && "GET".equals(method)) {
                response = handleEvaluateTrain();
            } else if ("/evaluate/race".equals(path) && "GET".equals(method)) {
                response = handleEvaluateRace();
            } else if ("/evaluate/skill".equals(path) && "GET".equals(method)) {
                response = handleEvaluateSkill();
            } else if ("/sl/check".equals(path) && "GET".equals(method)) {
                response = handleSlCheck();
            } else if ("/sl/status".equals(path) && "GET".equals(method)) {
                response = handleSlStatus();
            } else if ("/factor/score".equals(path) && "GET".equals(method)) {
                response = handleFactorScore();
            } else if ("/health".equals(path) && "GET".equals(method)) {
                response = handleHealth();
            } else if ("/config".equals(path) && "POST".equals(method)) {
                response = handleConfig(reader, headers);
            } else {
                statusCode = 404;
                response = "{\"error\":\"not found\",\"path\":\"" + escapeJson(path) + "\"}";
            }

            sendResponse(outputStream, statusCode, response);

        } catch (Exception e) {
            Log.e(TAG, "Handle request error: " + e.getMessage());
            try {
                if (outputStream != null) {
                    sendResponse(outputStream, 500, "{\"error\":\"internal error\"}");
                }
            } catch (Exception ignored) {}
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (IOException ignored) {}
            }
            if (inputStream != null) {
                try { inputStream.close(); } catch (IOException ignored) {}
            }
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 处理 /recommend 请求 — 返回当前最佳行动推荐
     */
    private String handleRecommend() {
        JSONObject summary = hachimiClient.getLastSummary();
        if (summary == null) {
            return "{\"error\":\"no data available\",\"hint\":\"game not started or hlpatch disconnected\"}";
        }

        try {
            GameState state = GameState.fromSummaryJson(summary);
            DecisionEngine.DecisionResult result = decisionEngine.decide(state);

            JSONObject resp = new JSONObject();
            resp.put("action", result.action);
            resp.put("label", result.actionLabel);
            resp.put("detail", result.bestDetail);
            resp.put("color", result.bestColor);
            resp.put("score", result.bestScore);
            resp.put("reason", result.reason);

            // 所有训练评分
            if (result.allScores != null) {
                JSONArray scores = new JSONArray();
                String[] labels = {"speed", "stamina", "power", "guts", "wisdom"};
                for (int i = 0; i < result.allScores.length && i < labels.length; i++) {
                    JSONObject s = new JSONObject();
                    s.put("type", labels[i]);
                    s.put("score", result.allScores[i]);
                    scores.put(s);
                }
                resp.put("all_scores", scores);
            }

            resp.put("scenario", currentScenario);
            resp.put("timestamp", System.currentTimeMillis());
            return resp.toString();

        } catch (JSONException e) {
            Log.e(TAG, "Recommend JSON error: " + e.getMessage());
            return "{\"error\":\"json processing error\"}";
        } catch (Exception e) {
            Log.e(TAG, "Recommend error: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /evaluate/train 请求 — 返回训练评分详情
     */
    private String handleEvaluateTrain() {
        JSONObject summary = hachimiClient.getLastSummary();
        if (summary == null) {
            return "{\"error\":\"no data available\"}";
        }
        try {
            TrainingEvaluator eval = new TrainingEvaluator();
            eval.setScenario(currentScenario);
            TrainingEvaluator.EvalResult result = eval.evaluate(summary);

            JSONObject resp = new JSONObject();
            resp.put("best_type", result.bestType);
            resp.put("best_label", result.bestLabel);
            resp.put("best_score", result.bestScore);
            resp.put("detail", result.bestDetail);

            JSONArray scores = new JSONArray();
            String[] labels = {"speed", "stamina", "power", "guts", "wisdom"};
            for (int i = 0; i < result.allScores.length && i < labels.length; i++) {
                JSONObject s = new JSONObject();
                s.put("type", labels[i]);
                s.put("label", labels[i]);
                s.put("score", result.allScores[i]);
                scores.put(s);
            }
            resp.put("scores", scores);
            resp.put("scenario", currentScenario);
            return resp.toString();
        } catch (JSONException e) {
            return "{\"error\":\"json error\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}";
        } catch (Exception e) {
            Log.e(TAG, "Evaluate train error: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /evaluate/race 请求 — 返回比赛评估
     */
    private String handleEvaluateRace() {
        JSONObject summary = hachimiClient.getLastSummary();
        if (summary == null) {
            return "{\"error\":\"no data available\"}";
        }
        try {
            GameState state = GameState.fromSummaryJson(summary);
            List<RaceEvaluator.RaceEvalResult> races = RaceEvaluator.getRecommendedRaces(state);

            JSONArray arr = new JSONArray();
            for (RaceEvaluator.RaceEvalResult r : races) {
                JSONObject o = new JSONObject();
                o.put("name", r.race.name);
                o.put("distance", r.race.distance);
                o.put("grade", r.race.grade);
                o.put("field", r.race.fieldType);
                o.put("win_prob", r.winProbability);
                o.put("expected_value", r.expectedValue);
                o.put("recommendation", r.recommendation);
                o.put("reason", r.reason);
                arr.put(o);
            }
            JSONObject resp = new JSONObject();
            resp.put("races", arr);
            resp.put("stage", state.getStage());
            resp.put("scenario", currentScenario);
            return resp.toString();
        } catch (JSONException e) {
            return "{\"error\":\"json error\"}";
        } catch (Exception e) {
            Log.e(TAG, "Evaluate race error: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /evaluate/skill 请求 — 返回技能评估
     */
    private String handleEvaluateSkill() {
        try {
            JSONObject resp = new JSONObject();
            JSONArray skills = new JSONArray();

            // 添加示例技能评分 (使用静态优先级数据)
            String[][] sampleSkills = {
                {"\u76f4\u7ebf\u52a0\u901f", "S", "0.95", "100", "0.001425"},
                {"\u5f2f\u9053\u56de\u590d", "S", "0.90", "100", "0.001080"},
                {"\u4f4d\u7f6e\u53d6\u308a", "A", "0.70", "120", "0.000583"},
                {"\u51fa\u95f8", "A", "0.80", "80", "0.000800"},
                {"\u672b\u811a", "S", "0.85", "120", "0.001063"},
                {"\u4e34\u673a\u5e94\u53d8", "B", "0.40", "150", "0.000320"},
                {"\u9a91\u9a6c\u4f53\u9a8c", "A", "0.60", "30", "0.001000"}
            };
            for (String[] sk : sampleSkills) {
                JSONObject s = new JSONObject();
                s.put("name", sk[0]);
                s.put("tier", sk[1]);
                s.put("trigger_prob", Double.parseDouble(sk[2]));
                s.put("pt_cost", Integer.parseInt(sk[3]));
                s.put("efficiency", Double.parseDouble(sk[4]));
                skills.put(s);
            }
            resp.put("skills", skills);
            resp.put("note", "skill evaluation: trigger_prob * impact / pt_cost");
            return resp.toString();
        } catch (JSONException e) {
            return "{\"error\":\"json error\"}";
        } catch (Exception e) {
            Log.e(TAG, "Evaluate skill error: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /sl/check 请求 — SL过滤检查
     */
    private String handleSlCheck() {
        if (slFilter == null) {
            return "{\"error\":\"SL filter not initialized\",\"hint\":\"sl filter is disabled or not configured\"}";
        }
        try {
            return slFilter.getStatusJson();
        } catch (Exception e) {
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /sl/status 请求 — SL过滤状态
     */
    private String handleSlStatus() {
        if (slFilter == null) {
            return "{\"enabled\":false,\"initialized\":false}";
        }
        try {
            return slFilter.getStatusJson();
        } catch (Exception e) {
            return "{\"enabled\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 处理 /factor/score 请求 — 因子评分
     */
    private String handleFactorScore() {
        try {
            JSONObject resp = new JSONObject();
            resp.put("note", "Factor scoring requires factor data from game");
            JSONArray strategies = new JSONArray();
            strategies.put("speed_build_strict");
            strategies.put("balanced");
            strategies.put("red_focused");
            strategies.put("lenient");
            resp.put("strategies", strategies);
            resp.put("current_strategy", slFilterStrategy);
            return resp.toString();
        } catch (JSONException e) {
            return "{\"error\":\"json error\"}";
        }
    }

    /**
     * 处理 /health 请求 — 健康检查
     */
    private String handleHealth() {
        try {
            JSONObject resp = new JSONObject();
            resp.put("status", "ok");
            resp.put("version", "1.0.0");
            resp.put("port", PORT);
            resp.put("game_initialized", hachimiClient.isGameInitialized());
            resp.put("scenario", currentScenario);
            resp.put("sl_filter_enabled", slFilterEnabled);
            resp.put("sl_filter_strategy", slFilterStrategy);
            resp.put("timestamp", System.currentTimeMillis());
            return resp.toString();
        } catch (JSONException e) {
            return "{\"status\":\"error\",\"message\":\"json error\"}";
        }
    }

    /**
     * 处理 /config POST 请求 — 更新配置
     */
    private String handleConfig(BufferedReader reader, Map<String, String> headers) {
        try {
            // 读取body长度
            int contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Integer.parseInt(headers.get("content-length"));
                } catch (NumberFormatException e) {
                    return "{\"error\":\"invalid content-length\"}";
                }
            }

            if (contentLength <= 0) {
                return "{\"error\":\"empty body\"}";
            }

            if (contentLength > 65536) {
                return "{\"error\":\"body too large\",\"max\":65536}";
            }

            char[] bodyChars = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = reader.read(bodyChars, read, contentLength - read);
                if (r < 0) break;
                read += r;
            }
            String body = new String(bodyChars, 0, read);

            JSONObject config = new JSONObject(body);

            // 更新剧本
            if (config.has("scenario")) {
                String scenario = config.getString("scenario");
                if (scenario != null && scenario.length() > 0) {
                    currentScenario = scenario;
                    Log.i(TAG, "Config: scenario set to " + scenario);
                }
            }
            // 更新SL过滤开关
            if (config.has("sl_filter_enabled")) {
                slFilterEnabled = config.getBoolean("sl_filter_enabled");
                Log.i(TAG, "Config: sl_filter_enabled set to " + slFilterEnabled);
            }
            // 更新SL过滤策略
            if (config.has("sl_filter_strategy")) {
                String strategy = config.getString("sl_filter_strategy");
                if (strategy != null && strategy.length() > 0) {
                    slFilterStrategy = strategy;
                    Log.i(TAG, "Config: sl_filter_strategy set to " + strategy);
                }
            }

            JSONObject resp = new JSONObject();
            resp.put("success", true);
            resp.put("scenario", currentScenario);
            resp.put("sl_filter_enabled", slFilterEnabled);
            resp.put("sl_filter_strategy", slFilterStrategy);
            return resp.toString();

        } catch (JSONException e) {
            Log.e(TAG, "Config JSON error: " + e.getMessage());
            return "{\"error\":\"invalid json\",\"detail\":\"" + escapeJson(e.getMessage()) + "\"}";
        } catch (Exception e) {
            Log.e(TAG, "Config error: " + e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    /**
     * 发送HTTP响应
     * @param out 输出流
     * @param statusCode HTTP状态码
     * @param body 响应体JSON字符串
     */
    private void sendResponse(OutputStream out, int statusCode, String body) {
        try {
            String status;
            switch (statusCode) {
                case 200: status = "200 OK"; break;
                case 400: status = "400 Bad Request"; break;
                case 404: status = "404 Not Found"; break;
                case 405: status = "405 Method Not Allowed"; break;
                default: status = "500 Internal Server Error"; break;
            }

            byte[] bodyBytes = body != null ? body.getBytes("UTF-8") : new byte[0];

            StringBuilder header = new StringBuilder();
            header.append("HTTP/1.1 ").append(status).append("\r\n");
            header.append("Content-Type: application/json; charset=utf-8\r\n");
            header.append("Access-Control-Allow-Origin: *\r\n");
            header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            header.append("Access-Control-Allow-Headers: Content-Type\r\n");
            header.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            header.append("Connection: close\r\n");
            header.append("\r\n");

            out.write(header.toString().getBytes("UTF-8"));
            if (bodyBytes.length > 0) {
                out.write(bodyBytes);
            }
            out.flush();
        } catch (IOException e) {
            Log.e(TAG, "Send response error: " + e.getMessage());
        }
    }

    /**
     * 转义JSON字符串中的特殊字符
     * @param s 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c); break;
            }
        }
        return sb.toString();
    }
}
