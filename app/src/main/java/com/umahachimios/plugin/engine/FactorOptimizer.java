package com.umahachimios.plugin.engine;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 因子与PT优化器
 * 负责因子继承评估、SL过滤策略、PT使用优化
 */
public class FactorOptimizer {
    private static final String TAG = "FactorOpt";

    // 因子优先级权重表: 因子类型_星数 -> 基础权重
    private static final Map<String, Double> FACTOR_PRIORITIES;

    // 属性名到中文的映射
    private static final Map<String, String> STAT_NAME_MAP;

    static {
        // ==================== 初始化因子优先级 ====================
        FACTOR_PRIORITIES = new HashMap<>();

        // 蓝因子(属性因子): 最重要的因子
        FACTOR_PRIORITIES.put("blue_3", 10.0);   // 3星蓝因子
        FACTOR_PRIORITIES.put("blue_2", 5.0);    // 2星蓝因子
        FACTOR_PRIORITIES.put("blue_1", 2.0);    // 1星蓝因子

        // 红因子(距离适性因子)
        FACTOR_PRIORITIES.put("red_3", 8.0);     // 3星红因子
        FACTOR_PRIORITIES.put("red_2", 4.0);     // 2星红因子
        FACTOR_PRIORITIES.put("red_1", 1.5);     // 1星红因子

        // 绿因子(固有技能因子)
        FACTOR_PRIORITIES.put("green_3", 6.0);   // 3星绿因子
        FACTOR_PRIORITIES.put("green_2", 3.0);   // 2星绿因子
        FACTOR_PRIORITIES.put("green_1", 1.0);   // 1星绿因子

        // 白因子(技能hint因子)
        FACTOR_PRIORITIES.put("white_3", 3.0);   // 3星白因子
        FACTOR_PRIORITIES.put("white_2", 1.5);   // 2星白因子
        FACTOR_PRIORITIES.put("white_1", 0.5);   // 1星白因子

        // ==================== 初始化属性名称映射 ====================
        STAT_NAME_MAP = new HashMap<>();
        STAT_NAME_MAP.put("speed", "速度");
        STAT_NAME_MAP.put("stamina", "耐力");
        STAT_NAME_MAP.put("power", "力量");
        STAT_NAME_MAP.put("guts", "根性");
        STAT_NAME_MAP.put("wisdom", "智力");
    }

    /**
     * 因子数据结构
     */
    public static class Factor {
        public String type;       // "blue"/"red"/"green"/"white"
        public int stars;         // 1-3
        public String stat;       // 蓝因子对应属性 (speed/stamina/power/guts/wisdom)
        public String distance;   // 红因子对应距离 (short/mile/middle/long)
        public String skillName;  // 白/绿因子对应技能名

        public Factor() {
            this.stars = 1;
        }

        public Factor(String type, int stars, String stat, String distance, String skillName) {
            this.type = type;
            this.stars = Math.max(1, Math.min(3, stars));
            this.stat = stat;
            this.distance = distance;
            this.skillName = skillName;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            if ("blue".equals(type)) {
                sb.append("蓝");
                String statName = STAT_NAME_MAP.getOrDefault(stat, stat);
                sb.append(statName);
            } else if ("red".equals(type)) {
                sb.append("红");
                sb.append(distance != null ? distance : "?");
            } else if ("green".equals(type)) {
                sb.append("绿");
                sb.append(skillName != null ? skillName : "?");
            } else if ("white".equals(type)) {
                sb.append("白");
                sb.append(skillName != null ? skillName : "?");
            } else {
                sb.append(type);
            }
            sb.append("_").append(stars).append("星]");
            return sb.toString();
        }
    }

    /**
     * 目标构建配置
     */
    public static class TargetBuild {
        public List<String> priorityStats = new ArrayList<>();  // 优先属性 ["speed", "power"]
        public String targetDistance = "middle";                // 目标距离 short/mile/middle/long
        public String targetGround = "turf";                   // 目标场地 turf/dirt

        public TargetBuild() {
        }

        public TargetBuild(List<String> priorityStats, String targetDistance, String targetGround) {
            if (priorityStats != null) {
                this.priorityStats = new ArrayList<>(priorityStats);
            }
            this.targetDistance = targetDistance != null ? targetDistance : "middle";
            this.targetGround = targetGround != null ? targetGround : "turf";
        }
    }

    /**
     * 过滤统计信息
     */
    public static class FilterStats {
        public int totalAttempts;        // 总尝试次数
        public int accepted;             // 接受次数
        public double bestScore;         // 最佳分数
        public double acceptanceRate;    // 接受率
        public String estimatedTimePerAttempt; // 每次尝试估计时间

        @Override
        public String toString() {
            return String.format("FilterStats{尝试=%d, 接受=%d, 最佳=%.1f, 接受率=%.2f%%}",
                    totalAttempts, accepted, bestScore, acceptanceRate * 100);
        }
    }

    /**
     * PT优化结果
     */
    public static class PtOptimizationResult {
        public List<SkillEvaluator.SkillEvalResult> selectedSkills;  // 选中的技能列表
        public int totalPtCost;     // 总PT消耗
        public int remainingPt;     // 剩余PT
        public double totalValue;   // 总价值
        public double efficiency;   // 整体效率

        @Override
        public String toString() {
            int skillCount = selectedSkills != null ? selectedSkills.size() : 0;
            return String.format("PtOptResult{技能=%d, 消耗=%d, 剩余=%d, 价值=%.2f, 效率=%.5f}",
                    skillCount, totalPtCost, remainingPt, totalValue, efficiency);
        }
    }

    /**
     * 评估因子继承的综合得分
     *
     * @param factors 因子列表
     * @param target  目标构建
     * @return 综合得分
     */
    public static double evaluateInheritance(List<Factor> factors, TargetBuild target) {
        // 边界检查
        if (factors == null || factors.isEmpty()) {
            return 0.0;
        }
        if (target == null) {
            target = new TargetBuild();
        }

        double score = 0.0;
        for (Factor f : factors) {
            if (f == null) continue;

            String key = f.type + "_" + f.stars;
            double value = FACTOR_PRIORITIES.getOrDefault(key, 0.0);

            // 如果因子匹配目标构建，价值翻倍
            if (matchesBuild(f, target)) {
                value *= 2.0;
                Log.v(TAG, "因子匹配构建: " + f + " 价值翻倍=" + value);
            }

            score += value;
        }

        return score;
    }

    /**
     * 检查因子是否匹配目标构建
     */
    private static boolean matchesBuild(Factor f, TargetBuild target) {
        if (f == null || target == null) {
            return false;
        }

        if ("blue".equals(f.type)) {
            // 蓝因子匹配: 属性在优先列表中
            return f.stat != null && target.priorityStats != null
                    && target.priorityStats.contains(f.stat);
        } else if ("red".equals(f.type)) {
            // 红因子匹配: 距离匹配目标距离
            return f.distance != null && f.distance.equals(target.targetDistance);
        }
        // 绿/白因子通常都有用
        return true;
    }

    /**
     * SL过滤器
     * 用于S/L（Save/Load）策略中判断是否接受当前因子组合
     */
    public static class SLFilter {
        private TargetBuild target;
        private double threshold;
        private int attempts = 0;
        private int accepted = 0;
        private double bestScore = 0.0;
        private List<Factor> bestFactors = null;

        public SLFilter(TargetBuild target, double threshold) {
            this.target = target != null ? target : new TargetBuild();
            this.threshold = Math.max(0, threshold);
        }

        /**
         * 检查当前因子是否满足过滤条件
         *
         * @param factors 当前因子列表
         * @return true=接受, false=拒绝(需要重新抽)
         */
        public boolean shouldAccept(List<Factor> factors) {
            attempts++;
            double score = evaluateInheritance(factors, target);

            if (score > bestScore) {
                bestScore = score;
                bestFactors = factors != null ? new ArrayList<>(factors) : null;
            }

            if (score >= threshold) {
                accepted++;
                Log.i(TAG, "SL过滤接受: 得分=" + String.format("%.1f", score)
                        + ", 阈值=" + threshold);
                return true;
            }

            return false;
        }

        /**
         * 快速检查（不增加计数器）
         */
        public boolean checkScore(List<Factor> factors) {
            double score = evaluateInheritance(factors, target);
            return score >= threshold;
        }

        /**
         * 获取过滤统计
         */
        public FilterStats getStats() {
            FilterStats s = new FilterStats();
            s.totalAttempts = attempts;
            s.accepted = accepted;
            s.bestScore = bestScore;
            s.acceptanceRate = attempts > 0 ? (double) accepted / attempts : 0.0;
            s.estimatedTimePerAttempt = "45-60秒";
            return s;
        }

        /**
         * 获取最佳因子组合
         */
        public List<Factor> getBestFactors() {
            return bestFactors != null ? new ArrayList<>(bestFactors) : null;
        }

        /**
         * 获取最佳分数
         */
        public double getBestScore() {
            return bestScore;
        }

        /**
         * 重置计数器
         */
        public void reset() {
            attempts = 0;
            accepted = 0;
            bestScore = 0.0;
            bestFactors = null;
        }

        /**
         * 动态调整阈值（用于渐进式过滤）
         */
        public void adjustThreshold(double delta) {
            this.threshold = Math.max(0, this.threshold + delta);
        }
    }

    /**
     * 创建预设的SL过滤策略
     *
     * @param strategy 策略名称
     * @param target   目标构建
     * @return SL过滤器实例
     */
    public static SLFilter createFilter(String strategy, TargetBuild target) {
        if (strategy == null || strategy.isEmpty()) {
            strategy = "default";
        }
        if (target == null) {
            target = new TargetBuild();
        }

        switch (strategy) {
            case "speed_build_strict":
                // 严格速度构建: 必须有3星蓝因子匹配目标属性
                Log.d(TAG, "创建严格速度构建过滤器, 阈值=20.0");
                return new SLFilter(target, 20.0);

            case "balanced":
                // 平衡构建: 总星数>=12
                Log.d(TAG, "创建平衡构建过滤器, 阈值=12.0");
                return new SLFilter(target, 12.0);

            case "red_focused":
                // 红因子优先: 3星红 + 2星蓝
                Log.d(TAG, "创建红因子优先过滤器, 阈值=18.0");
                return new SLFilter(target, 18.0);

            case "lenient":
                // 宽松策略
                Log.d(TAG, "创建宽松过滤器, 阈值=8.0");
                return new SLFilter(target, 8.0);

            case "very_strict":
                // 极严格: 3星蓝 + 3星红
                Log.d(TAG, "创建极严格过滤器, 阈值=36.0");
                return new SLFilter(target, 36.0);

            case "min_max":
                // 追求极品: 3星蓝*2 + 3星红
                Log.d(TAG, "创建极品过滤器, 阈值=28.0");
                return new SLFilter(target, 28.0);

            case "default":
            default:
                Log.d(TAG, "创建默认过滤器, 阈值=15.0");
                return new SLFilter(target, 15.0);
        }
    }

    /**
     * PT优化: 将PT转化为最高效的技能组合
     *
     * @param state           当前游戏状态
     * @param availableSkills 可购买的技能列表
     * @param ptBudget        PT预算
     * @return 优化结果
     */
    public static PtOptimizationResult optimizePtUsage(GameState state,
                                                        List<String> availableSkills, int ptBudget) {
        PtOptimizationResult result = new PtOptimizationResult();

        // 边界检查
        if (availableSkills == null || availableSkills.isEmpty() || ptBudget <= 0) {
            result.selectedSkills = Collections.emptyList();
            result.totalPtCost = 0;
            result.remainingPt = ptBudget;
            result.totalValue = 0.0;
            result.efficiency = 0.0;
            return result;
        }

        // 使用SkillEvaluator选择最优技能
        List<SkillEvaluator.SkillEvalResult> skills =
                SkillEvaluator.selectOptimalSkills(state, availableSkills, ptBudget);

        int totalCost = 0;
        double totalValue = 0.0;

        if (skills != null) {
            for (SkillEvaluator.SkillEvalResult s : skills) {
                if (s == null || s.skillName == null) continue;

                SkillEvaluator.SkillData data = SkillEvaluator.getSkillData(s.skillName);
                if (data != null) {
                    totalCost += data.ptCost;
                    totalValue += s.adjustedTrigger * data.impact;
                }
            }
        }

        result.selectedSkills = skills != null ? skills : Collections.emptyList();
        result.totalPtCost = totalCost;
        result.remainingPt = ptBudget - totalCost;
        result.totalValue = totalValue;
        result.efficiency = totalCost > 0 ? totalValue / totalCost : 0.0;

        Log.i(TAG, "PT优化完成: " + result.toString());

        return result;
    }

    /**
     * 计算因子组合的总星数
     */
    public static int calcTotalStars(List<Factor> factors) {
        if (factors == null || factors.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Factor f : factors) {
            if (f != null) {
                total += f.stars;
            }
        }
        return total;
    }

    /**
     * 按类型统计因子
     */
    public static Map<String, Integer> countFactorsByType(List<Factor> factors) {
        Map<String, Integer> counts = new HashMap<>();
        if (factors == null) {
            return counts;
        }

        counts.put("blue", 0);
        counts.put("red", 0);
        counts.put("green", 0);
        counts.put("white", 0);

        for (Factor f : factors) {
            if (f != null && f.type != null && counts.containsKey(f.type)) {
                counts.put(f.type, counts.get(f.type) + 1);
            }
        }

        return counts;
    }

    /**
     * 生成因子组合的报告字符串
     */
    public static String generateFactorReport(List<Factor> factors, TargetBuild target) {
        if (factors == null || factors.isEmpty()) {
            return "因子列表为空";
        }

        double score = evaluateInheritance(factors, target);
        int totalStars = calcTotalStars(factors);
        Map<String, Integer> typeCounts = countFactorsByType(factors);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 因子评估报告 ===\n");
        sb.append("综合得分: ").append(String.format("%.1f", score)).append("\n");
        sb.append("总星数: ").append(totalStars).append("\n");
        sb.append("因子分布: ");
        sb.append("蓝=").append(typeCounts.getOrDefault("blue", 0)).append(" ");
        sb.append("红=").append(typeCounts.getOrDefault("red", 0)).append(" ");
        sb.append("绿=").append(typeCounts.getOrDefault("green", 0)).append(" ");
        sb.append("白=").append(typeCounts.getOrDefault("white", 0)).append("\n");

        sb.append("因子详情:\n");
        for (int i = 0; i < factors.size(); i++) {
            Factor f = factors.get(i);
            if (f == null) continue;
            boolean matches = matchesBuild(f, target);
            sb.append("  ").append(i + 1).append(". ").append(f.toString());
            if (matches) {
                sb.append(" [匹配构建]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
