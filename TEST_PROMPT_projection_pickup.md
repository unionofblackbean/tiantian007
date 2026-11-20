# 测试任务：圈投影取货（取货端 0.5.0 / 机器人端 0.5.6）游戏内验收

你在一台 Windows 11 机器上工作，工作目录 `C:\Users\xd\Desktop\autoget`。这里有两个 Fabric Mod 项目（不是 git 仓库，改文件前先备份或记下改动）：

- `superwy-requester`：玩家取货端，版本 0.5.0。新功能入口在 Litematica 区域编辑器（按 M）右上角的「机器人取货」按钮。相关源码：`LitematicaAreaHook`、`AreaMaterials`、`ProjectionOrderScreen`、`ParcelPlan`、`ParcelTooltip`、`ProjectionBridge`。
- `superwy-fabric`：仓库机器人端，版本 0.5.6。圈投影订单（parcels 标记）的装盒交付逻辑在 `core/PackingPolicy`、`game/BatchInbox`、`game/OrderEngine`。

环境：Minecraft 26.1.2，JDK 在 `C:\Program Files\Java\jdk-25.0.2`。每条 Gradle 命令前都要设置 `$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'`。

**已完成**：两端 `.\gradlew.bat test build` 都通过（取货端 50 项、机器人端 171 项 JUnit，0 失败）。jar 已生成：
- `superwy-requester\build\libs\superwy-requester-0.5.0.jar`
- `superwy-fabric\build\libs\superwy-fabric-0.5.6.jar`

**还没做**：任何游戏内测试。你的任务就是做这部分。

## 规则

- 只在本地隔离世界或 Fabric Client GameTest 里测试。**不要连接任何真实服务器**，不要碰 FRP 配置、私有打包脚本（`tools/package_private.py`）、`PRIVATE_MESSAGES.md` 或任何凭据。
- 每次运行都把完整输出重定向到项目目录下的日志文件，命名用 `projection-*.log`，例如 `.\gradlew.bat runClientGameTest --console=plain *> projection-regression.log`。
- 产品代码（`src/main`）里发现 bug 时：先写一个能稳定复现的测试，再做最小修复，然后重跑相关测试和完整 `test build`。每个修复都要在报告里写清原因和改动。不要为了让测试通过而放宽断言。
- 不要改版本号，不要删除 `build/libs` 里的旧 jar 和已有日志。

## 第 1 步：旧功能回归（证明普通订单没坏）

1. 取货端：在 `superwy-requester` 下运行 `.\gradlew.bat test build runClientGameTest`。
2. 机器人端：在 `superwy-fabric` 下，按下面每个开关分别运行一次，都要带上新的取货端 jar：
   ```
   .\gradlew.bat runClientGameTest -P<开关> -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.5.0.jar
   ```
   开关依次为：`batchPickupTest`、`batchSkipTest`、`packingTest`、`parcelCapTest`、`taskQueueTest`、`deliveryQueueTest`、`directDeliveryTest`。另外不带开关跑一次默认的 `runClientGameTest`。
3. 每次都记录：成功/失败、耗时、日志里的 `SUPERWY_*_OK` 标记（参考 `VERIFICATION.md` 里历次记录的写法）。

## 第 2 步：给新功能补自动化 GameTest

现在还没有任何覆盖圈投影订单的 GameTest。参照已有写法补上：机器人端在 `src/gametest/java/dev/superwy/WarehouseGameTest.java` 里，通过 `build.gradle` 的 `-P开关 → -Dsuperwy.xxx=true` 选择场景；取货端在 `src/gametest/java/dev/superwy/requester/RequesterGameTest.java`。新增一个开关 `projectionParcelsTest`，在机器人端 `build.gradle` 的 `clientGameTest` 运行配置里照现有格式注册，日志标记用 `SUPERWY_PROJECTION_PARCELS_OK`。

场景至少覆盖下面这些，每条都要在游戏内实际核对物品数量，不能只看日志：

| # | 场景 | 期望 |
|---|---|---|
| A | 圈投影订单，材料约 3 盒的量，仓库全部有货 | 收货假人 `sf` 收到多个潜影盒；除最后一盒外每盒 27 格都装满；盒内物品和数量与 `ParcelPlan` 在同样输入下算出的预估完全一致（包括按名称排序、同类相邻） |
| B | 中途交付（机器人背包满了先送一趟） | 没装满的那盒留在机器人身上继续装，最终每盒内容仍与预估一致 |
| C | 清单里有潜影盒本身（例如 2 个空潜影盒） | 不放进盒子，在其他材料装完后散装交付 |
| D | `sf` 装不下 | 切换到 `sf_1`、`sf_2`，与预估的假人数量一致 |
| E | 16 格堆叠（末影珍珠）和不可堆叠物品（例如钻石剑） | 按 16 / 1 计算格子，与预估一致 |
| F | 部分材料缺货 | 订单不卡死；实际结果和预估不同是允许的，但聊天反馈要说清缺了什么、交付了什么 |
| G | 用普通单项订单和普通批量订单各下一单 | 行为和 0.5.5 一样，**不**强制装盒 |
| H | 协议兼容：模拟旧机器人（不认识 parcels 标记）收到圈投影订单 | 旧机器人拒绝；取货端提示「请把机器人更新到 0.5.6」 |
| I | 超限：超过 128 种或合计超过 100 万件 | 取货端在发送前拦下，并提示缩小选区 |

取货端 GameTest 至少覆盖：
- 在测试世界里放一个小的 Litematica 投影并圈出区域，打开区域编辑器，找到「机器人取货」按钮。
- 材料清单的「需要 / 背包 / 缺少」三列。背包要算主背包、快捷栏、副手，以及身上潜影盒里的物品，每一处各放一种测试物品来验证。
- 切换「已放置方块：不扣除」和「全部数量」后数量会变化；点一行可以排除该材料。
- 「确认清单」后的盒子数和假人数预估；悬停提示要有 9×3 排布和物品汇总。用截图或读取界面状态确认。
- 「下发任务」真的把订单发了出去。如果能做成双端联测就更好：带 `-PrequesterJar` 跑机器人端，让整条链路走一遍。

## 第 3 步：可视与性能检查

用 `.\gradlew.bat runClient` 启动取货端（带 Litematica、MaLiLib），在单人创造世界里：
- 截图确认按钮位置不和 Litematica 原有按钮重叠。分别在 GUI 缩放 2、3、4 和窗口宽度 854 下检查。
- 圈一个大选区（约 128×128×64，方块种类多），记录从点击到清单出现的耗时，并确认游戏不卡顿（FPS 不掉到个位数，没有长时间冻结）。
- 悬停预览不出屏幕、文字不被截断。

截图存到 `superwy-requester\verification\projection\`。

## 第 4 步：交付

1. 所有改动后，两端都重新跑一次 `.\gradlew.bat test build`，确认全部通过。
2. 在两个项目 `VERIFICATION.md` 顶部「0.5.6 / 0.5.0 圈投影取货」这一节，把「待在 Windows 上完成」改成实际结果，按原有格式写：命令、日志文件名、标记、测试数、覆盖了什么、没覆盖什么。
3. 最后给出一份简短报告：
   - 每个场景（第 1 步每个开关、第 2 步 A–I、取货端界面各项、第 3 步）的通过、失败或未测，以及证据（日志名、截图路径）。
   - 发现的 bug 和修复（文件:行号、原因）；没修的问题写清复现步骤。
   - 明确列出还要人工做的事：真实服务器验收、FRP 私有 0.5.6 jar 重新打包（这两项不归你做）。
