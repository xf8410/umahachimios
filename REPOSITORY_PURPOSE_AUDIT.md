# 仓库用途审计（新增，不覆盖旧 README）

## 实际用途

这是一个 Android/Java 工程，目标是从本机 hlpatch HTTP 服务读取状态并提供独立的决策服务。名称含 “SO插件”，但当前目录证据是 APK/Android 工程，不能称为本仓库编译原生 SO。

## 目录

- `app/src/main/java/`：Java 实现。
- `app/src/main/AndroidManifest.xml`：Android 组件声明。
- 根 Gradle 文件：Android 构建入口。

## 关联性

预期链路为 hlpatch（运行时数据）→ 本工程（决策）→ uma-juece（展示）。这属于架构目标；是否已稳定联调必须由构建、端口和实机响应证据确认。

## 状态

- **实际**：Android 工程和 Java 源码目录存在。
- **待核验**：README 所列 18766 全部端点、13 剧本逻辑、种子预测、SL 与因子优化是否完整可用。
- **猜测/误判纠正**：“纯Java SO插件”表述不准确；更安全的定位是 Android Java 决策服务实验。
- **未完成**：没有仅凭目录即可证明的端到端验收结论。

旧 README 保留为历史设计说明。