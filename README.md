# Umahachimios — 赛马娘哈基米AI SO插件

基于哈基米(Hachimi)框架的赛马娘育成AI辅助SO插件，纯Java实现，通过HTTP从hlpatch获取游戏内存数据，运行AI决策算法后对外提供推荐API。

## 架构

```
赛马娘游戏进程 ←→ hlpatch SO插件(18765) ← HTTP → umahachimios AI插件(18766) ← HTTP → uma-juece浮窗App
                              ↑                                              ↑
                         内存数据读取                                    AI决策推荐
```

## 功能模块

| 模块 | 文件 | 说明 |
|------|------|------|
| 决策引擎 | `DecisionEngine.java` | 主决策：训练/休息/外出/比赛/技能 |
| 训练评估 | `TrainingEvaluator.java` | 5种训练评分，13剧本专属逻辑 |
| 比赛评估 | `RaceEvaluator.java` | G1比赛胜率估算与参赛决策 |
| 技能评估 | `SkillEvaluator.java` | 技能性价比与购买优先级 |
| 因子优化 | `FactorOptimizer.java` | 因子继承评分/SL过滤/PT优化 |
| 种子追踪 | `SeedTracker.java` | 伪随机序列预测 |
| SL过滤 | `SLFilter.java` | Save/Load种子筛选 |

## HTTP端点 (端口18766)

| 端点 | 方法 | 说明 |
|------|------|------|
| `/recommend` | GET | 获取当前最佳行动推荐 |
| `/evaluate/train` | GET | 获取5种训练评分详情 |
| `/evaluate/race` | GET | 获取比赛评估 |
| `/evaluate/skill` | GET | 获取技能评估 |
| `/sl/check` | GET | SL过滤检查 |
| `/sl/status` | GET | SL过滤状态 |
| `/factor/score` | GET | 因子评分 |
| `/health` | GET | 健康检查 |
| `/config` | POST | 更新配置(剧本/策略) |

## 构建

```bash
./gradlew :app:assembleDebug
```

## 安装

1. 将编译出的APK通过哈基米框架加载
2. 插件自动连接hlpatch(18765)并启动AI服务(18766)
3. 浏览器访问 `http://127.0.0.1:18766/health` 确认运行

## 关联项目

- [hlpatch](https://github.com/xf8410/hlpatch) — 内存读取SO插件(Rust)
- [uma-juece](https://github.com/xf8410/uma-juece) — 浮窗决策App(Java)
- [uma-data](https://github.com/xf8410/uma-data) — 事件数据(JSON)
