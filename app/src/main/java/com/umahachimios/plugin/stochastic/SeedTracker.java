package com.umahachimios.plugin.stochastic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 种子追踪器 — 追踪赛马娘育成中的伪随机序列
 *
 * 核心原理：
 * 1. 关键随机结果在特定检查点被确定
 * 2. 一旦种子确定，随机序列固定
 * 3. 通过识别种子边界，可以预览和过滤结果
 *
 * 应用场景：
 * - 育成前预测训练成功/失败序列
 * - 识别有利的连续成功窗口
 * - 事件排程预测，提前规划支援卡事件
 */
public class SeedTracker {
    private static final String TAG = "SeedTracker";

    // 基础训练成功率，实际受干劲和设施等级影响（范围约75%~95%）
    private static final double BASE_SUCCESS_RATE = 0.90;

    // 每回合最大训练动作数
    private static final int ACTIONS_PER_TURN = 3;

    // 默认预测回合数（标准育成72回合）
    private static final int DEFAULT_PREDICT_TURNS = 72;

    /**
     * 种子检查点定义
     * 每个检查点对应一个关键随机决策边界
     */
    public enum Checkpoint {
        NURTURE_START,       // 开始育成：决定因子继承/初始支援卡
        TURN_1_START,        // 第1回合：决定训练成功/失败序列
        RACE_ENTRY,          // 每次参赛：决定比赛结果
        EVENT_TRIGGER,       // 事件触发：决定随机事件
        SKILL_SHOP_REFRESH   // 技能店刷新
    }

    // 当前已知的种子状态
    private long currentSeed = 0L;
    private final Map<Checkpoint, Long> checkpointSeeds = new HashMap<>();
    private int turnCount = 0;

    // 训练成功序列预测
    private final List<boolean[]> predictedTrainingSequence = new ArrayList<>();

    /**
     * 记录种子检查点
     *
     * @param cp   检查点类型，不能为null
     * @param seed 该检查点对应的种子值
     * @throws IllegalArgumentException 如果cp为null
     */
    public void recordCheckpoint(Checkpoint cp, long seed) {
        if (cp == null) {
            throw new IllegalArgumentException("Checkpoint不能为null");
        }
        checkpointSeeds.put(cp, seed);
        if (cp == Checkpoint.TURN_1_START) {
            currentSeed = seed;
            predictTrainingSequence(seed, DEFAULT_PREDICT_TURNS);
        }
    }

    /**
     * 预测训练成功序列
     * 给定种子，预测每回合的训练成功/失败情况
     *
     * @param seed     随机种子
     * @param numTurns 预测回合数，必须>0
     * @throws IllegalArgumentException 如果numTurns<=0
     */
    public void predictTrainingSequence(long seed, int numTurns) {
        if (numTurns <= 0) {
            throw new IllegalArgumentException("预测回合数必须大于0");
        }
        predictedTrainingSequence.clear();
        Random rng = new Random(seed);

        for (int turn = 0; turn < numTurns; turn++) {
            // 每回合最多3次训练动作（速/耐/力/根/智 五维训练）
            boolean[] actions = new boolean[ACTIONS_PER_TURN];
            for (int action = 0; action < ACTIONS_PER_TURN; action++) {
                double roll = rng.nextDouble();
                // 基础成功率约90%，受干劲/设施等级影响
                // roll < rate 表示成功，否则失败（掉心情/训练效果下降）
                actions[action] = roll < BASE_SUCCESS_RATE;
            }
            predictedTrainingSequence.add(actions);
        }
    }

    /**
     * 查找连续成功窗口
     * 用于定位一段连续回合内所有训练动作都成功的区间
     *
     * @param windowSize 窗口大小（回合数），必须>0
     * @return 所有满足条件的窗口[startTurn, endTurn]列表
     * @throws IllegalArgumentException 如果windowSize<=0
     */
    public List<int[]> findSuccessWindows(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("窗口大小必须大于0");
        }
        List<int[]> windows = new ArrayList<>();
        if (predictedTrainingSequence.isEmpty()) {
            return windows;
        }
        if (windowSize > predictedTrainingSequence.size()) {
            // 窗口比序列还大，不可能找到
            return windows;
        }

        for (int i = 0; i <= predictedTrainingSequence.size() - windowSize; i++) {
            boolean allSuccess = true;
            for (int j = 0; j < windowSize; j++) {
                boolean[] actions = predictedTrainingSequence.get(i + j);
                for (boolean success : actions) {
                    if (!success) {
                        allSuccess = false;
                        break;
                    }
                }
                if (!allSuccess) {
                    break;
                }
            }
            if (allSuccess) {
                windows.add(new int[]{i, i + windowSize - 1});
            }
        }
        return windows;
    }

    /**
     * 预测事件排程
     * 基于当前种子预测指定回合范围内可能触发的事件
     *
     * @param stage     育成阶段("junior"/"classic"/"senior")，不能为null
     * @param startTurn 起始回合，必须>=0
     * @param endTurn   结束回合，必须>=startTurn
     * @return 预测的事件排程列表
     * @throws IllegalArgumentException 如果参数非法
     */
    public List<EventPrediction> predictEventSchedule(String stage, int startTurn, int endTurn) {
        if (stage == null || stage.isEmpty()) {
            throw new IllegalArgumentException("stage不能为null或空");
        }
        if (startTurn < 0) {
            throw new IllegalArgumentException("startTurn必须>=0");
        }
        if (endTurn < startTurn) {
            throw new IllegalArgumentException("endTurn必须>=startTurn");
        }

        List<EventPrediction> schedule = new ArrayList<>();
        // 使用当前种子+起始回合偏移作为预测种子
        // 这样保证同一startTurn的预测结果稳定
        Random rng = new Random(currentSeed + startTurn);

        Map<String, Double> eventPools = getEventPool(stage);
        if (eventPools.isEmpty()) {
            return schedule;
        }

        // 预跳过startTurn之前的随机数，保持序列一致性
        for (int i = 0; i < startTurn; i++) {
            for (int j = 0; j < eventPools.size(); j++) {
                rng.nextDouble();
            }
        }

        for (int turn = startTurn; turn <= endTurn; turn++) {
            List<String> events = new ArrayList<>();
            for (Map.Entry<String, Double> entry : eventPools.entrySet()) {
                if (rng.nextDouble() < entry.getValue()) {
                    events.add(entry.getKey());
                }
            }
            if (!events.isEmpty()) {
                schedule.add(new EventPrediction(turn, events));
            }
        }
        return schedule;
    }

    /**
     * 获取指定阶段的事件池
     *
     * @param stage 育成阶段
     * @return 事件类型到触发概率的映射
     */
    private Map<String, Double> getEventPool(String stage) {
        Map<String, Double> pool = new HashMap<>();
        switch (stage) {
            case "junior":
                // 出道前期：支援卡事件概率较高，比赛事件较低
                pool.put("support_card_event", 0.30);
                pool.put("random_event", 0.15);
                pool.put("race_event", 0.10);
                break;
            case "classic":
                // 经典级：支援卡事件略降，比赛事件上升
                pool.put("support_card_event", 0.25);
                pool.put("random_event", 0.12);
                pool.put("race_event", 0.15);
                break;
            case "senior":
                //  senior级：比赛事件概率最高
                pool.put("support_card_event", 0.20);
                pool.put("random_event", 0.10);
                pool.put("race_event", 0.20);
                break;
            default:
                // 未知阶段使用默认概率
                pool.put("support_card_event", 0.25);
                pool.put("random_event", 0.12);
                break;
        }
        return pool;
    }

    /**
     * 获取指定检查点的种子值
     *
     * @param cp 检查点类型
     * @return 种子值，如果未记录则返回null
     */
    public Long getSeed(Checkpoint cp) {
        if (cp == null) {
            return null;
        }
        return checkpointSeeds.get(cp);
    }

    /**
     * 获取所有已记录的检查点种子
     *
     * @return 检查点到种子值的映射副本
     */
    public Map<Checkpoint, Long> getAllSeeds() {
        return new HashMap<>(checkpointSeeds);
    }

    /**
     * 获取预测的训练成功序列
     *
     * @return 序列的防御性副本
     */
    public List<boolean[]> getPredictedSequence() {
        List<boolean[]> copy = new ArrayList<>(predictedTrainingSequence.size());
        for (boolean[] actions : predictedTrainingSequence) {
            copy.add(actions.clone());
        }
        return copy;
    }

    /**
     * 获取预测序列的长度
     *
     * @return 已预测的回合数
     */
    public int getPredictedTurnCount() {
        return predictedTrainingSequence.size();
    }

    /**
     * 获取当前回合数
     *
     * @return 当前回合
     */
    public int getTurnCount() {
        return turnCount;
    }

    /**
     * 设置当前回合数
     *
     * @param turnCount 回合数
     */
    public void setTurnCount(int turnCount) {
        if (turnCount < 0) {
            throw new IllegalArgumentException("回合数不能为负数");
        }
        this.turnCount = turnCount;
    }

    /**
     * 重置所有状态
     */
    public void reset() {
        currentSeed = 0L;
        checkpointSeeds.clear();
        turnCount = 0;
        predictedTrainingSequence.clear();
    }

    // ==================== 数据类 ====================

    /**
     * 事件预测结果
     */
    public static class EventPrediction {
        public int turn;
        public List<String> eventTypes;

        public EventPrediction(int turn, List<String> eventTypes) {
            if (eventTypes == null) {
                throw new IllegalArgumentException("eventTypes不能为null");
            }
            this.turn = turn;
            this.eventTypes = new ArrayList<>(eventTypes);
        }

        @Override
        public String toString() {
            return "Turn " + turn + ": " + eventTypes;
        }
    }
}
