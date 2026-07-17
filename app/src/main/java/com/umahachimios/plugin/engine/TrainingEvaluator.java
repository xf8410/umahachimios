package com.umahachimios.plugin.engine;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 训练评分引擎
 * 参考 uma-juece 的 TrainingEvaluator.java 实现
 * 对5种训练(速/耐/力/根/智)进行综合评分，选出最优训练
 *
 * 评分维度:
 * - 属性增益 (软上限约束)
 * - 体力变化价值
 * - 失败率惩罚
 * - 彩圈(闪光)加成
 * - 羁绊价值
 * - 没带支援卡惩罚
 * - 剧本偏好加成
 * - 训练等级加成
 * - 剧本专属加成
 */
public class TrainingEvaluator {
    private static final String TAG = "TrainingEvaluator";

    // ==================== 属性权重 ====================
    private static final double[] STATUS_WEIGHTS = {7.0, 8.0, 8.0, 8.0, 6.0}; // [速, 耐, 力, 根, 智]

    // ==================== 没带卡惩罚权重 ====================
    private static final double NO_HEADS_WEIGHT = 2.0;

    // ==================== 剧本偏好矩阵 [速, 耐, 力, 根, 智] ====================
    private static final int[] URA_DEFAULT = {20, 10, 30, 30, 20};
    private static final int[] CLIMAX = {20, 10, 30, 30, 25};
    private static final int[] AOHARU = {20, 15, 25, 25, 20};
    private static final int[] GRAND_DRIVE = {25, 10, 25, 25, 20};
    private static final int[] GRAND_MASTERS = {20, 15, 25, 25, 20};
    private static final int[] LARC = {20, 15, 30, 25, 20};
    private static final int[] UAF = {25, 15, 25, 25, 20};
    private static final int[] HARVEST = {20, 10, 30, 30, 20};
    private static final int[] MECHA = {25, 15, 25, 20, 20};
    private static final int[] LEGENDS = {20, 15, 25, 25, 20};
    private static final int[] DESERT_ISLAND = {25, 15, 25, 20, 20};
    private static final int[] HOT_SPRING = {25, 15, 25, 20, 20};
    private static final int[] DREAMS = {25, 15, 25, 25, 20};

    // ==================== 软上限参数 ====================
    private static final int STATUS_CAP = 1200;          // 属性软上限
    private static final double RESERVE_STATUS_FACTOR = 40.0; // 属性越接近上限，加成越低

    // ==================== 各剧本finalBonus ====================
    private static final Map<String, Integer> FINAL_BONUS_MAP = new HashMap<>();

    static {
        FINAL_BONUS_MAP.put("scenario/legend", 115);       // URA
        FINAL_BONUS_MAP.put("scenario/aoharu", 90);        // Aoharu
        FINAL_BONUS_MAP.put("scenario/climax", 80);        // Climax
        FINAL_BONUS_MAP.put("scenario/granddrive", 100);   // GrandDrive
        FINAL_BONUS_MAP.put("scenario/grandmasters", 100); // GrandMasters
        FINAL_BONUS_MAP.put("scenario/larc", 100);         // LArc
        FINAL_BONUS_MAP.put("scenario/uaf", 100);          // UAF
        FINAL_BONUS_MAP.put("scenario/harvest", 90);       // Harvest
        FINAL_BONUS_MAP.put("scenario/mecha", 90);         // Mecha
        FINAL_BONUS_MAP.put("scenario/legends", 100);      // Legends
        FINAL_BONUS_MAP.put("scenario/desert", 100);       // DesertIsland
        FINAL_BONUS_MAP.put("scenario/hotspring", 100);    // HotSpring
        FINAL_BONUS_MAP.put("scenario/dreams", 120);       // Dreams
    }

    // ==================== 评分常量 ====================
    private static final double JIBAN_VALUE = 12.0;           // 羁绊单次增长价值
    private static final double VITAL_FACTOR_START = 3.5;     // 初期体力系数
    private static final double VITAL_FACTOR_END = 2.0;       // 末期体力系数
    private static final double SMALL_FAIL_VALUE = -1500.0;   // 小失败惩罚
    private static final double BIG_FAIL_VALUE = -1800.0;     // 大失败惩罚
    private static final double SHINING_BONUS = 35.0;         // 彩圈加成基数
    private static final double TRAIN_LEVEL_BONUS = 8.0;      // 训练等级加成基数
    private static final double STATE_PENALTY = -500.0;       // 负面状态惩罚
    private static final double NO_HEADS_PENALTY = -100.0;    // 没带卡惩罚基数

    // ==================== CMD到索引映射 ====================
    // 101→0(Speed), 105→1(Stamina), 103→3(Guts), 102→2(Power), 106→4(Wisdom)
    private static final Map<Integer, Integer> CMD_TO_IDX = new HashMap<>();
    // 拉面剧本CMD映射: 601→0, 602→1, 603→2, 604→3, 605→4
    private static final Map<Integer, Integer> RAMEN_CMD_TO_IDX = new HashMap<>();

    static {
        CMD_TO_IDX.put(101, 0); // Speed
        CMD_TO_IDX.put(105, 1); // Stamina
        CMD_TO_IDX.put(102, 2); // Power
        CMD_TO_IDX.put(103, 3); // Guts
        CMD_TO_IDX.put(106, 4); // Wisdom

        RAMEN_CMD_TO_IDX.put(601, 0);
        RAMEN_CMD_TO_IDX.put(602, 1);
        RAMEN_CMD_TO_IDX.put(603, 2);
        RAMEN_CMD_TO_IDX.put(604, 3);
        RAMEN_CMD_TO_IDX.put(605, 4);
    }

    // ==================== 训练类型标签 ====================
    private static final String[] TRAIN_LABELS = {"速", "耐", "力", "根", "智"};
    private static final String[] TRAIN_TYPES = {"speed", "stamina", "power", "guts", "wisdom"};
    private static final int[] TRAIN_COLORS = {
            0xFFE74C3C, // 速 - 红
            0xFF3498DB, // 耐 - 蓝
            0xFFF39C12, // 力 - 橙
            0xFFE67E22, // 根 - 深橙
            0xFF9B59B6  // 智 - 紫
    };

    private String scenario = "scenario/legend"; // 默认URA

    /**
     * 设置当前剧本
     */
    public void setScenario(String s) {
        if (s != null && !s.isEmpty()) {
            this.scenario = s;
        }
    }

    /**
     * 主评分入口 - 对summary中的5种训练进行评分
     *
     * @param summary hlpatch /summary JSON
     * @return EvalResult 包含最优训练及各训练评分
     */
    public EvalResult evaluate(JSONObject summary) {
        EvalResult result = new EvalResult();
        try {
            // 解析chara_info
            JSONObject chara = summary.optJSONObject("chara_info");
            if (chara == null) {
                Log.w(TAG, "chara_info为空");
                result.bestType = "rest";
                result.bestLabel = "休息";
                return result;
            }

            // 当前属性
            int[] currentStats = new int[5];
            currentStats[0] = chara.optInt("speed", 0);
            currentStats[1] = chara.optInt("stamina", 0);
            currentStats[2] = chara.optInt("power", 0);
            currentStats[3] = chara.optInt("guts", 0);
            currentStats[4] = chara.optInt("wiz", 0);

            int vital = chara.optInt("vital", 0);
            int maxVital = chara.optInt("max_vital", 100);
            int turn = chara.optInt("turn", 1);
            int motivation = chara.optInt("motivation", 3);

            // 剧本
            String sc = chara.optString("scenario_id", "scenario/legend");
            setScenario(sc);

            // 总回合数 (不同剧本可能不同)
            int maxTurn = 72; // 默认
            if (scenario.contains("climax")) maxTurn = 78;
            else if (scenario.contains("aoharu")) maxTurn = 67;
            else if (scenario.contains("granddrive")) maxTurn = 78;
            else if (scenario.contains("larc")) maxTurn = 78;
            else if (scenario.contains("uaf")) maxTurn = 78;
            else if (scenario.contains("harvest")) maxTurn = 78;
            else if (scenario.contains("dreams")) maxTurn = 78;

            // 训练数据
            JSONArray trainings = summary.optJSONArray("training_info");
            if (trainings == null || trainings.length() == 0) {
                Log.w(TAG, "训练数据为空");
                result.bestType = "rest";
                result.bestLabel = "休息";
                return result;
            }

            // 羁绊数据
            JSONArray evaluationArr = summary.optJSONArray("evaluation_info_array");
            double avgEvaluation = calcAvgEvaluation(evaluationArr);

            // 训练等级
            JSONArray trainLevelArr = summary.optJSONArray("train_level_info");
            Map<Integer, Integer> trainLevels = parseTrainingLevels(trainLevelArr);

            // 剧本偏好
            double[] typeBonus = getTypeBonus(scenario);

            // 负面状态
            int state = 0;
            if (chara.has("chara_effect_id_array")) {
                JSONArray effects = chara.getJSONArray("chara_effect_id_array");
                if (effects.length() > 0) state = 1;
            }

            // 对5种训练分别评分
            double bestScore = Double.NEGATIVE_INFINITY;
            int bestIdx = -1;
            double[] scores = new double[5];

            for (int i = 0; i < 5; i++) {
                double score = evaluateTraining(
                        summary, chara, trainings, i, vital, maxVital,
                        turn, maxTurn, state, trainLevels,
                        avgEvaluation, typeBonus, currentStats
                );
                scores[i] = score;
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = i;
                }
            }

            result.allScores = scores;
            result.bestScore = bestScore;

            if (bestIdx >= 0) {
                result.bestType = TRAIN_TYPES[bestIdx];
                result.bestLabel = TRAIN_LABELS[bestIdx];
                result.bestColor = TRAIN_COLORS[bestIdx];
                result.bestDetail = buildDetail(summary, trainings, bestIdx, trainLevels);
            } else {
                result.bestType = "rest";
                result.bestLabel = "休息";
            }

        } catch (Exception e) {
            Log.e(TAG, "训练评估失败: " + e.getMessage(), e);
            result.bestType = "rest";
            result.bestLabel = "休息";
        }
        return result;
    }

    /**
     * 对单个训练进行评分
     */
    private double evaluateTraining(JSONObject summary, JSONObject chara, JSONArray trainings,
                                    int trainIdx, int vital, int maxVital, int turn, int maxTurn,
                                    int state, Map<Integer, Integer> trainLevels,
                                    double avgEvaluation, double[] typeBonus, int[] currentStats) {
        try {
            JSONObject trData = getTrainingData(trainings, trainIdx);
            if (trData == null) {
                return Double.NEGATIVE_INFINITY; // 该训练不可用
            }

            double score = 0.0;

            // 1. 属性增益评分 (软上限约束)
            int[] gainStats = new int[5];
            if (trData.has("training_effect")) {
                JSONObject effect = trData.getJSONObject("training_effect");
                // 解析属性增益
                gainStats[0] = effect.optInt("speed", 0);
                gainStats[1] = effect.optInt("stamina", 0);
                gainStats[2] = effect.optInt("power", 0);
                gainStats[3] = effect.optInt("guts", 0);
                gainStats[4] = effect.optInt("wiz", 0);
            }
            int ptGain = trData.optInt("skill_point", 0);

            double statusScore = calcStatusGain(currentStats, gainStats, ptGain, turn, maxTurn, trainIdx);
            score += statusScore;

            // 2. 体力变化价值
            int vitalCost = trData.optInt("vital_cost", 0);
            int vitalAfter = vital - vitalCost;
            double vitalFactor = calcVitalFactor(turn, maxTurn);
            double vitalDiff = vitalAfter - vital;
            score += vitalFactor * vitalDiff * 2.0;

            // 体力不足时额外惩罚
            if (vitalAfter < 0) {
                score += (vitalAfter * vitalFactor * 5.0); // 严重惩罚
            }

            // 3. 失败率惩罚
            int failRate = trData.optInt("failure_rate", 0);
            if (failRate > 0) {
                if (failRate <= 5) {
                    score += SMALL_FAIL_VALUE * (failRate / 100.0);
                } else {
                    score += BIG_FAIL_VALUE * (failRate / 100.0);
                }
            }

            // 4. 彩圈(闪光)加成
            boolean isShining = trData.optBoolean("is_shining", false);
            if (isShining) {
                score += SHINING_BONUS;
            }

            // 检查每个支援卡是否闪光
            if (trData.has("support_card_list")) {
                JSONArray cards = trData.getJSONArray("support_card_list");
                int shiningCount = 0;
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.optJSONObject(i);
                    if (card != null && card.optBoolean("is_shining", false)) {
                        shiningCount++;
                    }
                }
                score += SHINING_BONUS * 0.5 * shiningCount;
            }

            // 5. 羁绊价值
            if (trData.has("support_card_list")) {
                JSONArray cards = trData.getJSONArray("support_card_list");
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.optJSONObject(i);
                    if (card != null) {
                        int bond = card.optInt("bond", 0);
                        // 羁绊接近80时价值更高
                        if (bond >= 60 && bond < 80) {
                            score += JIBAN_VALUE * 1.5;
                        } else if (bond >= 40 && bond < 60) {
                            score += JIBAN_VALUE;
                        } else if (bond < 40) {
                            score += JIBAN_VALUE * 0.5;
                        }
                    }
                }
            }

            // 6. 没带卡惩罚
            if (trData.has("support_card_list")) {
                JSONArray cards = trData.getJSONArray("support_card_list");
                // 统计空位
                int emptySlots = 0;
                for (int i = 0; i < cards.length(); i++) {
                    JSONObject card = cards.optJSONObject(i);
                    if (card == null || card.optInt("support_card_id", 0) == 0) {
                        emptySlots++;
                    }
                }
                score += NO_HEADS_PENALTY * NO_HEADS_WEIGHT * emptySlots;
            }

            // 7. 剧本偏好加成
            score += typeBonus[trainIdx];

            // 8. 训练等级加成
            int trainLv = getTrainLevel(trainLevels, trainIdx);
            score += trainLv * TRAIN_LEVEL_BONUS;

            // 9. 负面状态惩罚
            if (state > 0) {
                score += STATE_PENALTY;
            }

            // 10. 剧本专属加成
            score += scenarioBonus(trainIdx, summary, trData);

            // 11. Dreams剧本专属
            if (scenario.contains("dreams")) {
                score += dreamsBonus(trainIdx, summary, trData);
            }

            return score;

        } catch (Exception e) {
            Log.e(TAG, "评估训练[" + trainIdx + "]失败: " + e.getMessage());
            return Double.NEGATIVE_INFINITY;
        }
    }

    /**
     * 属性软上限计算
     * 属性越接近软上限，每次增长的实际价值越低
     */
    private double calcStatusGain(int[] currentStats, int[] gainStats, int ptGain,
                                  int turn, int maxTurn, int trainIdx) {
        double total = 0.0;
        int finalBonus = FINAL_BONUS_MAP.getOrDefault(scenario, 100);
        double reserve = RESERVE_STATUS_FACTOR;

        for (int i = 0; i < 5; i++) {
            if (gainStats[i] <= 0) continue;

            double current = currentStats[i];
            double gain = gainStats[i];

            // 软上限函数: 属性接近上限时收益递减
            double beforeVal = statusSoftFunction(current, reserve);
            double afterVal = statusSoftFunction(current + gain, reserve);
            double marginalGain = afterVal - beforeVal;

            // 乘以属性权重
            total += marginalGain * STATUS_WEIGHTS[i];
        }

        // 技能点价值
        if (ptGain > 0) {
            total += ptGain * 0.5;
        }

        return total;
    }

    /**
     * 软上限函数
     * 当x接近STATUS_CAP时，增长收益递减
     */
    private double statusSoftFunction(double x, double reserve) {
        double cap = STATUS_CAP;
        if (x >= cap) {
            // 超过软上限后收益极低
            return cap + (x - cap) * 0.1;
        } else if (x >= cap - reserve) {
            // 接近软上限时线性递减
            double ratio = (cap - x) / reserve;
            return x * (0.3 + 0.7 * ratio);
        } else {
            // 正常区间
            return x;
        }
    }

    /**
     * 体力评估函数
     * 体力越低价值越高 (边际效应)
     */
    private double vitalEvaluation(int vital, int maxVital) {
        if (maxVital <= 0) return 0;
        double ratio = (double) vital / maxVital;
        // 体力百分比越低，每点体力价值越高
        if (ratio < 0.2) return 3.0;
        if (ratio < 0.4) return 2.0;
        if (ratio < 0.6) return 1.2;
        if (ratio < 0.8) return 0.8;
        return 0.3;
    }

    /**
     * 计算体力系数 (随回合变化)
     * 初期体力价值高，末期体力价值低
     */
    private double calcVitalFactor(int turn, int maxTurn) {
        if (maxTurn <= 1) return VITAL_FACTOR_START;
        double progress = (double) (turn - 1) / (maxTurn - 1);
        return VITAL_FACTOR_START + (VITAL_FACTOR_END - VITAL_FACTOR_START) * progress;
    }

    /**
     * 获取剧本偏好加成数组
     */
    private double[] getTypeBonus(String scenario) {
        int[] bonus;
        if (scenario.contains("climax")) {
            bonus = CLIMAX;
        } else if (scenario.contains("aoharu")) {
            bonus = AOHARU;
        } else if (scenario.contains("granddrive")) {
            bonus = GRAND_DRIVE;
        } else if (scenario.contains("grandmasters")) {
            bonus = GRAND_MASTERS;
        } else if (scenario.contains("larc")) {
            bonus = LARC;
        } else if (scenario.contains("uaf")) {
            bonus = UAF;
        } else if (scenario.contains("harvest")) {
            bonus = HARVEST;
        } else if (scenario.contains("mecha")) {
            bonus = MECHA;
        } else if (scenario.contains("legends")) {
            bonus = LEGENDS;
        } else if (scenario.contains("desert")) {
            bonus = DESERT_ISLAND;
        } else if (scenario.contains("hotspring")) {
            bonus = HOT_SPRING;
        } else if (scenario.contains("dreams")) {
            bonus = DREAMS;
        } else {
            bonus = URA_DEFAULT; // 默认URA
        }

        double[] result = new double[5];
        for (int i = 0; i < 5; i++) {
            result[i] = bonus[i];
        }
        return result;
    }

    /**
     * 剧本专属加成
     */
    private double scenarioBonus(int trainIdx, JSONObject summary, JSONObject trData) {
        double bonus = 0.0;
        try {
            // 不同剧本的专属逻辑
            if (scenario.contains("uaf")) {
                // UAF: 考虑战术Pt
                if (summary.has("scenario_data")) {
                    JSONObject sd = summary.getJSONObject("scenario_data");
                    // UAF训练有额外战术Pt收益
                    if (trData.has("tactical_point")) {
                        int tp = trData.optInt("tactical_point", 0);
                        bonus += tp * 2.0;
                    }
                }
            } else if (scenario.contains("larc")) {
                // LArc: 考虑海外适应
                if (trData.has("aptitude_gain")) {
                    int apt = trData.optInt("aptitude_gain", 0);
                    bonus += apt * 3.0;
                }
            } else if (scenario.contains("granddrive")) {
                // GrandDrive: 团队Pt
                if (trData.has("team_point")) {
                    int tp = trData.optInt("team_point", 0);
                    bonus += tp * 1.5;
                }
            } else if (scenario.contains("climax")) {
                // Climax: 连续训练加成
                if (trData.optBoolean("continue_bonus", false)) {
                    bonus += 25.0;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "剧本专属加成计算失败: " + e.getMessage());
        }
        return bonus;
    }

    /**
     * Dreams(育马者杯)剧本专属加成
     */
    private double dreamsBonus(int trainIdx, JSONObject summary, JSONObject trData) {
        double bonus = 0.0;
        try {
            if (summary.has("scenario_data")) {
                JSONObject sd = summary.getJSONObject("scenario_data");
                // Dreams: 考虑羁绊特化
                if (sd.has("bond_special")) {
                    int special = sd.optInt("bond_special", 0);
                    bonus += special * 2.0;
                }
                // Dreams: 训练等级影响更大
                if (sd.has("training_level_bonus")) {
                    int tlb = sd.optInt("training_level_bonus", 0);
                    bonus += tlb * 5.0;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Dreams加成计算失败: " + e.getMessage());
        }
        return bonus;
    }

    // ==================== 辅助解析方法 ====================

    /**
     * 解析训练等级数组
     */
    private Map<Integer, Integer> parseTrainingLevels(JSONArray arr) {
        Map<Integer, Integer> map = new HashMap<>();
        if (arr == null) return map;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    int cmdId = obj.optInt("command_id", 0);
                    int level = obj.optInt("level", 1);
                    Integer idx = CMD_TO_IDX.get(cmdId);
                    if (idx != null) {
                        map.put(idx, level);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "解析训练等级失败: " + e.getMessage());
        }
        return map;
    }

    /**
     * 获取指定训练类型的等级
     */
    private int getTrainLevel(Map<Integer, Integer> map, int idx) {
        return map.getOrDefault(idx, 1);
    }

    /**
     * 计算平均羁绊值
     */
    private double calcAvgEvaluation(JSONArray arr) {
        if (arr == null || arr.length() == 0) return 0;
        double sum = 0;
        int count = 0;
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    sum += obj.optInt("evaluation", 0);
                    count++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "计算平均羁绊失败: " + e.getMessage());
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * 获取指定索引的训练数据
     * 按 [速, 耐, 力, 根, 智] 顺序
     */
    private JSONObject getTrainingData(JSONArray trainings, int idx) {
        if (trainings == null) return null;
        try {
            // 训练数据可能按CMD_ID排序，需要找到对应类型的
            for (int i = 0; i < trainings.length(); i++) {
                JSONObject tr = trainings.optJSONObject(i);
                if (tr != null) {
                    int cmdId = tr.optInt("command_id", 0);
                    Integer mappedIdx = CMD_TO_IDX.get(cmdId);
                    if (mappedIdx != null && mappedIdx == idx) {
                        return tr;
                    }
                    // 检查拉面剧本CMD
                    mappedIdx = RAMEN_CMD_TO_IDX.get(cmdId);
                    if (mappedIdx != null && mappedIdx == idx) {
                        return tr;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "获取训练数据失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 构建训练详情字符串
     */
    private String buildDetail(JSONObject summary, JSONArray trainings, int idx, Map<Integer, Integer> trainLevels) {
        StringBuilder sb = new StringBuilder();
        try {
            JSONObject tr = getTrainingData(trainings, idx);
            if (tr != null) {
                sb.append(TRAIN_LABELS[idx]).append("训练 ");

                // 属性增益
                if (tr.has("training_effect")) {
                    JSONObject effect = tr.optJSONObject("training_effect");
                    if (effect != null) {
                        int spd = effect.optInt("speed", 0);
                        int stm = effect.optInt("stamina", 0);
                        int pow = effect.optInt("power", 0);
                        int gut = effect.optInt("guts", 0);
                        int wiz = effect.optInt("wiz", 0);
                        int pt = effect.optInt("skill_point", 0);

                        if (spd > 0) sb.append("速+").append(spd).append(" ");
                        if (stm > 0) sb.append("耐+").append(stm).append(" ");
                        if (pow > 0) sb.append("力+").append(pow).append(" ");
                        if (gut > 0) sb.append("根+").append(gut).append(" ");
                        if (wiz > 0) sb.append("智+").append(wiz).append(" ");
                        if (pt > 0) sb.append("PT+").append(pt).append(" ");
                    }
                }

                // 体力消耗
                int vitalCost = tr.optInt("vital_cost", 0);
                if (vitalCost > 0) {
                    sb.append("| 体力-").append(vitalCost).append(" ");
                }

                // 失败率
                int failRate = tr.optInt("failure_rate", 0);
                if (failRate > 0) {
                    sb.append("| 失败率").append(failRate).append("% ");
                }

                // 训练等级
                int lv = getTrainLevel(trainLevels, idx);
                sb.append("| Lv.").append(lv);

                // 彩圈标记
                if (tr.optBoolean("is_shining", false)) {
                    sb.append(" [彩圈]");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "构建详情失败: " + e.getMessage());
        }
        return sb.toString().trim();
    }

    // ==================== 结果类 ====================

    /**
     * 训练评估结果
     */
    public static class EvalResult {
        public String bestType = "speed";       // "speed"/"stamina"/"power"/"guts"/"wisdom"/"rest"
        public String bestLabel = "速";         // "速"/"耐"/"力"/"根"/"智"/"休息"
        public String bestDetail = "";          // 详细属性增益
        public int bestColor = 0xFFE74C3C;      // 颜色
        public double bestScore = 0;            // 评分
        public double[] allScores = new double[5]; // 各选项评分
    }
}
