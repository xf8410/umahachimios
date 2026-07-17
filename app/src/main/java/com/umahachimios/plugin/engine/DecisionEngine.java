package com.umahachimios.plugin.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

import com.umahachimios.plugin.engine.TrainingEvaluator;

/**
 * 主决策引擎
 * 整合训练选择、休息判断、外出判断、比赛判断、技能购买等多种决策
 * 根据当前游戏状态(GameState)综合评估，返回最佳行动方案
 *
 * 决策优先级 (由高到低):
 * 1. 目标比赛检查 - 有重要比赛时优先参赛
 * 2. 体力检查 - 体力过低时考虑休息
 * 3. 干劲检查 - 干劲过低时考虑外出
 * 4. 技能店 - 在技能店且有好技能时购买
 * 5. 训练选择 - 选择最优训练
 */
public class DecisionEngine {
    private static final String TAG = "DecisionEngine";

    // ==================== 决策阈值 ====================
    private static final int VITAL_LOW_THRESHOLD = 30;        // 体力低阈值
    private static final int VITAL_CRITICAL_THRESHOLD = 15;   // 体力危险阈值
    private static final int MOTIVATION_LOW_THRESHOLD = 3;    // 干劲低阈值 (1-5)
    private static final int REST_SCORE_THRESHOLD = -200;     // 休息评分阈值
    private static final int OUTING_SCORE_THRESHOLD = 100;    // 外出评分阈值
    private static final int RACE_SCORE_THRESHOLD = 200;      // 比赛评分阈值
    private static final int SKILL_SCORE_THRESHOLD = 150;     // 技能购买评分阈值

    // ==================== 评分权重 ====================
    private static final double REST_VITAL_WEIGHT = 3.0;      // 休息体力恢复权重
    private static final double REST_MOTIVATION_WEIGHT = 2.0; // 休息干劲恢复权重
    private static final double OUTING_MOOD_WEIGHT = 2.5;     // 外出提升干劲权重
    private static final double RACE_FAN_WEIGHT = 1.5;        // 比赛粉丝权重
    private static final double RACE_STAT_WEIGHT = 2.0;       // 比赛属性权重

    // ==================== 子评估器 ====================
    private TrainingEvaluator trainingEval;
    private RaceEvaluator raceEval;
    private SkillEvaluator skillEval;

    // ==================== 训练类型映射 ====================
    private static final String[] TRAIN_ACTIONS = {
            "train_speed", "train_stamina", "train_power", "train_guts", "train_wisdom"
    };
    private static final String[] TRAIN_LABELS = {"速", "耐", "力", "根", "智"};
    private static final int[] TRAIN_COLORS = {
            0xFFE74C3C, // 速 - 红
            0xFF3498DB, // 耐 - 蓝
            0xFFF39C12, // 力 - 橙
            0xFFE67E22, // 根 - 深橙
            0xFF9B59B6  // 智 - 紫
    };

    public DecisionEngine() {
        this.trainingEval = new TrainingEvaluator();
        this.raceEval = new RaceEvaluator();
        this.skillEval = new SkillEvaluator();
    }

    /**
     * 主入口：给定GameState，返回最佳行动
     *
     * @param state 当前游戏状态
     * @return DecisionResult 包含最佳行动及详细分析
     */
    public DecisionResult decide(GameState state) {
        DecisionResult result = new DecisionResult();

        try {
            Log.d(TAG, "开始决策: " + state.toString());

            // 解析JSON数据
            JSONObject summary = buildSummaryJson(state);

            // 设置剧本
            trainingEval.setScenario(state.scenario);

            // ========== 1. 训练评估 ==========
            TrainingEvaluator.EvalResult trainResult = trainingEval.evaluate(summary);
            double bestTrainScore = trainResult.bestScore;
            String bestTrainType = trainResult.bestType;
            int bestTrainIdx = getTrainIndex(bestTrainType);

            // ========== 2. 休息评估 ==========
            double restScore = evaluateRest(state);

            // ========== 3. 外出评估 ==========
            double outingScore = evaluateOuting(state);

            // ========== 4. 比赛评估 ==========
            double raceScore = evaluateRace(state, summary);

            // ========== 5. 技能购买评估 ==========
            double skillScore = evaluateSkill(state, summary);

            // ========== 综合决策 ==========
            // 收集所有选项评分
            double[] allScores = new double[6]; // [速, 耐, 力, 根, 智, 休息]
            System.arraycopy(trainResult.allScores, 0, allScores, 0, 5);
            allScores[5] = restScore;

            result.allScores = allScores;

            // 决策逻辑
            String decision;
            String decisionLabel;
            int decisionColor;
            double bestScore;
            StringBuilder reason = new StringBuilder();

            // 优先级1: 体力极低 -> 强制休息
            if (state.vital <= VITAL_CRITICAL_THRESHOLD) {
                decision = "rest";
                decisionLabel = "休息";
                decisionColor = 0xFF2ECC71; // 绿色
                bestScore = restScore;
                reason.append("体力极低(").append(state.vital).append(")，必须休息恢复体力");
            }
            // 优先级2: 有重要比赛且评分高 -> 参赛
            else if (raceScore > RACE_SCORE_THRESHOLD && hasTargetRace(state, summary)) {
                decision = "race";
                decisionLabel = "比赛";
                decisionColor = 0xFFE91E63; // 粉红
                bestScore = raceScore;
                reason.append("有目标比赛需要参加，粉丝=").append(state.fans);
            }
            // 优先级3: 干劲极低 + 外出评分高 -> 外出
            else if (state.motivation < 2 && outingScore > OUTING_SCORE_THRESHOLD) {
                decision = "outing";
                decisionLabel = "外出";
                decisionColor = 0xFF00BCD4; // 青色
                bestScore = outingScore;
                reason.append("干劲极低(").append(getMotivationText(state.motivation))
                        .append(")，需要外出恢复干劲");
            }
            // 优先级4: 技能店有好技能 -> 购买
            else if (skillScore > SKILL_SCORE_THRESHOLD && isInSkillShop(state, summary)) {
                decision = "skill";
                decisionLabel = "技能";
                decisionColor = 0xFF673AB7; // 深紫
                bestScore = skillScore;
                reason.append("技能店有高性价比技能可购买，技能Pt=").append(state.skillPt);
            }
            // 优先级5: 体力低 + 休息评分优于最差训练 -> 休息
            else if (state.vital <= VITAL_LOW_THRESHOLD && restScore >= bestTrainScore * 0.7) {
                decision = "rest";
                decisionLabel = "休息";
                decisionColor = 0xFF2ECC71; // 绿色
                bestScore = restScore;
                reason.append("体力较低(").append(state.vital).append(")，建议休息恢复");
            }
            // 优先级6: 干劲低 + 外出评分高 -> 外出
            else if (state.motivation < MOTIVATION_LOW_THRESHOLD && outingScore > bestTrainScore * 0.8) {
                decision = "outing";
                decisionLabel = "外出";
                decisionColor = 0xFF00BCD4; // 青色
                bestScore = outingScore;
                reason.append("干劲较低(").append(getMotivationText(state.motivation))
                        .append(")，建议外出恢复");
            }
            // 默认: 最优训练
            else {
                if (bestTrainIdx >= 0 && bestTrainIdx < 5) {
                    decision = TRAIN_ACTIONS[bestTrainIdx];
                    decisionLabel = TRAIN_LABELS[bestTrainIdx];
                    decisionColor = TRAIN_COLORS[bestTrainIdx];
                    bestScore = bestTrainScore;
                    reason.append(trainResult.bestDetail);
                } else {
                    // 训练评估失败，默认休息
                    decision = "rest";
                    decisionLabel = "休息";
                    decisionColor = 0xFF2ECC71;
                    bestScore = restScore;
                    reason.append("训练评估异常，选择休息");
                }
            }

            result.action = decision;
            result.actionLabel = decisionLabel;
            result.actionColor = decisionColor;
            result.score = bestScore;
            result.reason = reason.toString();

            // 构建详细说明
            StringBuilder detail = new StringBuilder();
            detail.append("【推荐: ").append(decisionLabel).append("】\n");
            detail.append("评分: ").append(String.format("%.1f", bestScore)).append("\n");
            detail.append("理由: ").append(reason.toString()).append("\n\n");

            detail.append("--- 各选项评分 ---\n");
            for (int i = 0; i < 5; i++) {
                detail.append(TRAIN_LABELS[i]).append(": ")
                        .append(String.format("%.1f", allScores[i])).append("\n");
            }
            detail.append("休息: ").append(String.format("%.1f", restScore)).append("\n");
            detail.append("外出: ").append(String.format("%.1f", outingScore)).append("\n");
            detail.append("比赛: ").append(String.format("%.1f", raceScore)).append("\n");
            detail.append("技能: ").append(String.format("%.1f", skillScore)).append("\n");

            detail.append("\n--- 状态 ---\n");
            detail.append("回合: ").append(state.turn).append(" (").append(state.stage).append(")\n");
            detail.append("属性: [").append(state.speed).append(",").append(state.stamina)
                    .append(",").append(state.power).append(",").append(state.guts)
                    .append(",").append(state.wisdom).append("]\n");
            detail.append("体力: ").append(state.vital).append("/").append(state.maxVital).append("\n");
            detail.append("干劲: ").append(getMotivationText(state.motivation)).append("\n");
            detail.append("技能Pt: ").append(state.skillPt).append("\n");
            detail.append("粉丝: ").append(state.fans);

            result.detail = detail.toString();

            Log.d(TAG, "决策结果: " + decision + "[" + decisionLabel + "] 评分=" + bestScore);

        } catch (Exception e) {
            Log.e(TAG, "决策失败: " + e.getMessage(), e);
            // 异常时默认休息
            result.action = "rest";
            result.actionLabel = "休息";
            result.actionColor = 0xFF2ECC71;
            result.score = 0;
            result.reason = "决策异常，默认休息: " + e.getMessage();
            result.detail = "决策引擎发生异常，建议选择休息";
        }

        return result;
    }

    /**
     * 评估休息价值
     */
    private double evaluateRest(GameState state) {
        double score = 0.0;

        // 体力恢复价值
        double vitalPercent = state.getVitalPercent();
        int vitalRecover = state.maxVital - state.vital; // 休息恢复的体力量

        // 体力越低，休息价值越高
        if (vitalPercent < 20) {
            score += vitalRecover * REST_VITAL_WEIGHT * 3.0;
        } else if (vitalPercent < 35) {
            score += vitalRecover * REST_VITAL_WEIGHT * 2.0;
        } else if (vitalPercent < 50) {
            score += vitalRecover * REST_VITAL_WEIGHT * 1.2;
        } else if (vitalPercent < 70) {
            score += vitalRecover * REST_VITAL_WEIGHT * 0.6;
        } else {
            score += vitalRecover * REST_VITAL_WEIGHT * 0.2; // 体力充足时休息价值低
        }

        // 干劲恢复 (休息有时也能小幅恢复干劲)
        if (state.motivation < 3) {
            score += (3 - state.motivation) * REST_MOTIVATION_WEIGHT * 10.0;
        }

        // 负面状态恢复概率
        if (state.hasNegativeState()) {
            score += 80.0; // 休息有概率恢复负面状态
        }

        return score;
    }

    /**
     * 评估外出价值
     */
    private double evaluateOuting(GameState state) {
        double score = 0.0;

        // 干劲提升价值
        if (state.motivation <= 1) {
            score += 200.0 * OUTING_MOOD_WEIGHT; // 绝不调 -> 优先级极高
        } else if (state.motivation == 2) {
            score += 120.0 * OUTING_MOOD_WEIGHT; // 不调 -> 优先级高
        } else if (state.motivation == 3) {
            score += 50.0 * OUTING_MOOD_WEIGHT;  // 普通 -> 有一定价值
        } else {
            score -= 50.0; // 好调/绝好调时外出价值低
        }

        // 体力恢复价值 (外出也能恢复少量体力)
        if (state.vital < 50) {
            score += (50 - state.vital) * 1.5;
        }

        // 支援卡羁绊 (外出可触发支援卡事件)
        score += 30.0;

        return score;
    }

    /**
     * 评估比赛价值
     */
    private double evaluateRace(GameState state, JSONObject summary) {
        double score = 0.0;
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

                boolean isTarget = race.optBoolean("is_target", false);
                boolean isEntry = race.optBoolean("is_entry", false);

                if (isTarget || isEntry) {
                    // 目标比赛价值
                    int fanBonus = race.optInt("fan_count", 0);
                    score += fanBonus * RACE_FAN_WEIGHT;

                    // 比赛奖励属性
                    if (race.has("bonus")) {
                        JSONObject bonus = race.getJSONObject("bonus");
                        int spd = bonus.optInt("speed", 0);
                        int stm = bonus.optInt("stamina", 0);
                        int pow = bonus.optInt("power", 0);
                        int gut = bonus.optInt("guts", 0);
                        int wiz = bonus.optInt("wiz", 0);
                        int statBonus = spd + stm + pow + gut + wiz;
                        score += statBonus * RACE_STAT_WEIGHT;
                    }

                    // 技能点奖励
                    int skillPtBonus = race.optInt("skill_pt", 0);
                    score += skillPtBonus * 0.8;

                    // 距离当前时间越近的比赛价值越高
                    int raceTurn = race.optInt("turn", state.turn);
                    int turnsToRace = raceTurn - state.turn;
                    if (turnsToRace >= 0 && turnsToRace <= 2) {
                        score *= (1.5 - turnsToRace * 0.2); // 近期比赛加成
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "比赛评估失败: " + e.getMessage());
        }
        return score;
    }

    /**
     * 评估技能购买价值
     */
    private double evaluateSkill(GameState state, JSONObject summary) {
        double score = 0.0;
        try {
            if (!isInSkillShop(state, summary)) {
                return 0;
            }

            if (!summary.has("skill_shop")) {
                return 0;
            }
            JSONArray shopSkills = summary.getJSONArray("skill_shop");
            if (shopSkills == null || shopSkills.length() == 0) {
                return 0;
            }

            for (int i = 0; i < shopSkills.length(); i++) {
                JSONObject skill = shopSkills.optJSONObject(i);
                if (skill == null) continue;

                int skillId = skill.optInt("skill_id", 0);
                int cost = skill.optInt("cost", 9999);
                int grade = skill.optInt("grade", 1);
                boolean isRecommended = skill.optBoolean("recommended", false);

                if (cost > state.skillPt) {
                    continue; // 买不起
                }

                // 性价比 = 技能等级 / 消耗
                double value = (grade * 50.0) / (cost + 1);

                // 推荐技能加成
                if (isRecommended) {
                    value *= 2.0;
                }

                // 技能点充裕时更愿意买
                if (state.skillPt > 1000) {
                    value *= 1.3;
                }

                if (value > score) {
                    score = value;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "技能评估失败: " + e.getMessage());
        }
        return score;
    }

    // ==================== 辅助方法 ====================

    /**
     * 检查是否有目标比赛
     */
    private boolean hasTargetRace(GameState state, JSONObject summary) {
        try {
            if (!summary.has("race_info_array")) return false;
            JSONArray races = summary.getJSONArray("race_info_array");
            for (int i = 0; i < races.length(); i++) {
                JSONObject race = races.optJSONObject(i);
                if (race != null && race.optBoolean("is_target", false)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "检查目标比赛失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 检查是否在技能店
     */
    private boolean isInSkillShop(GameState state, JSONObject summary) {
        try {
            return summary.has("skill_shop") && summary.optJSONArray("skill_shop") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取训练类型索引
     */
    private int getTrainIndex(String type) {
        for (int i = 0; i < 5; i++) {
            if (TRAIN_TYPES[i].equals(type)) {
                return i;
            }
        }
        return -1;
    }

    private static final String[] TRAIN_TYPES = {"speed", "stamina", "power", "guts", "wisdom"};

    /**
     * 将GameState构建为summary JSON格式 (供子评估器使用)
     */
    private JSONObject buildSummaryJson(GameState state) {
        try {
            JSONObject summary = new JSONObject();

            // chara_info
            JSONObject chara = new JSONObject();
            chara.put("speed", state.speed);
            chara.put("stamina", state.stamina);
            chara.put("power", state.power);
            chara.put("guts", state.guts);
            chara.put("wiz", state.wisdom);
            chara.put("vital", state.vital);
            chara.put("max_vital", state.maxVital);
            chara.put("motivation", state.motivation);
            chara.put("turn", state.turn);
            chara.put("scenario_id", state.scenario);
            chara.put("skill_point", state.skillPt);
            chara.put("fans", state.fans);

            if (state.charaEffectIds != null && state.charaEffectIds.length > 0) {
                JSONArray effects = new JSONArray();
                for (int id : state.charaEffectIds) {
                    effects.put(id);
                }
                chara.put("chara_effect_id_array", effects);
            }

            if (state.skillsJson != null) {
                chara.put("skill_array", new JSONArray(state.skillsJson));
            }

            summary.put("chara_info", chara);

            // training_info
            if (state.trainingsJson != null) {
                summary.put("training_info", new JSONArray(state.trainingsJson));
            }

            // evaluation_info_array
            if (state.evaluationJson != null) {
                summary.put("evaluation_info_array", new JSONArray(state.evaluationJson));
            }

            // buff_array
            if (state.buffsJson != null) {
                summary.put("buff_array", new JSONArray(state.buffsJson));
            }

            // train_level_info
            if (state.trainingLevelsJson != null) {
                summary.put("train_level_info", new JSONArray(state.trainingLevelsJson));
            }

            // scenario_data
            if (state.scenarioDataJson != null) {
                summary.put("scenario_data", new JSONObject(state.scenarioDataJson));
            }

            return summary;

        } catch (Exception e) {
            Log.e(TAG, "构建summary JSON失败: " + e.getMessage(), e);
            return new JSONObject();
        }
    }

    /**
     * 获取干劲文本描述
     */
    private String getMotivationText(int motivation) {
        switch (motivation) {
            case 1: return "绝不调";
            case 2: return "不调";
            case 3: return "普通";
            case 4: return "好调";
            case 5: return "绝好调";
            default: return "未知";
        }
    }

    // ==================== 结果类 ====================

    /**
     * 决策结果
     */
    public static class DecisionResult {
        public String action;           // "train_speed"/"train_stamina"/"train_power"/"train_guts"/"train_wisdom"/"rest"/"outing"/"race"/"skill"
        public String actionLabel;      // 中文标签 "速"/"耐"/"力"/"根"/"智"/"休息"/"外出"/"比赛"/"技能"
        public String detail;           // 详细说明 (包含各选项评分和状态)
        public int actionColor;         // 显示颜色
        public double score;            // 评分
        public double[] allScores;      // 各选项评分 [速, 耐, 力, 根, 智, ...]
        public String reason;           // 推荐理由
    }
}
