package com.umahachimios.plugin.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

/**
 * 比赛评估器
 * 评估目标比赛的参赛价值，包括:
 * - 粉丝数收益
 * - 属性奖励
 * - 技能点奖励
 * - 比赛时间紧迫度
 * - 当前体力是否足够参赛
 */
public class RaceEvaluator {
    private static final String TAG = "RaceEvaluator";

    // 比赛类型权重
    private static final double TARGET_RACE_WEIGHT = 3.0;     // 目标比赛权重
    private static final double OPTIONAL_RACE_WEIGHT = 1.0;   // 可选比赛权重
    private static final double G1_RACE_WEIGHT = 2.5;         // G1比赛权重
    private static final double G2_RACE_WEIGHT = 1.8;         // G2比赛权重
    private static final double G3_RACE_WEIGHT = 1.3;         // G3比赛权重

    // 距离适性参数
    private static final double SUITABILITY_MATCH_BONUS = 50.0; // 适性匹配加成

    /**
     * 评估所有可参赛的比赛，返回最优比赛评分
     *
     * @param state   当前游戏状态
     * @param summary hlpatch summary JSON
     * @return 最优比赛评分 (没有比赛时返回0)
     */
    public double evaluate(GameState state, JSONObject summary) {
        double bestScore = 0;
        try {
            if (!summary.has("race_info_array")) {
                return 0;
            }
            JSONArray races = summary.getJSONArray("race_info_array");
            if (races == null || races.length() == 0) {
                return 0;
            }

            for (int i = 0; i < races.length(); i++) {
                JSONObject race = races.optJSONObject(i);
                if (race == null) continue;

                double score = evaluateSingleRace(state, race);
                if (score > bestScore) {
                    bestScore = score;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "比赛评估失败: " + e.getMessage());
        }
        return bestScore;
    }

    /**
     * 评估单个比赛
     *
     * @param state 当前游戏状态
     * @param race  比赛JSON数据
     * @return 比赛评分
     */
    public double evaluateSingleRace(GameState state, JSONObject race) {
        double score = 0;
        try {
            boolean isTarget = race.optBoolean("is_target", false);
            boolean isEntry = race.optBoolean("is_entry", false);
            String raceGrade = race.optString("grade", ""); // "g1"/"g2"/"g3"/"op"/"pre"

            // 基础权重
            if (isTarget || isEntry) {
                score += 100.0 * TARGET_RACE_WEIGHT;
            } else {
                score += 100.0 * OPTIONAL_RACE_WEIGHT;
            }

            // 比赛等级加成
            switch (raceGrade) {
                case "g1":
                    score += 200.0 * G1_RACE_WEIGHT;
                    break;
                case "g2":
                    score += 150.0 * G2_RACE_WEIGHT;
                    break;
                case "g3":
                    score += 100.0 * G3_RACE_WEIGHT;
                    break;
                default:
                    score += 50.0;
                    break;
            }

            // 粉丝收益
            int fanCount = race.optInt("fan_count", 0);
            score += fanCount * 0.5;

            // 属性奖励
            if (race.has("bonus")) {
                JSONObject bonus = race.optJSONObject("bonus");
                if (bonus != null) {
                    int spd = bonus.optInt("speed", 0);
                    int stm = bonus.optInt("stamina", 0);
                    int pow = bonus.optInt("power", 0);
                    int gut = bonus.optInt("guts", 0);
                    int wiz = bonus.optInt("wiz", 0);
                    score += (spd + stm + pow + gut + wiz) * 3.0;
                }
            }

            // 技能点奖励
            int skillPtReward = race.optInt("skill_pt", 0);
            score += skillPtReward * 0.5;

            // 距离适性
            String distanceType = race.optString("distance_type", "");
            if (isDistanceSuitable(state, distanceType)) {
                score += SUITABILITY_MATCH_BONUS;
            }

            // 体力检查 - 体力过低时惩罚
            if (state.vital < 30) {
                score -= (30 - state.vital) * 10.0;
            }

            // 干劲影响
            score *= state.getMoodMultiplier();

            // 负面状态惩罚
            if (state.hasNegativeState()) {
                score *= 0.6;
            }

        } catch (Exception e) {
            Log.w(TAG, "评估单场比赛失败: " + e.getMessage());
        }
        return score;
    }

    /**
     * 检查距离适性是否匹配
     * 根据当前属性判断是否有足够的距离适性
     */
    private boolean isDistanceSuitable(GameState state, String distanceType) {
        // 短距离: 速度重要
        // 英里: 速度和力量
        // 中距离: 耐力和速度
        // 长距离: 耐力根性
        if (distanceType == null || distanceType.isEmpty()) {
            return true; // 默认认为合适
        }
        switch (distanceType) {
            case "short":
                return state.speed > 400;
            case "mile":
                return state.speed > 350 && state.power > 300;
            case "middle":
                return state.stamina > 350 && state.speed > 300;
            case "long":
                return state.stamina > 400 && state.guts > 300;
            default:
                return true;
        }
    }

    /**
     * 获取比赛推荐详情
     */
    public String getRaceDetail(JSONObject race) {
        StringBuilder sb = new StringBuilder();
        try {
            String name = race.optString("name", "未知比赛");
            String grade = race.optString("grade", "").toUpperCase();
            int fans = race.optInt("fan_count", 0);

            sb.append(grade).append(" ").append(name);
            sb.append(" (粉丝+").append(fans).append(")");

            if (race.optBoolean("is_target", false)) {
                sb.append(" [目标]");
            }
        } catch (Exception e) {
            sb.append("比赛信息解析失败");
        }
        return sb.toString();
    }
}
