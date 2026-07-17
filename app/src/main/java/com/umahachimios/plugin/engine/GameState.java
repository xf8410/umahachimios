package com.umahachimios.plugin.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

/**
 * 游戏状态数据模型
 * 从hlpatch的/summary接口JSON数据解析，存储当前育成回合的全部状态信息
 */
public class GameState {
    private static final String TAG = "GameState";

    // ==================== 核心五维属性 ====================
    public int speed;      // 速度
    public int stamina;    // 耐力
    public int power;      // 力量
    public int guts;       // 根性
    public int wisdom;     // 智力

    // ==================== 资源 ====================
    public int vital;       // 当前体力
    public int maxVital;    // 体力上限
    public int motivation;  // 干劲 1-5 (1:绝不调, 2:不调, 3:普通, 4:好调, 5:绝好调)

    // ==================== 进度 ====================
    public int turn;        // 当前回合数 (1-79)
    public int month;       // 月份 (1-12)
    public int half;        // 上下半月 (0:上半月, 1:下半月)
    public String stage;    // 阶段 "junior"/"classic"/"senior"
    public String scenario; // 剧本ID，如 " scenario/legend" 等

    // ==================== 训练设施 ====================
    public int[] facilityLevels = new int[5];  // [速, 耐, 力, 根, 智] 设施等级

    // ==================== JSON透传数据 ====================
    public String trainingsJson;      // 训练数据 (JSONArray透传)
    public String evaluationJson;     // 支援卡羁绊数据
    public String buffsJson;          // Buff/状态效果
    public int[] charaEffectIds;      // 疾病等负面状态ID数组
    public String skillsJson;         // 已拥有技能
    public String trainingLevelsJson; // 训练等级
    public String scenarioDataJson;   // 剧本专属数据 (team_data/ramen等)

    // ==================== 技能 ====================
    public int skillPt;   // 可用技能点

    // ==================== 粉丝 ====================
    public int fans;      // 粉丝数

    // ==================== 最近一次行动 ====================
    public String lastAction;           // 上次行动类型
    public int lastActionCommandId;     // 上次行动命令ID

    /**
     * 干劲倍率表 [0.8, 0.9, 1.0, 1.1, 1.2]
     * 1=绝不调, 2=不调, 3=普通, 4=好调, 5=绝好调
     */
    private static final double[] MOOD_MULTIPLIERS = {0.0, 0.8, 0.9, 1.0, 1.1, 1.2};

    /**
     * 阶段回合数分界点
     */
    private static final int JUNIOR_END = 24;   // 青春杯 junior 结束
    private static final int CLASSIC_END = 48;  // classic 结束
    private static final int SENIOR_END = 72;   // senior 结束 (URA标准)

    public GameState() {
    }

    /**
     * 从hlpatch的/summary JSON解析游戏状态
     *
     * @param json /summary接口返回的JSONObject
     * @return 解析后的GameState对象
     */
    public static GameState fromSummaryJson(JSONObject json) {
        GameState state = new GameState();
        try {
            // 解析核心属性 chara_info
            if (json.has("chara_info")) {
                JSONObject chara = json.getJSONObject("chara_info");
                state.speed = chara.optInt("speed", 0);
                state.stamina = chara.optInt("stamina", 0);
                state.power = chara.optInt("power", 0);
                state.guts = chara.optInt("guts", 0);
                state.wisdom = chara.optInt("wiz", 0);

                state.vital = chara.optInt("vital", 0);
                state.maxVital = chara.optInt("max_vital", 100);
                state.motivation = chara.optInt("motivation", 3);

                state.turn = chara.optInt("turn", 0);
                state.scenario = chara.optString("scenario_id", "scenario/legend");
                state.skillPt = chara.optInt("skill_point", 0);
                state.fans = chara.optInt("fans", 0);

                // 解析负面状态
                if (chara.has("chara_effect_id_array")) {
                    JSONArray effects = chara.getJSONArray("chara_effect_id_array");
                    state.charaEffectIds = new int[effects.length()];
                    for (int i = 0; i < effects.length(); i++) {
                        state.charaEffectIds[i] = effects.optInt(i, 0);
                    }
                }

                // 技能
                if (chara.has("skill_array")) {
                    state.skillsJson = chara.getJSONArray("skill_array").toString();
                }
            }

            // 解析训练设施等级
            if (json.has("home_info")) {
                JSONObject home = json.getJSONObject("home_info");
                for (int i = 0; i < 5; i++) {
                    state.facilityLevels[i] = home.optInt("facility_level_" + i, 1);
                }
            }

            // 训练数据
            if (json.has("training_info")) {
                JSONArray trainArr = json.getJSONArray("training_info");
                state.trainingsJson = trainArr.toString();
            }

            // 支援卡羁绊
            if (json.has("evaluation_info_array")) {
                JSONArray evalArr = json.getJSONArray("evaluation_info_array");
                state.evaluationJson = evalArr.toString();
            }

            // Buff
            if (json.has("buff_array")) {
                JSONArray buffArr = json.getJSONArray("buff_array");
                state.buffsJson = buffArr.toString();
            }

            // 训练等级
            if (json.has("train_level_info")) {
                JSONArray tlArr = json.getJSONArray("train_level_info");
                state.trainingLevelsJson = tlArr.toString();
            }

            // 剧本专属数据
            if (json.has("scenario_data")) {
                JSONObject sd = json.getJSONObject("scenario_data");
                state.scenarioDataJson = sd.toString();
            }

            // 计算月份、上下半月、阶段
            state.calcTurnInfo();

            Log.d(TAG, "解析完成: 回合=" + state.turn
                    + ", 属性=[" + state.speed + "," + state.stamina + "," + state.power
                    + "," + state.guts + "," + state.wisdom + "]"
                    + ", 体力=" + state.vital + "/" + state.maxVital
                    + ", 干劲=" + state.motivation
                    + ", 阶段=" + state.stage);

        } catch (Exception e) {
            Log.e(TAG, "解析GameState失败: " + e.getMessage(), e);
        }
        return state;
    }

    /**
     * 计算回合相关信息 (月份、阶段等)
     */
    private void calcTurnInfo() {
        // 月份计算: turn 1-2 = 7月, 3-4 = 8月, ...
        int totalHalves = turn - 1;
        this.month = (totalHalves / 2) % 12 + 1;
        this.half = totalHalves % 2;

        // 阶段判断
        this.stage = getStage();
    }

    /**
     * 获取干劲倍率
     *
     * @return 干劲倍率 [0.8, 0.9, 1.0, 1.1, 1.2]
     */
    public double getMoodMultiplier() {
        if (motivation >= 1 && motivation <= 5) {
            return MOOD_MULTIPLIERS[motivation];
        }
        return 1.0; // 默认普通
    }

    /**
     * 获取当前阶段
     *
     * @return "junior" / "classic" / "senior"
     */
    public String getStage() {
        if (turn <= JUNIOR_END) {
            return "junior";
        } else if (turn <= CLASSIC_END) {
            return "classic";
        } else {
            return "senior";
        }
    }

    /**
     * 获取属性数组
     *
     * @return [speed, stamina, power, guts, wisdom]
     */
    public int[] getStatsArray() {
        return new int[]{speed, stamina, power, guts, wisdom};
    }

    /**
     * 获取当前体力百分比
     *
     * @return 0-100
     */
    public double getVitalPercent() {
        if (maxVital <= 0) return 0;
        return (double) vital / maxVital * 100.0;
    }

    /**
     * 检查是否有负面状态 (疾病等)
     *
     * @return true if 有负面状态
     */
    public boolean hasNegativeState() {
        if (charaEffectIds == null || charaEffectIds.length == 0) {
            return false;
        }
        // 疾病类effect ID范围: 通常100-200区间
        for (int id : charaEffectIds) {
            if (id >= 100 && id <= 200) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "GameState{" +
                "turn=" + turn +
                ", stage='" + stage + '\'' +
                ", scenario='" + scenario + '\'' +
                ", stats=[" + speed + "," + stamina + "," + power + "," + guts + "," + wisdom + "]" +
                ", vital=" + vital + "/" + maxVital +
                ", motivation=" + motivation +
                ", skillPt=" + skillPt +
                ", fans=" + fans +
                '}';
    }
}
