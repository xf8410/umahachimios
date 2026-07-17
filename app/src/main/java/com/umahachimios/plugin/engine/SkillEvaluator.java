package com.umahachimios.plugin.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * 技能评估器
 * 评估技能购买的价值，包括:
 * - 技能性价比 (效果/消耗)
 * - 技能与当前育成策略的匹配度
 * - 技能稀有度和推荐度
 * - 技能点是否充裕
 */
public class SkillEvaluator {
    private static final String TAG = "SkillEvaluator";

    // 技能类型优先级权重
    private static final double SPEED_SKILL_WEIGHT = 1.2;       // 速度类技能
    private static final double ACCEL_SKILL_WEIGHT = 1.5;       // 加速度类技能
    private static final double RECOVERY_SKILL_WEIGHT = 1.3;    // 回复类技能
    private static final double GOLD_SKILL_WEIGHT = 2.0;        // 金技能权重
    private static final double WHITE_SKILL_WEIGHT = 1.0;       // 白技能权重

    // 常用高性价比技能ID (马娘核心技能)
    private static final Map<Integer, Double> PRIORITY_SKILLS = new HashMap<>();

    static {
        // 速度/加速度系金技能 (高优先级)
        PRIORITY_SKILLS.put(10001, 3.0);  // ハヤテ一文字 (疾风一文字)
        PRIORITY_SKILLS.put(10021, 3.0);  // 功全程不绝
        PRIORITY_SKILLS.put(10041, 3.0);  // 一陣の風 (一阵之风)
        PRIORITY_SKILLS.put(10061, 3.0);  // 直線一気 (直线一气)
        PRIORITY_SKILLS.put(10081, 3.0);  // 末脚 (末脚)
        PRIORITY_SKILLS.put(10101, 3.0);  // 前途洋々 (前途洋洋)
        PRIORITY_SKILLS.put(10121, 3.0);  // timing爆発
        PRIORITY_SKILLS.put(10141, 3.0);  // 位置取り押し上げ
        PRIORITY_SKILLS.put(10161, 3.0);  // 食らいつき
        PRIORITY_SKILLS.put(20001, 3.0);  // 逃亡者 (逃亡者)
        PRIORITY_SKILLS.put(20041, 3.0);  //  difference掌握
        PRIORITY_SKILLS.put(20061, 3.0);  // 先頭の景色
        PRIORITY_SKILLS.put(20081, 3.0);  // 独占力 (独占力)
        PRIORITY_SKILLS.put(20101, 3.0);  // 迫る影 (迫る影)
        PRIORITY_SKILLS.put(20141, 3.0);  // 乗り換え上手 (换乘上手)

        // 回复系金技能
        PRIORITY_SKILLS.put(30011, 2.5);  // 川流不息
        PRIORITY_SKILLS.put(30021, 2.5);  // 好転一息 (好转一息)
        PRIORITY_SKILLS.put(30041, 2.5);  // 円弧のマエストロ (圆弧大师)
        PRIORITY_SKILLS.put(30061, 2.5);  // 快速恢复
        PRIORITY_SKILLS.put(30081, 2.5);  // 冷却 (冷却)

        // 绿技能 (被动属性加成)
        PRIORITY_SKILLS.put(10021, 2.0);  // 春/夏/秋/冬马娘
        PRIORITY_SKILLS.put(10022, 2.0);
        PRIORITY_SKILLS.put(10023, 2.0);
        PRIORITY_SKILLS.put(10024, 2.0);
        PRIORITY_SKILLS.put(10031, 2.0);  // 晴天/阴天/雨天
        PRIORITY_SKILLS.put(10032, 2.0);
        PRIORITY_SKILLS.put(10033, 2.0);
    }

    /**
     * 评估技能购买价值
     *
     * @param state    当前游戏状态
     * @param summary  hlpatch summary JSON
     * @return 最优技能购买评分
     */
    public double evaluate(GameState state, JSONObject summary) {
        double bestScore = 0;
        try {
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

                double score = evaluateSingleSkill(state, skill);
                if (score > bestScore) {
                    bestScore = score;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "技能评估失败: " + e.getMessage());
        }
        return bestScore;
    }

    /**
     * 评估单个技能
     *
     * @param state 当前游戏状态
     * @param skill 技能JSON数据
     * @return 技能购买评分
     */
    public double evaluateSingleSkill(GameState state, JSONObject skill) {
        double score = 0;
        try {
            int skillId = skill.optInt("skill_id", 0);
            int cost = skill.optInt("cost", 9999);
            int grade = skill.optInt("grade", 1);
            String rarity = skill.optString("rarity", "normal"); // "normal"/"rare"/"unique"
            boolean isRecommended = skill.optBoolean("recommended", false);
            boolean alreadyHave = skill.optBoolean("already_have", false);

            // 已拥有则跳过
            if (alreadyHave) {
                return -9999;
            }

            // 买不起则跳过
            if (cost > state.skillPt) {
                return -9999;
            }

            // 基础性价比 = 技能效果 / 消耗
            double baseValue = grade * 10.0;
            double costEfficiency = baseValue / Math.max(cost, 1);
            score += costEfficiency * 100.0;

            // 稀有度加成
            if ("unique".equals(rarity)) {
                score *= GOLD_SKILL_WEIGHT;
            } else if ("rare".equals(rarity)) {
                score *= 1.5;
            } else {
                score *= WHITE_SKILL_WEIGHT;
            }

            // 推荐技能加成
            if (isRecommended) {
                score *= 2.0;
            }

            // 优先级技能加成
            if (PRIORITY_SKILLS.containsKey(skillId)) {
                score *= PRIORITY_SKILLS.get(skillId);
            }

            // 技能类型加成
            String skillType = skill.optString("skill_type", "");
            switch (skillType) {
                case "speed":
                    score *= SPEED_SKILL_WEIGHT;
                    break;
                case "acceleration":
                    score *= ACCEL_SKILL_WEIGHT;
                    break;
                case "recovery":
                    score *= RECOVERY_SKILL_WEIGHT;
                    break;
                default:
                    break;
            }

            // 技能点充裕度调整
            double skillPtRatio = (double) state.skillPt / Math.max(cost, 1);
            if (skillPtRatio > 5) {
                score *= 1.2; // 技能点很多，更乐意买
            } else if (skillPtRatio < 1.5) {
                score *= 0.7; // 技能点紧张，更谨慎
            }

            // 育成后期更看重比赛技能
            if (state.turn > 55) {
                if ("speed".equals(skillType) || "acceleration".equals(skillType)) {
                    score *= 1.3;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "评估单个技能失败: " + e.getMessage());
        }
        return score;
    }

    /**
     * 获取最优技能详情
     *
     * @param state   当前游戏状态
     * @param summary hlpatch summary JSON
     * @return 最优技能JSON，没有则返回null
     */
    public JSONObject getBestSkill(GameState state, JSONObject summary) {
        JSONObject bestSkill = null;
        double bestScore = 0;
        try {
            if (!summary.has("skill_shop")) {
                return null;
            }
            JSONArray shopSkills = summary.getJSONArray("skill_shop");
            if (shopSkills == null || shopSkills.length() == 0) {
                return null;
            }

            for (int i = 0; i < shopSkills.length(); i++) {
                JSONObject skill = shopSkills.optJSONObject(i);
                if (skill == null) continue;

                double score = evaluateSingleSkill(state, skill);
                if (score > bestScore) {
                    bestScore = score;
                    bestSkill = skill;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "获取最优技能失败: " + e.getMessage());
        }
        return bestSkill;
    }

    /**
     * 构建技能详情字符串
     */
    public String getSkillDetail(JSONObject skill) {
        StringBuilder sb = new StringBuilder();
        try {
            String name = skill.optString("name", "未知技能");
            int cost = skill.optInt("cost", 0);
            String rarity = skill.optString("rarity", "");
            boolean isRecommended = skill.optBoolean("recommended", false);

            sb.append(name);
            sb.append(" [消耗:").append(cost).append("PT]");

            if ("unique".equals(rarity)) {
                sb.append(" [金]");
            } else if ("rare".equals(rarity)) {
                sb.append(" [银]");
            }

            if (isRecommended) {
                sb.append(" [推荐]");
            }

        } catch (Exception e) {
            sb.append("技能信息解析失败");
        }
        return sb.toString();
    }

    /**
     * 检查是否应该去技能店
     * 技能点充裕且有好技能时返回true
     */
    public boolean shouldVisitSkillShop(GameState state, JSONObject summary) {
        try {
            if (!summary.has("skill_shop")) {
                return false;
            }
            // 技能点足够多才考虑去
            if (state.skillPt < 200) {
                return false;
            }
            double bestScore = evaluate(state, summary);
            return bestScore > 100.0;
        } catch (Exception e) {
            return false;
        }
    }
}
