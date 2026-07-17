package com.umahachimios.plugin.stochastic;

import com.umahachimios.plugin.engine.FactorOptimizer;
import com.umahachimios.plugin.engine.FactorOptimizer.Factor;
import com.umahachimios.plugin.engine.FactorOptimizer.TargetBuild;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SL (Save/Load) 过滤引擎
 *
 * 通过快速重启评估初期随机结果，筛选有利种子后再开始正式育成。
 * 核心思想：利用游戏开局时的因子继承是确定性的特点，反复重开直到
 * 获得满意的初始因子组合，从而提高最终育成质量。
 *
 * 使用流程：
 * 1. 开始育成 → 查看因子继承
 * 2. 调用shouldAccept评估
 * 3. 不满足 → 退出重开
 * 4. 满足 → 停止过滤，开始AI辅助育成
 */
public class SLFilter {
    private static final String TAG = "SLFilter";

    // 预设策略常量
    /** 速度流严格策略：要求3星蓝因子匹配 */
    public static final String STRATEGY_SPEED_STRICT = "speed_build_strict";
    /** 平衡策略：总星数>=10即可 */
    public static final String STRATEGY_BALANCED = "balanced";
    /** 红因子聚焦策略：要求3星红+2星蓝 */
    public static final String STRATEGY_RED_FOCUSED = "red_focused";
    /** 宽松策略：低门槛快速开始 */
    public static final String STRATEGY_LENIENT = "lenient";

    // 目标育成配置
    private final TargetBuild targetBuild;

    // 评分阈值
    private final double threshold;

    // 当前策略名称
    private final String strategy;

    // 统计计数
    private int totalAttempts = 0;
    private int acceptedCount = 0;
    private double bestScore = 0.0;
    private long startTime = 0L;

    // 尝试历史记录（用于调试和统计）
    private final List<AttemptRecord> attemptHistory = new ArrayList<>();

    // 是否处于活跃过滤状态
    private boolean isActive = true;

    /**
     * 创建SL过滤器
     *
     * @param target   目标育成配置，不能为null
     * @param strategy 策略名称，不能为null或空
     * @throws IllegalArgumentException 如果参数非法
     */
    public SLFilter(TargetBuild target, String strategy) {
        if (target == null) {
            throw new IllegalArgumentException("TargetBuild不能为null");
        }
        if (strategy == null || strategy.isEmpty()) {
            throw new IllegalArgumentException("strategy不能为null或空");
        }
        this.targetBuild = target;
        this.strategy = strategy;
        this.threshold = getThresholdForStrategy(strategy);
        this.startTime = System.currentTimeMillis();
    }

    /**
     * 根据策略名称获取评分阈值
     *
     * @param strategy 策略名称
     * @return 对应的评分阈值
     */
    private double getThresholdForStrategy(String strategy) {
        switch (strategy) {
            case STRATEGY_SPEED_STRICT:
                return 20.0;  // 必须有3星蓝因子匹配
            case STRATEGY_BALANCED:
                return 12.0;  // 总星数>=10
            case STRATEGY_RED_FOCUSED:
                return 18.0;  // 3星红 + 2星蓝
            case STRATEGY_LENIENT:
                return 8.0;   // 宽松
            default:
                return 15.0;  // 默认中等严格
        }
    }

    /**
     * 评估当前因子是否可接受
     *
     * @param factors 当前继承的因子列表，null或空列表会评分为0
     * @return true=接受（满足阈值），false=拒绝（应退出重开）
     */
    public boolean shouldAccept(List<Factor> factors) {
        totalAttempts++;

        // 防御性处理：null或空列表评分为0
        double score;
        if (factors == null || factors.isEmpty()) {
            score = 0.0;
        } else {
            score = FactorOptimizer.evaluateInheritance(factors, targetBuild);
        }

        // 记录本次尝试
        AttemptRecord record = new AttemptRecord();
        record.attemptNumber = totalAttempts;
        record.score = score;
        record.factors = factors != null ? new ArrayList<>(factors) : Collections.emptyList();
        record.timestamp = System.currentTimeMillis();
        record.accepted = score >= threshold;
        attemptHistory.add(record);

        // 更新最佳分数
        if (score > bestScore) {
            bestScore = score;
        }

        boolean accepted = score >= threshold;
        if (accepted) {
            acceptedCount++;
            isActive = false; // 接受后自动停止过滤
        }
        return accepted;
    }

    /**
     * 仅评估分数，不更新统计状态
     * 用于预览当前因子的分数而不影响过滤计数
     *
     * @param factors 因子列表
     * @return 评分结果
     */
    public double previewScore(List<Factor> factors) {
        if (factors == null || factors.isEmpty()) {
            return 0.0;
        }
        return FactorOptimizer.evaluateInheritance(factors, targetBuild);
    }

    /**
     * 获取当前过滤状态摘要
     *
     * @return 过滤状态对象
     */
    public FilterStatus getStatus() {
        FilterStatus status = new FilterStatus();
        status.totalAttempts = totalAttempts;
        status.accepted = acceptedCount;
        status.bestScore = bestScore;
        status.threshold = threshold;
        status.strategy = strategy;
        status.acceptanceRate = totalAttempts > 0 ? (double) acceptedCount / totalAttempts : 0.0;
        status.elapsedTimeMs = System.currentTimeMillis() - startTime;
        status.isActive = isActive;

        // 预估剩余尝试次数（基于当前观察到的接受率）
        if (totalAttempts > 0 && acceptedCount == 0) {
            double observedRate = 1.0 / totalAttempts; // 当前观察到的每N次成功1次
            double expectedAttempts = 1.0 / Math.max(observedRate, 0.001);
            status.estimatedRemainingAttempts = (int) Math.ceil(expectedAttempts);
        } else {
            status.estimatedRemainingAttempts = 0;
        }

        return status;
    }

    /**
     * 获取过滤统计JSON字符串
     *
     * @return JSON格式的状态字符串
     */
    public String getStatusJson() {
        FilterStatus s = getStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"attempts\":").append(s.totalAttempts).append(",");
        sb.append("\"accepted\":").append(s.acceptedCount).append(",");
        sb.append("\"best_score\":").append(String.format(java.util.Locale.US, "%.1f", s.bestScore)).append(",");
        sb.append("\"threshold\":").append(s.threshold).append(",");
        sb.append("\"strategy\":\"").append(escapeJson(s.strategy)).append("\",");
        sb.append("\"acceptance_rate\":\"").append(String.format(java.util.Locale.US, "%.1f%%", s.acceptanceRate * 100.0)).append("\",");
        sb.append("\"elapsed_sec\":").append(s.elapsedTimeMs / 1000).append(",");
        sb.append("\"is_active\":").append(s.isActive);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 获取详细历史记录JSON
     *
     * @return 包含所有尝试记录的JSON数组
     */
    public String getHistoryJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < attemptHistory.size(); i++) {
            AttemptRecord r = attemptHistory.get(i);
            sb.append("{");
            sb.append("\"attempt\":").append(r.attemptNumber).append(",");
            sb.append("\"score\":").append(String.format(java.util.Locale.US, "%.1f", r.score)).append(",");
            sb.append("\"accepted\":").append(r.accepted).append(",");
            sb.append("\"factor_count\":").append(r.factors.size());
            sb.append("}");
            if (i < attemptHistory.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 重置过滤器（开始新一轮过滤）
     */
    public void reset() {
        totalAttempts = 0;
        acceptedCount = 0;
        bestScore = 0.0;
        startTime = System.currentTimeMillis();
        attemptHistory.clear();
        isActive = true;
    }

    /**
     * 手动停止过滤
     */
    public void stop() {
        isActive = false;
    }

    /**
     * 获取当前策略名称
     *
     * @return 策略名称
     */
    public String getStrategy() {
        return strategy;
    }

    /**
     * 获取评分阈值
     *
     * @return 当前阈值
     */
    public double getThreshold() {
        return threshold;
    }

    /**
     * 获取目标育成配置
     *
     * @return TargetBuild对象
     */
    public TargetBuild getTargetBuild() {
        return targetBuild;
    }

    /**
     * 获取尝试历史记录数量
     *
     * @return 历史记录条数
     */
    public int getHistorySize() {
        return attemptHistory.size();
    }

    /**
     * 获取历史记录（防御性副本）
     *
     * @return 尝试历史列表
     */
    public List<AttemptRecord> getAttemptHistory() {
        List<AttemptRecord> copy = new ArrayList<>();
        for (AttemptRecord r : attemptHistory) {
            AttemptRecord c = new AttemptRecord();
            c.attemptNumber = r.attemptNumber;
            c.score = r.score;
            c.factors = new ArrayList<>(r.factors);
            c.timestamp = r.timestamp;
            c.accepted = r.accepted;
            copy.add(c);
        }
        return copy;
    }

    /**
     * 简单的JSON字符串转义
     *
     * @param input 原始字符串
     * @return 转义后的字符串
     */
    private String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== 数据类 ====================

    /**
     * 单次尝试记录
     */
    public static class AttemptRecord {
        public int attemptNumber;
        public double score;
        public List<Factor> factors = new ArrayList<>();
        public long timestamp;
        public boolean accepted;
    }

    /**
     * 过滤状态汇总
     */
    public static class FilterStatus {
        public int totalAttempts;
        public int accepted;
        public double bestScore;
        public double threshold;
        public String strategy;
        public double acceptanceRate;
        public long elapsedTimeMs;
        public int estimatedRemainingAttempts;
        public boolean isActive;
    }
}
