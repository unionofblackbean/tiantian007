# 0.2.0 共享控制验证

2026-10-25，Minecraft 26.1.2 / JDK 25.0.2 / Loader 0.19.5 / Fabric API 0.155.3+26.1.2 / Baritone API 1.18.0。

`gradlew build runClientGameTest` 成功，日志 `shared-controls-gametest.log`。独立测试没有加载 SuperWY。

另在 SuperWY 0.3.4 中同时加载本补丁，完整联合游戏回归通过。机器人项目 `shared-controls-combined-gametest.log` 验证长按方向键抵墙 32 秒仍保留任务，松开后继续原目标，并完成安全箱整理、快捷栏保留、取货装箱和假人交付。

- 八组真实路径：前台/后台 × 原 freeLook 开关 × 正常/提前清除旋转目标，均实际经过两个转角并到达目标。运行中持续设置 yaw=47、pitch=17，每个游戏 tick 后验证镜头未被路径改写。
- 手动 W 沿镜头方向实际移动，保持原目标；松开后到达原目标，标记 `BARITONE_FIX_SHARED_INPUT_OK`。使用正式混淆版 Baritone，验证输入类型识别不依赖未混淆类名。
- 实际跳上一格平台；运行中 pause/cancel 后自由视角保持。标记 `BARITONE_FIX_JUMP_STOP_OK`。
- 所有故障注入和测试入口仅在 gametest 源集，发布 jar 不包含。

未连接远程服务器；旧版 67 Mod 的兼容性结果仅属下列历史记录，本版未重新完整覆盖该组合。

以下为 0.1.0 历史记录，其中“强制镜头转向”的行为已被本版共享控制取代。

---

# 0.1.0 验证记录

日期：2026-10-25（Asia/Hong_Kong）。Minecraft 26.1.2，Java 25.0.2，Fabric Loader 0.19.5，Fabric API 0.155.3+26.1.2，Baritone API 1.18.0。

## 核对与复现

- 用户的 `26.1.2 Fabric` 实例中 Baritone API 1.18.0 的 SHA256 为 `beec850e73591ab1dc81cb359d1d224ef3230a35d21dc6e72f8e8d287e7b79d2`，与工作区测试依赖一致。
- 另一实例的 `baritone-standalone-fabric-1.20.0 (2).jar` 元数据声明 Minecraft `~26.3`；没有修改其版本声明，也没有将它用于 26.1.2 回归。
- 原实例配置的 Baritone 设置包括 `antiCheatCompatibility false`。基础直接命令测试覆盖 freeLook、antiCheatCompatibility 开关及前台/后台的八种组合，在没有 SuperWY 转向接管时通过。
- 将原实例的 67 个其他 Mod 复制到隔离测试依赖目录后，通过原生 `goto` 复现了卡住：`pathing=true`、`forward=true`、yaw 与查询得到的移动目标角度均停留在 -180 度，位置从 `(0.5,70,-9.5)` 只移动到墙边 `(0.5,70,-9.7)`。没有通过 SuperWY Actions.go 发起寻路。
- 在同一兼容性环境加入本补丁后，上述八种组合全部实际通过两个转角并到达目标，镜头可见转向，整轮 Gradle 游戏测试成功结束。记录位于机器人项目的 `direct-baritone-patched.log`。

## 独立补丁测试

- `gradlew build runClientGameTest` 完成真实客户端游戏测试。独立项目没有加载 SuperWY 机器人端或玩家取货端，使用 Baritone 自己的 `goto` 命令。
- 八组实走断言：前台/后台 × freeLook 开关 × 正常/提前清除旋转目标。三格高石墙限制绕路，断言实际经过东向和南向通道、到达目标，以及玩家镜头发生转向。标记 `BARITONE_FIX_CORRIDOR_OK`。
- 提前清除由只存在于 gametest 源集的 Mixin 调用 PlayerUpdate POST 复现；发布 jar 不包含此 Mixin、测试入口或其他故障注入。
- 实际跳上一格高平台，验证离地和落脚高度。对正在执行的另一条路径执行 pause，再 cancel，分别设置玩家视角并验证不被补丁覆盖。标记 `BARITONE_FIX_JUMP_STOP_OK`。
- 在独立补丁项目加入复制的 67 个 Mod 后，上述八组转弯、跳跃、运行中暂停及取消断言也全部通过。该轮在断言完成后的集成服务器退出阶段卡住；线程转储显示 Fabric 游戏测试的服务端/客户端 Phaser 互相等待，已终止该隔离测试进程。该轮只能作为行为断言通过记录，不能声称整个退出流程通过。

## 发布范围

仅提供独立客户端补丁。不修改用户的游戏实例、配置、存档或服务器，不重新打包 Baritone。发布 jar 不包含 Fabric API、Baritone、其他第三方 Mod 或测试用例。

此测试证明补丁可修复已复现的转向执行故障；不把基础环境正常解释为用户现象不存在，也不据此断言所有 Mod 组合中的上游问题都已修复。补丁使用有效期受限的当前路径旋转，不推测未知容器或服务器状态。
