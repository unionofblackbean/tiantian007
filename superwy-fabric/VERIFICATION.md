# 0.5.6 批量取货失败收尾（机器人端，取货端无需更新）

2026-10-29，Windows，Minecraft 26.1.2 / JDK 25.0.2，本地隔离世界。版本号仍为 0.5.6。

- 批量订单（含圈投影）失败后自动收尾：取盒点空盒用完时，已装好的盒子交给收货假人，剩余散货存回安全箱（`returnEntry` 清理箱）；其他原因失败时，剩余散货继续装盒，与已有盒子一并交付。公屏输出失败原因、交付件数、交付的盒数和假人、存箱件数及未交付清单。取消、超时、换世界、手动接管、光标非空或交付无法核实时不收尾，保持原样待核对。
- 打包订单开单前检查取盒点：没有空盒时直接失败、不取货；空盒少于预计时公屏提示后继续。
- `test build` 通过，机器人端 175 项 JUnit（新增 `BatchFailureReportTest` 4 项）全部通过，日志 `salvage-build.log`。
- `runClientGameTest -PbatchSalvageTest -PrequesterJar=…0.5.0.jar` 通过，日志 `salvage-gametest.log`，标记 `SUPERWY_BATCH_SALVAGE_OK`：S0 空取盒点开单即失败；S1 只有 1 个空盒，1 盒 1728 石头交给 sf、832 石头存回安全箱；S2 注入其他失败，已有盒子加 15 组散货装成 2 盒交付。均核对假人背包、安全箱、机器人背包和公屏消息。
- 回归（带取货端 0.5.0 jar）全部通过，日志 `salvage-regression-<开关>.log`：`cleanupChestTest`（`SUPERWY_CLEANUP_CHEST_OK`，复核抽成公用函数的清理箱存放）、`projectionParcelsTest`（A–G 及 `SUPERWY_PROJECTION_PARCELS_OK`）、`packingTest`（`SUPERWY_PACKING_OK`）、`parcelCapTest`（`SUPERWY_PARCEL_CAP_OK`）、`batchSkipTest`（`SUPERWY_BATCH_SKIP_OK`）、`batchPickupTest`（`SUPERWY_BATCH_PICKUP_OK`）。
- 私有包 `dist/superwy-fabric-0.5.6-private.jar` 经 `privatePresetCheck` 通过（`private-0.5.6-preset.log`，`SUPERWY_PRIVATE_PRESET_OK`）。

# 0.5.6 / 0.5.0 圈投影取货（Windows 本地验收完成，普通窗口人工性能复测待补）

2026-10-29，Windows，Minecraft 26.1.2 / JDK 25.0.2，仅本地隔离世界。两端最终 `test build` 通过，取货端 50 项、机器人端 171 项 JUnit，失败及错误均为 0。版本号未变。

- 旧功能：取货端 `test build runClientGameTest` 通过（`projection-requester-regression.log`，111 秒）；机器人带取货端 0.5.0 jar 的 batchPickupTest、batchSkipTest、packingTest、parcelCapTest、taskQueueTest、deliveryQueueTest、directDeliveryTest 与默认完整场景全部通过。各场景命令、耗时、日志和 SUPERWY 标记见完整报告。
- 新入口 `runClientGameTest -PprojectionParcelsTest`。机器人带 `-PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.5.0.jar` 的完整日志：`../superwy-fabric/projection-parcels-final.log`，426.8 秒，`SUPERWY_PROJECTION_PARCELS_OK`。真实双端私聊、QuickShulker、GCA 和下线重召背包核对通过：三盒 27/27/7 格与 ParcelPlan 逐槽一致，中途保留未满盒，散装空盒最后交付，73 个空盒在 sf/sf_1/sf_2 中分别为 36/36/1，16/1 堆叠，部分缺货及普通单项/批量不强制装盒。
- 取货端日志：`../superwy-requester/projection-requester-scale-final.log`，`SUPERWY_PROJECTION_REQUESTER_OK`、`SUPERWY_PROJECTION_H_OK`、`SUPERWY_PROJECTION_I_OK`。实际简易区域编辑器按钮、四处库存的需要/背包/缺少三列、已放置扣除、全部数量、行排除、预估、9×3 悬停、真实发包、模拟旧机器人更新提示和两种超限均通过。
- GUI 缩放 2/3/4 逐一断言实际生效，854 宽度通过。128×128×64、16 种实际投影方块统计耗时 366.4 ms，采样最低 FPS=111；扣除已放置的 1 块后计数 1048575。截图及可重载小投影在 `../superwy-requester/verification/projection/`。性能来自真实客户端 GameTest；普通 runClient 已进入本地创造世界并加载真实投影，后续界面操作因用户按 Esc 停止 Computer Use，未另做普通窗口同场 FPS 复测。
- 发现并修复 `OrderEngine.java:574` 的投影交付顺序缺陷：石头 1 + 空盒 2 原本把两个空盒先交付。先补稳定失败的实际背包测试，再最小修复为无后续可装箱材料时先交付最后材料盒。失败日志 `projection-recheck-projectionParcelsTest.log` 保留，修复后小场景与完整场景通过；普通订单路径未改。另修正旧 GameTest 对已移除字段的引用、区域就绪竞态，并让大型投影使用现有 packingTest 等级的等待预算，未放宽物品断言。
- 备份在 `../verification/projection-backup-20261029-192154/`，旧 jar 和日志未删除。没有连接真实服务器或操作 FRP、私有打包脚本、PRIVATE_MESSAGES.md 和真实凭据。未测生产服组合、所有分辨率和非简易编辑器；真实服验收及 FRP 私有 0.5.6 重新打包仍由人工完成。

完整报告：[圈投影验收报告](../verification/projection-test-report.md)。最终构建日志：各项目的 `projection-build-verified.log`。

# 0.5.5 自动补货机换盒重试与实时布局

2026-10-29，Minecraft 26.1.2 / Java 25，本地隔离世界。

- `test build` 通过，168 项 JUnit 全部通过，发布构建结果位于 `build-restock-release/test-results/test`，构建日志 `restock-release.log`。新版本机器人为 0.5.5，玩家取货端无需更新。
- `runClientGameTest -PrestockRetryTest` 通过，最终日志 `restock-retry-final.log`，标记 `SUPERWY_RESTOCK_RETRY_OK`。真实点击从放置的潜影盒拿走 64 个安山岩墙后关闭界面，服务端拆掉旧盒、换入新盒并将货物改到第 17 槽，原第 2 槽放泥土；订单自动核实首批数量，等待至少 1 秒后重开，再取 64 个，收货假人实际持有恰好 128 个安山岩墙，泥土未被拿取。
- 同场在首次点击前关闭界面，触发 `MENU_DESYNC` 后重新排布金锭位置，确认重开间隔不少于 1 秒，按新槽位恰好取出 4 个。另验证缓存中的第 2 槽源盒变成第 20 槽、盒内金锭从 4 个变成 9 个且内层槽位变化后，仍正确取出 3 个；借盒期间第 20 槽被填入泥土，源盒归还到同一容器当前空槽，剩余 6 金锭和 1 铁锭准确保留，没有覆盖占用槽或丢失源盒。
- `runClientGameTest -PbatchSkipTest -PrequesterJar=...0.4.2.jar` 回归通过，日志 `restock-batch-regression.log`，标记 `SUPERWY_BATCH_SKIP_OK`。普通缺货/部分缺货仍跳过；点击前失效和点击后已转移的两种界面变化均先核实再继续，材料只交付一次；借盒期间失效可修复、归还并继续。光标关闭造成数量无法核实的情形仍停止并标记需要核对，未继续后续行。
- 取货步骤最多重试 3 次，取消、停止和订单超时检查保留。缓存用于定位候选坐标，每次开箱仅按当前菜单定位来源。未修改普通组件匹配要求、每单数量或交付规则。
- 回归夹具移除缓存箱子前先清空内容，避免测试产生的散落物进入后续订单背包；零交付断言按当前 `NOTHING_DELIVERED` 总状态和逐行 `OUT_OF_STOCK` 同时核对。
- 最终 `runClientGameTest -PsourceFallbackTest` 通过，日志 `restock-source-final.log`，标记 `SUPERWY_SOURCE_FALLBACK_OK`。覆盖已移除/空容器重试用尽后关窗切换、多个容器凑足数量、缓存数量偏低、所有候选耗尽仅公告一次、保留组件匹配及下一单重置排除记录；结束为 `BUILD SUCCESSFUL`。

# 0.5.4 内存保留与解码积压修复

2026-10-29，Minecraft 26.1.2 / Java 25，本地隔离世界。

- `test build` 通过，168 项 JUnit 全部通过，结果位于 `build-memory-final/test-results/test`。新增 150 条终态历史与 1 条活动订单场景：内存订单索引仅保留活动订单；旧订单详情、幂等重放、按用户限制 10 条历史及关闭重开均通过。分片取消后验证 Netty 缓冲 `refCnt=0`，迟到分片不再分配缓冲。
- `runClientGameTest -PservuxScanTest` 通过，日志 `memory-gametest.log`，标记 `SUPERWY_SCAN_PROTOCOLS_OK`。实际 Servux 0.10.4 协议、多分片、协议切换、取消后迟到响应、超时、无效版本与 F3 恢复全部通过。新增阻塞解码线程后连续注入 33 个分片，确认超限请求失败且待解码队列立即清空。
- 同场 `SUPERWY_NBT_NETTY_OK` / `SUPERWY_NBT_DECODE_OK` 通过，覆盖真实 27×27 嵌套库存、NBT 预算与取消后旧响应隔离。此轮未加载 Litematica；原生 Litematica 桥未在此次游戏回归中重测。
- 为绕开原有缓存及临时目录权限问题，使用 `../tools/memory-check.init.gradle`、独立 Gradle 缓存、临时目录和 `build-memory-final` 输出。Gradle 增量缓存写入曾报告访问失败，游戏任务最终 `BUILD SUCCESSFUL`。
- 本次验证代码持有关系、引用计数和异常路径；未开展数小时生产服务器堆采样，不能据此保证所有第三方模组不存在泄漏。没有使用强制 GC 或工作集压缩。

# 0.5.3 取货前自动关闭容器界面

2026-10-28，Minecraft 26.1.2 / Java 25，本地隔离世界，未操作生产服务器。

- `test build runClientGameTest -PautoCloseMenuTest` 通过，166 项 JUnit 全部通过，日志 `auto-close-menu-gametest.log`，标记 `SUPERWY_AUTO_CLOSE_MENU_OK`。
- 普通 HTTP 订单开始时真实木桶界面未关闭，执行器自动关窗后取货交付成功；批量订单开始时真实 GCA 假人背包未关闭，也能自动关窗完成交付；玩家背包界面同样通过。收货假人离线重召核对准确数量，无多取或重复交付。
- 真实点击将木桶内 5 泥土拿到光标，下一单以 CURSOR_NOT_EMPTY 结束且 fetched=0，容器 ID 和光标物品数量保持原样；夹具手动将光标放回后再次下单，正常交付。服务端最后核对石头消耗恰为 4，泥土仍为 5。
- 检查仅在 FIFO 订单开始执行时进行；入队和批量草稿不关闭当前活动订单的容器。原有转移中界面变化、取消及物品核对逻辑保持原样。
- `node tools/verify_connection_web.cjs` 通过。保留每 50 轮存箱、缺货部分交付及原 FRP 配置。

# 0.5.2 每 50 轮主背包余物存箱

2026-10-28，Minecraft 26.1.2 / Java 25，本地隔离世界，未操作生产服务器。

- `test build`：166 项 JUnit 全部通过。新增 SQLite 关闭重开后保留 49/50 轮边界、同单重复保存不重复计数、清理失败保持到期、核实清理后复位、世界/维度/仓库隔离、旧历史不追溯计数及旧入口迁移一次后允许编辑的验证。
- `runClientGameTest -PcleanupChestTest` 通过，日志 `cleanup-chest-gametest.log`，标记 `SUPERWY_CLEANUP_CHEST_OK`。夹具仅预置 48 条历史计数，实际执行第 49/50/51 轮取货、GCA 交付与存箱：第 49 轮保留余物；第 50 轮将 26 格泥土存入真实坐标 `80,50,-23` 的箱子，原有 63 泥土最终为 89，机器人主背包清空且无地面掉落；第 51 轮保留余物。快捷栏 9 格钻石、全套装备及副手盾牌保持原样。
- 同场测试将计数推进到下一次到期并填满清理箱，交付仍以 stored_offline 结束，delivered=1，余物保留，cleanupErrorCode=CLEANUP_STORAGE_FULL；真实普通聊天提示“本轮已完成交付；背包存箱清理失败”。腾出箱子后下一次交付再次清理，准确存入 5 泥土，核对收货假人离线重召共 5 石头，无重复交付。
- `test build runClientGameTest -PpartialStockTest` 回归通过，日志 `cleanup-chest-partial-regression.log`，保持 64 请求/54 实交/缺货 10、两行部分交付、源盒归还、零库存及后续完整订单行为。
- `node tools/verify_connection_web.cjs` 通过；实际 app.js 使用新清理箱默认坐标，坐标编辑、保存和重新加载通过。清理周期固定 50 个完成交付订单，普通及批量各算一轮；取消、完全无货及交付中断不计数，计数从升级后开始。

# 0.5.1 普通订单缺货后交付已取物品

2026-10-28，Minecraft 26.1.2 / Java 25，本地隔离世界。

- `test build`：163 项 JUnit 全部通过；缺货公告覆盖重复行合并、实际交付计数及物品 ID 校验。
- `runClientGameTest -PpartialStockTest` 通过，日志 `partial-stock-gametest.log`，标记 `SUPERWY_PARTIAL_STOCK_OK`。普通 HTTP 订单要求石头 64 个、真实货源仅 54 个，最终 fetched=54、delivered=54、shortfall=10、partial_stored_offline；收货假人下线后重新召回，服务端背包恰为 54 石头，机器人背包为空。真实普通聊天 `minecraft:stone 缺货10个，已交付54个` 恰一次。
- 同场验证两行各要求 64 个，石头 7 个、源潜影盒内铁锭 21 个，分别实际交付 7/21 个并公告缺货 57/43 个；两种缺货记录均保留，借出的空源盒归还原槽，机器人无残留。零库存不召唤收货假人，公告交付 0；后续足量订单仍正常完成。
- `test build runClientGameTest -PbatchSkipTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.4.0.jar` 通过，日志 `partial-stock-batch-regression.log`，标记 `SUPERWY_BATCH_SKIP_OK`。批量缺货、部分取货、界面在转移前后变化、源盒归还、全部无货及不确定光标停止均回归通过；真实公屏核实 `minecraft:stone 缺货5个，已交付5个`。
- 普通订单开始前某行完全没有候选货源时仍保持整单预检失败；本次修复取货过程中库存耗尽后已取货物未交付的问题。未连接生产服务器。

# 0.5.0 超背包打包、空盒补充和全物品入口

2026-10-28，Minecraft 26.1.2 / Java 25，隔离本地世界，未连接生产服务器。

- 机器人 162 项、收货端 36 项 JUnit 全部通过；覆盖 25/24 格轮次与工作槽预留、64/1 堆叠占槽、假人后缀长度、默认坐标、打包 24 小时时限、百万数量输入及普通单项 64 上限。
- 完整打包实测：真实收货端提交石头 3840 件、60 格清单，真实 QuickShulker 装箱。为较快覆盖中途交付，将携盒上限设为 2；实际产出三盒 27/22/11 组，服务端离线重召核对数量准确。收货基础假人预填满，自动切换 _1。64 堆叠空盒箱只消耗 3 个。日志 packing-gametest.log，最终版本 packing-final-gametest.log。
- 25 盒边界实测：仅测试夹具预置 24 个已核实盒子和对应账本，随后实际取货并装第 25 盒，尚余 64 件时立即交付，再继续取货并交付第 26 盒。离线重召核对共 1664 石头、26 盒；空盒箱只消耗 2 个。parcel-cap-gametest.log，SUPERWY_PARCEL_CAP_OK。
- 全物品入口实测：普通单交付后，主背包 26 格泥土全部投放至真实漏斗/木桶，逐槽核对清空；快捷栏 9 格钻石、四件钻石装备和副手盾牌保持不变。入口采用邻接站位投放，避免站在漏斗上寻路失败。
- 新版混合队列回归通过：packing-task-queue-regression.log，SUPERWY_TASK_QUEUE_OK；私聊单项、HTTP 单项及第二批均可在首批执行时入队，取消中间任务后前方数正确推进，独立进度与真实 FIFO 收货准确。
- 新版逐项异常跳过回归通过：packing-skip-regression.log，SUPERWY_BATCH_SKIP_OK；缺货、部分不足、转移前后容器界面变化、源盒归还继续成功，光标不确定时停止核对。
- 收货端完整投影/原版按键/设置回归通过：../superwy-requester/packing-requester-gametest.log，BUILD SUCCESSFUL。普通单项与批量键位逻辑均保留。
- node tools/verify_connection_web.cjs 通过，执行真实 app.js 并核对工作流默认值、中文逗号/空格坐标输入、保存及重新加载，保留连接指令与剪贴板检查。网页轻量 dashboard 返回工作流配置；最终打包实测也核对 dashboard 的实际保存值。

# 0.4.19 批量执行期间继续接单与混合队列通知

2026-10-28，Minecraft 26.1.2 / Java 25，本地隔离世界。

- `test build`：159 项 JUnit 测试通过。覆盖活动批量接收普通/批量任务、同玩家混合任务上限、持久 FIFO 序号、延后容量复核、幂等重放、队列位置变化与隐私隔离、快速失败在首次检测前结束时仍通知终态。
- 双真实 Mod 混合队列测试 `test build runClientGameTest -PtaskQueueTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.7.jar` 通过（`task-queue-final-gametest.log`，`SUPERWY_TASK_QUEUE_OK`、`BUILD SUCCESSFUL in 44s`）。
- 临时世界中在真实首批取货步骤设置仅 gametest 存在的等待闸门。首批活动时，同一玩家真实私聊单项、HTTP 单项和第二批均受理；收货端四张任务同时保留，排队项前方数量 1、2、3。取消中间私聊单项后，HTTP 和第二批分别更新为 1、2；重复提交第二批 commit 不新增订单。
- 等待中的第二批没有发出任何召唤/清除假人命令；闸门释放后按首批 → HTTP → 第二批执行，第二批在执行时查询真实容量。两批清单分别更新完成进度，GCA 真实存放、离线后重召验证最终石头 1、金锭 2、铁锭 1，没有重复交付或串单。
- 取货端完整投影/设置回归通过（`../superwy-requester/task-queue-requester-gametest.log`，`BUILD SUCCESSFUL in 49s`）：原版中文按键入口、键鼠改绑/清除/重置、HTTP 与私聊单项、投影数量、连续提交和批量草稿继续通过。
- 批量异常跳过回归通过（`task-queue-skip-regression.log`，`BUILD SUCCESSFUL in 56s`、`SUPERWY_BATCH_SKIP_OK`）：缺货、部分数量、点击前后界面变化、源盒归还、全部无货、无法核实时停止的保护均保留。
- 队列快照只遍历未结束任务索引，不逐秒解析全部历史订单。HTTP 队列查询仍受原凭据限制，私聊通知不包含其他任务的 ID、收货人或清单。自用版保留原 FRP 预设，未连接用户生产服务器。

# 0.4.18 批量逐项跳过、界面异常核对及部分交付

2026-10-28，Minecraft 26.1.2 / Java 25，本地隔离世界。

- `test build`：157 项 JUnit 测试通过。新增缺货/界面异常可跳过白名单、取消/超时/不确定交付不得跳过、部分已取数量保留、未取余量、逐行原因持久化和全缺货任务完成判定。
- `test build runClientGameTest -PbatchSkipTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.5.jar` 通过（`batch-skip-gametest3.log`，`SUPERWY_BATCH_SKIP_OK`、`BUILD SUCCESSFUL in 1m 2s`）。仅 gametest 源码中的 Mixin 强制关闭真实容器界面，不进入发行 jar。
- 四行清单：首行无货跳过，石头请求 10 只取到 5 后记录缺 5，金锭点击前强制关闭界面真实触发 MENU_DESYNC 后跳过，最后一项经 QuickShulker 取铁锭 3；一次召唤统一交付石头 5 和铁锭 3，HUD 已处理 4/4、跳过 3。
- 金锭完成目标槽点击后关闭界面，真实触发 CURSOR_OR_MENU_DESYNC；服务端 NBT 确认金锭 2 已转移，准确记账且只交付一次，随后清单中的铁锭仍成功。
- QuickShulker 内点击前关闭界面：核对背包、归还原位置源盒、刷新同一箱子缓存，然后继续下一项；源盒残余铁锭保持 17，后续金锭成功交付。消除了整箱缓存被标记无效后，后续同箱材料被误判缺货的问题。
- 两项全部缺货时处理到 2/2，不召唤空快递；HUD 显示均未取得材料，公屏发送指定完成文本。服务端仍以 failed / NOTHING_DELIVERED 表示没有物品实际交付。
- 单独测试点击后光标携带整堆 64 金锭却只请求 1 的关闭场景：背包变化与预期不符，保留物品并以 RECONCILIATION_REQUIRED 停止，下一项未执行，避免错误记账或重复取货。取消、断线、超时及不能归还源盒也保留原停止保护。
- 所有已交数量均通过真实 GCA 菜单及服务端 NBT 核对，离线后重召再次检查收货人累计物品。未连接用户生产服务器，无法仅由截图确定其界面变化的具体诱因。

- 原有订单回归 `test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.5.jar` 通过（机器人 `batch-skip-regression.log`，`BUILD SUCCESSFUL in 3m 19s`）：普通失败、原地 GCA、源潜影盒借还、多人 FIFO、历史库存、连接弹窗、原生与系统私聊、80 物品单空槽分批、满收货人、立即取消及模拟 501 秒超时均通过。

# 0.4.17 批量预约、取货与私聊进度

2026-10-27，隔离 Minecraft 26.1.2 / Java 25，本地临时世界。

- `test build`：154 项 JUnit 测试通过，覆盖批量预约身份隔离、HTTP/私聊共用拒单、执行中从排队索引移除后仍保持锁定、幂等重放、终态解锁、容量最小值减五，以及系统私聊格式。
- `test build runClientGameTest -PbatchPickupTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.4.jar` 通过（`batch-final-gametest.log`，`SUPERWY_BATCH_PICKUP_OK`、`BUILD SUCCESSFUL in 58s`）。
- 两个真实 Mod 私聊往返：实际 NBT 返回机器人 27 空槽、收货假人 12 空槽，容量为 7；查询结束假人下线，编辑阶段和已经取出材料的执行阶段 HTTP 均为 409 / BATCH_BUSY。
- 一张清单含石头 5、铁锭 3、金锭 4，按短消息逐行提交。石头来自两堆 2+3，合并到已取堆叠；铁锭经真实 QuickShulker 借还源盒。取齐三行后仅一次正式召唤，真实 GCA 存放并 NBT 核对；收货假人原有 24 个泥土保留，离线后再召唤核对全部数量。
- `/tell` 进度经双方协议实际回传并更新收货端 0/3 → 3/3，普通公屏严格发送 `搞定了，我歇逼了`。完成后普通订单可执行，放弃下一批草稿后也能恢复接单。
- 截图已目视检查；收货端对应截图和说明见其 docs/ 与 VERIFICATION.md。没有连接远程生产服务器；私聊批量需双方升级并在同服启用私聊接单。

- 原有交付回归 `test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.4.jar` 通过（机器人 `batch-regression-gametest.log`，`BUILD SUCCESSFUL in 3m 13s`）：多区控制、遮挡容器交互、GCA 原地交付、QuickShulker 借还、多玩家 FIFO、历史库存、连接弹窗、原生/系统私聊往返、单空槽 80 物品分批、收货人满背包、立即取消和模拟 501 秒超时均通过。

# 0.4.16 队列清空提示文案

2026-10-27。

- 将已启用队列进度播报后的剩余 0 单提示替换为 `都取完了，我歇逼了`；剩余数量大于 0 时保持 `还有N个订单在队列`。触发阈值、播报频率及队列清空后的重置不变。
- `test build compileGametestJava` 通过（`queue-done-message.log`，`BUILD SUCCESSFUL in 11s`）。更新既有队列边界断言和游戏测试预期，未重新启动游戏回归。

# 0.4.15 召唤失败后清除并重试一次

2026-10-27，隔离 Minecraft 26.1.2 / Java 25，本地临时世界。

- `test build runClientGameTest -PspawnFailureTest` 通过（`spawn-retry-verified.log`，`BUILD SUCCESSFUL in 1m 3s`、`SUPERWY_SPAWN_RETRY_OK`）。
- 服务端实际召唤快递假人，通过真实移除实体和玩家列表数据包使机器人本地看不到该假人。机器人首次召唤真实触发 `Player SW_spawn_fail is already logged on`，随后主动执行一次 kill，再次召唤并完成真实 GCA 交付；指令顺序严格为首次 spawn、补救 kill、重试 spawn、正常交付后 kill，收货数量为 1 且未重复取货。
- 测试中拦截两次召唤指令模拟持续失败：仅尝试两次 spawn，中间一次 kill；第二次失败保留 `FAKE_PLAYER_UNAVAILABLE`，已取货物保留并标记核对，失败公告一次，下一单仍能正确交付。
- 首次召唤等待期间取消订单后，没有补救 kill 或第二次 spawn，订单保持 cancelled。原先已在线时的交付前清除与队列播报逻辑保留。

# 0.4.14 在线快递假人重召唤与队列进度

2026-10-27，隔离 Minecraft 26.1.2 / Java 25，本地临时世界。

- `test build runClientGameTest -PdeliveryQueueTest` 通过（`delivery-queue-verified.log`，`BUILD SUCCESSFUL in 46s`、`SUPERWY_DELIVERY_QUEUE_OK`）；151 项 JUnit 测试通过。
- 在远处预先召唤快递假人，背包预放 5 个泥土。机器人取货后先 kill，确认玩家列表和实体均离线，再原地召唤并使用真实 GCA 菜单交付。服务端确认第一次重召唤发生时原实体已移除；三单成功交付后，假人离线保存的背包保留 5 个泥土并新增 3 个石头。
- 同一主线程批量入队四单，其中一单故意缺货。FIFO 执行结束后公屏严格依次收到 `还有3个订单在队列`、`还有2个订单在队列`、`还有1个订单在队列`、`还有0个订单在队列`，每条一次且均为真实 `ChatType.CHAT`，包括缺货失败订单；下一批单个订单不播报，实际物品继续正确累计。
- 单元测试覆盖严格大于 3 的阈值、处理中新增与结束前新增订单、播报至 0 后重置、排队订单全部取消后的空闲重置、世界/维度隔离，以及计数排除正在处理和已取消订单。计数读取内存排队索引，不反序列化历史订单。
- 交付回归 `runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 通过（`delivery-queue-regression.log`，`BUILD SUCCESSFUL in 3m 8s`）。包括两个区域各 81 个容器独立控制、原地 GCA 交付、嵌套借还、多人 FIFO、历史库存、玩家端连接和私聊往返、单空槽 80 物品分批、满收货人、立即取消、点击中取消、模拟 501 秒超时与下一单继续。

# 0.4.13 所有取货失败公告统一普通聊天

2026-10-27，隔离 Minecraft 26.1.2 / Java 25，本地临时世界。

- 修复原先仅 `OUT_OF_STOCK` 使用普通聊天、其他失败仍通过 `/say` 发出的遗漏。`OrderFailurePolicy` 返回纯文本，`OrderEngine.announceFailure` 统一调用 `sendChat`，涵盖组件冲突、缺货及超时等取货失败。
- `test build runClientGameTest -PfailureChatTest` 通过（`failure-chat.log`，`BUILD SUCCESSFUL in 28s`、`SUPERWY_FAILURE_CHAT_OK`）；147 项 JUnit 测试通过，新增白色潜影盒组件冲突和超时的纯文本公告断言。
- 真实游戏中在同一木桶放入空白色潜影盒和装有石头的白色潜影盒，不指定组件版本下单，实际产生 `AMBIGUOUS_ITEM`。中文失败消息通过真实服务端往返，接收类型断言为 `ChatType.CHAT`，只出现一次。紧接的钻石块缺货订单同样使用普通聊天且只公告一次，验证失败后队列继续，双方库存未移动。
- 大容量 NBT、坏流、过预算和取消后迟到回复检查同时通过。`node --check src/main/resources/web/app.js` 和 `node tools/verify_connection_web.cjs` 通过。
- README、私聊说明及 OpenAPI 的失败公告描述同步更新。保留 Servux 十分钟间隔和原自用预设。

# 0.4.12 Servux 十分钟扫描间隔

2026-10-27，隔离 Minecraft 26.1.2 / Java 25，本地临时世界。

- `test build runClientGameTest -PservuxScanTest` 通过（`servux-ten-minute.log`，`BUILD SUCCESSFUL in 42s`、`SUPERWY_SCAN_PROTOCOLS_OK`）；146 项 JUnit 测试通过。
- Servux 有效扫描间隔固定为 600 秒，从上一轮完成时间计算下一轮。游戏测试确认概览 `intervalSeconds`、`nextDueAt` 和持久断点 `nextAttemptAt` 一致；切回 F3 后恢复区域原有间隔。
- 单元测试覆盖现有区域、配置/数据库重开后的间隔计算、未完成断点恢复以及暂停状态保留。区域原始间隔不被覆盖。十分钟期限采用确定性时间计算断言，未实际等待十分钟。
- 实际 Servux 0.10.4 编解码与分片定向回归通过，包含迟到回复、切换协议、超时、坏流及 F3 恢复。
- `node --check src/main/resources/web/app.js` 与 `node tools/verify_connection_web.cjs` 通过。

# 0.4.11 大库存主线程负载优化与普通聊天缺货提示

2026-10-26，隔离 Minecraft 26.1.2 / Java 25，本地临时世界，未连接用户远程服务器或修改用户游戏实例。

- 144 项 JUnit 测试通过，新增物品/区块索引的替换、删除、负坐标、世界/维度与组件过滤、嵌套槽位、输入/输出隔离、异步保存顺序、批量事务后重开数据库、待处理 FIFO 查找、查询工作线程及 40,000 个待扫坐标的黄框上限验证。
- `test warehousePerformance runClientGameTest -PscanBackgroundTest` 通过（`performance-background.log`，`BUILD SUCCESSFUL in 36s`）。生产 `Items.snapshot` 使用游戏线程捕获的注册表，在真实 `superwy-scan-decode` 线程解码，嵌套潜影盒中的 21 个铁锭正确保留。
- 故意阻塞解码线程后，停止区域仍能保存断点并返回；释放线程后取消的后台结果不写入库存，重新开始可读取并持久保存。大库存测试向隔离数据库写入 6,000 个容器 / 162,000 个物品堆，100 次真实主线程 `updateView` 调用合计 2.779 毫秒；不再包含全库库存汇总。
- 可复现 CPU 探针 `gradlew warehousePerformance`：同样 6,000 个容器 / 162,000 个物品堆，旧的全库 JSON 解析加稀有物品货源查询平均 111.468 毫秒；新索引的相同查询平均 0.008 毫秒；大量常见物品的索引查询平均 6.099 毫秒，并在后台执行。40,000 个待扫坐标最多生成 128 个世界黄框、6 个 HUD 坐标；选择每 250 毫秒更新，完整队列不丢弃。这是本地模拟 CPU 耗时，不是用户服务器的 FPS 测量。
- Servux 与 Litematica 0.27.9 共存定向测试通过（`performance-servux.log`，`BUILD SUCCESSFUL in 39s`、`SUPERWY_SCAN_PROTOCOLS_OK`），验证后台分片/NBT 解码后多片回复、嵌套内容、取消/切协议迟到回复、坏流、超时及 F3 恢复。
- 最终构建无 Litematica 的 Servux 定向回归通过（`performance-servux-final.log`，`BUILD SUCCESSFUL in 33s`），包含后台解码、批量事务和断点异步保存后的相同协议场景。
- 跨区域逐箱补货定向测试通过（`performance-source-fallback.log`，`BUILD SUCCESSFUL in 1m 51s`）。两个区域的容器消失、搬空、数量分散、缓存偏低与组件隔离仍正常；新增通过实际 `ChatType.CHAT` 消息确认缺货走普通聊天，未使用 `/say`。
- `node --check` 和连接页面 DOM 测试通过。后台只处理独立 NBT/模型及已捕获注册表，Minecraft 世界读取、发包、寻路、点击、断点推进仍在游戏线程；库存与扫描断点只有持久保存成功才被确认。
- 最终 `test build runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 全部通过（`performance-full.log`，`BUILD SUCCESSFUL in 4m 28s`）。包括持久断点、两个各 81 容器区域独立控制、开箱寻路、嵌套潜影盒原槽归还、真实 GCA 交付、多人 FIFO、历史库存、实际玩家端连接/私聊往返、分批、满收货人、取消及模拟 501 秒超时。日志中的历史 `SUPERWY_STOCK_FAIL_SAY_OK` 标记名称保留，但本版缺货发送代码使用 `sendChat`，普通聊天类型由定向用例单独断言。

# 0.4.10 跨区域候选与逐箱补货

2026-10-26，隔离 Minecraft 26.1.2 集成世界，未修改用户游戏实例或连接其远程服务器。

- `test build runClientGameTest -PsourceFallbackTest` 通过（`source-fallback-targeted.log`，`BUILD SUCCESSFUL in 1m 53s`、`SUPERWY_SOURCE_FALLBACK_OK`）。138 项 JUnit 测试全部通过；新增取货行为由真实游戏测试覆盖。
- 建立两个启用区域，先用生产扫描器缓存容器，再在服务端改变实际库存。A 缓存 64 个拉杆但方块已删除、B 缓存 1 个但实际 9 个，请求 7 个：删除 A 的失效快照后从 B 完成交付。A 缓存 64 个黑色玻璃板但已搬空、B 实际 6 个，请求 5 个：A 更新为空，B 完成交付。
- 两区域三个容器实际库存为 2、3、4，请求 8 个，真实取出 2+3+3，第三箱剩余 1 个。缓存仅为 1+1、实际为 4+4 时请求 7 个，也能现场核对后取齐。每个成功场景均通过真实 GCA 菜单及服务端实体库存核对收货数量。
- A 已拆除、B 已清空时，穷尽候选后才 `failed / OUT_OF_STOCK`，只公告一次缺货，不召唤收货假人、不反复访问失效来源。A 仅剩不同组件物品时不会错误取走，从 B 获取正确物品。下一单重新填入 A 后可正常取货，排除记录没有跨订单残留。
- 仅确认容器消失、或借盒/转移前发现缓存内容变化时允许候选切换。已开始且结果不确定的转移、源盒尚未归还、光标异常、取消和超时不会进入替代货源流程。
- `node --check` 与连接页面 DOM 验证通过，网页和扫描协议代码未改变。
- 完整 `test build runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 通过（`source-fallback-full.log`，`BUILD SUCCESSFUL in 4m 19s`）。包括多区域扫描、断点、寻路、QuickShulker 借还、GCA 交付、FIFO、历史库存、真实玩家端连接/私聊往返、分批、取消和模拟 501 秒超时。

# 0.4.9 仓储容器白名单与实验性 Servux

2026-10-26，Minecraft 26.1.2 / Java 25，使用隔离本地世界，未修改用户游戏实例或连接用户远程服务器。

- `test build`：138 项 JUnit 测试通过。新增默认/旧配置 F3、协议持久化、非法配置拒绝、旧漏斗/熔炉库存过滤、批量库存与断点事务、负区块及高度编码、多片大 NBT 重组和坏流/上限校验。
- 无 Litematica 的定向游戏测试通过（`servux-no-litematica-final.log`，`BUILD SUCCESSFUL`、`SUPERWY_SCAN_PROTOCOLS_OK`）。测试通过隔离类加载器使用实际 Servux 0.10.4 发行 jar 的请求解码器、回复编码器及 PacketSplitter，不只用自己实现的协议互测。
- Litematica 0.27.9 + MaLiLib 0.28.8（`servux-oldclient-gametest.log`）与 Litematica 0.27.14 + MaLiLib 0.28.12（`servux-litematica-gametest.log`）共存测试均通过。实际原生消息类和通道注册参与测试；集成世界共享客户端/服务端 codec，测试专用解码 hook 用真实 Servux v1 解码模拟远程服务端，不打入发行包。
- 最终源码再次执行 `test build runClientGameTest -PservuxScanTest -PcompatibilityMods=build/servux-oldclient` 通过（`servux-final-targeted.log`，`BUILD SUCCESSFUL in 43s`）。
- 游戏测试确认默认 F3、HUD 指令切换和配置保存；一批读取四个仓储容器，漏斗/熔炉/陶罐排除；只有排除容器的区域不发批量请求。1.1 MiB padding 强制使用真实多片传输，嵌套潜影盒第 19 槽的 7 个金锭完整保留。
- 整批异常、未知协议和请求超时保留历史库存与待扫位置。超时通过推进请求截止时间触发生产 tick，未等待 15 秒。请求在途切换 F3 后迟到回复不会覆盖新 F3 库存；旧回复收齐后可重新使用 Servux。
- `node --check src/main/resources/web/app.js` 与 `node tools/verify_connection_web.cjs` 通过。Web 显示当前协议和中文协议异常，既有连接指令流程正常。
- 完整 `test build runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 通过（`servux-full-final2.log`，`BUILD SUCCESSFUL in 4m 27s`）。包含 F3 持久断点、容器白名单、两个各 81 容器区域独立启停、寻路与开箱、QuickShulker 原槽借还、GCA 交付、多人 FIFO、历史库存、真实玩家端连接/私聊下单、立即取消、模拟 501 秒超时和轻量概览。
- 首轮回归暴露测试把“手动启动新一轮”误判为“只继续旧完成断点”；修正为先验证被动等待不读取新增木桶，再显式重新扫描后读取，不更改手动扫描的既有行为。旧断点的容器类型迁移仅在恢复或新一轮开始时处理，避免每个容器读取前反复反序列化全库。
- 实验边界：没有远程生产服兼容性或速度测量。Servux 服务端提供器、权限和真实远程连接仍需用户服务器验收；协议批量流缺少独立请求编号，避免同时运行 Litematica 的 Servux 批量保存任务。区块回复中其他类型方块实体可能仍传输，库存只接受三类仓储容器。

# 0.4.8 公网端口记忆与玩家选择收货名

2026-10-26。

- 完整隔离 `runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 回归通过，`connection-port-gametest.log` 显示 `BUILD SUCCESSFUL in 4m 27s`。覆盖新连接弹窗及原有寻路、扫描、多区控制、QuickShulker、HTTP/私聊交付、FIFO、分批、防溢出、立即取消、500 秒超时与轻量历史。

- `test build`：132 项 JUnit 测试通过。新增已注册公网端口落盘、配置重载后优先复用、占用后随机换号并持久替换、注册失败不覆盖旧端口、保存失败关闭隧道，以及新连接凭证的名称权限、独立主体与持久化验证。
- `node --check` 和 `node tools/verify_connection_web.cjs` 通过；网页无需填写收货名，使用空对象生成 swc2 连接指令，复制和普通 HTTP 剪贴板回退、端口变化后清除旧指令、无库存轮询保持正常。
- 隔离游戏实际弹窗验证通过（`connection-port-gametest.log`，`SUPERWY_CONNECTION_IMPORT_OK`）：默认 sf；空名称不能保存，取消保留旧文件；确认自选收货名后才落盘；HTTP 下单并真实 GCA 交付成功；同一凭证修改为另一收货名后再次成功交付；重连重新弹窗，每次生成独立普通凭证。
- 弹窗截图 `build/run/clientGameTest/screenshots/0002_connection-recipient.png` 已目视检查，标题、地址、输入、保存/取消及提示无重叠；截图也附在玩家项目 `docs/connection-recipient.png`。
- 端口持久化使用替代 FRP 子进程测试，不启动用户公网隧道、不修改用户游戏目录。新普通凭证允许合法自选收货名，但订单读取/取消仍按主体隔离；旧 swc1 和受限凭证不升级权限。

# 0.4.7 仓库预设、托管 FRP 与玩家连接导入

2026-10-26。只读读取运行中的 Minecraft 实例 `config/superwy`，从 SQLite 导出 `bh` 和 `warehouse` 两区坐标，未复制库存/订单/管理员令牌，也未修改真实游戏实例。只读读取本机 `frpc.toml` 与 frpc 0.71.1；既有外部 frpc 保持运行。

- 机器人 `test build`：129 项 JUnit 测试通过，新增仓库世界匹配、预设不覆盖/删除后不重建、随机端口冲突回退、玩家连接指令格式与 256 字符长度、限定收货名凭证，以及 FRP 冲突重选/认证拒绝/超时/停止等验证。玩家端 25 项测试通过，新增导入保存与非法指令保留原设置。
- `frpLiveCheck` 实际公网测试通过，日志 `frp-live-build.log`，标记 `SUPERWY_FRP_LIVE_OK`。使用打包的 frpc，连接既有 FRP 服务；随机公网 22406 转发到隔离 nonce 服务的随机本机 54102，公网 HTTP 实际返回正确 nonce。退出关闭测试隧道，不触碰已有代理或真实游戏服务。此地址为临时测试结果，不能当作长期使用地址。
- 游戏完整回归 `runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.2.jar` 全部通过（`BUILD SUCCESSFUL`，日志 `frp-full-gametest2.log`）：实际管理员接口生成限定收货名的指令、真实玩家端 `/swrequest connect` 保存 HTTP 配置、真实 HTTP 订单与 GCA 收货核对、机器人游戏命令复制一致、隧道启停不取消取货。游戏回归使用替代隧道进程与本地地址，公网链路由上一项单独验证。标记 `SUPERWY_CONNECTION_IMPORT_OK`。之前的寻路、大容量 NBT、多区域扫描、多人队列、原地交付、私聊、立即取消、500 秒超时和轻量前端均通过。
- 执行真实 app.js 的 DOM 夹具：连接状态、生成复制、不可用 Clipboard API 的普通 HTTP 复制回退、端口变化/停止清除旧指令均通过；未请求库存树。`node --check` 通过。标记 `SUPERWY_CONNECTION_WEB_OK`。
- Windows x64 frpc 以独立子进程运行，提取时核对 SHA-256，退出只关闭自己拥有的进程。随机冲突最多 12 次；认证/端口禁用等错误停止并显示中文。其他平台没有内置可执行文件，返回中文说明。
- 普通 jar/源码/玩家 jar 不含 FRP 认证；单独 `-private.jar` 才注入所有者凭证。连接指令包含按收货名限制的普通 API 凭证，不含管理员令牌或 FRP 认证；状态接口不返回凭证。
- 对实际打包的私有 jar 执行 `privatePresetCheck` 通过（`frp-private-preset.log`，`SUPERWY_PRIVATE_PRESET_OK`）：首次注入凭证和允许 Host，保存关闭状态后再加载不会被预设重新启用。该检查不启动隧道或连接网络。发行 jar 不含任何测试类。

# 0.4.6 大容量 NBT 查询与异常扫描隔离

2026-10-26，用户断线报告中的根因是 `NbtAccounterException`：原版 `ClientboundTagQueryPacket` 读取 NBT 时超过 2097152 字节的内存计量预算，并非 HTTP 错误或扫描频率问题。

- `test build`：119 项 JUnit 测试通过，新增 8 项验证原版 2 MiB 限额复现、16 MiB 计量上限、独立原始字节上限、损坏及尾随数据、取消后丢弃、连接身份、记录过期和容量限制。
- 定向 `runClientGameTest -PnbtDecodeTest` 通过，日志 `nbt-netty-gametest3.log`。真实 Minecraft 26.1.2 客户端与集成服务器中创建含 27 个潜影盒、729 组带组件物品的木桶：真实回复重新编码后，原版预算失败，机器人查询通过生产解码 Mixin 完整读入缓存。标记 `SUPERWY_NBT_DECODE_OK`。
- 注入超过 16 MiB 计量预算与损坏 NBT 回复，扫描最终为 2 个成功、2 个跳过，连接保持可用；失败容器历史的 10 个石头保留且标为未完整扫描，未被清空。取消查询的迟到回复丢弃，后续实体库存查询正常完成。
- 额外使用 Netty EmbeddedChannel 与生产 `PacketDecoder` 验证真实管道连接查找：本连接已取消的大回复被丢弃，同一查询编号在其他连接仍触发原版预算异常。标记 `SUPERWY_NBT_NETTY_OK`。该测试运行实际解码器，但不代表已在远程服务器 TCP 链路验收。
- 仅机器人发出的查询采用 16 MiB 内存计量、2 MiB NBT 原始字节预算，原版深度限制保留。其他查询保持原版行为；暂停/取消/超时保留最多 60 秒的查询归属，断线清空。超过传输层限制或其他包类型的断线不在此次处理范围。
- 测试使用隔离开发目录，未连接用户远程服务器或修改用户游戏实例。玩家取货端保持 0.3.1。
- 回归中补正容器目标的方块中心与实际站位偏移：目标保留 0.9 格余量，提前结束接近步骤时要求落地且命中点比交互上限近 0.35 格；停稳后核对距离。定向夹具复现旁边另一木桶阻挡路线时，在 4.5 格边缘取消路径后微小位移导致命中点距离变为约 4.501 格的失败。
- `runClientGameTest -PforcedStopsTest` 通过，日志 `nbt-forced-stops2.log`：模拟单调时钟经过 501 秒，运行订单立即失败，排队取消立即生效；下一单真实开箱、取物和 GCA 交付成功，点击中取消保留光标且不再继续操作。标记 `SUPERWY_FORCE_TIMEOUT_OK`、`SUPERWY_MID_CLICK_CANCEL_OK`。
- 最终 `test build runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 全部通过，日志 `nbt-full-final.log`（`BUILD SUCCESSFUL`）。包含大容量/异常 NBT、Netty 连接隔离、前后台转向、32 秒共享输入、跳跃/半砖、10 格开箱与服务端距离回退、持久扫描/失败跳过、两个 81 容器区域独立启停、原地 GCA 交付、QuickShulker 原槽归还、缺货公告、多人 FIFO、历史库存、私聊及真实玩家端往返、单空槽分批、满收货人异常、立即取消、500 秒超时及最新 10 条历史概览。

# 0.4.5 容器距离寻路目标与统一中文错误

2026-10-26，隔离 Minecraft 26.1.2 / Fabric 客户端与真实 Baritone、Carpet、GCA、QuickShulker 验证。

- `test build`：111 项 JUnit 测试通过，新增最近交互面距离、头顶/负坐标目标、中文解释、未知异常不外泄原始文本、稳定 API 错误代码与中文 message 验证。
- `runClientGameTest -PcontainerPacketTest` 通过，日志 `container-goal-targeted-final.log`。远处头顶箱子被实心平台遮挡，地面为底半砖，服务端真实交互距离为 4.5：初次远距拒绝后，机器人通过新的 Baritone 交互距离目标走近，在没有可见射线的情况下打开正确箱子，镜头保持原方向。缓存目标位于未加载区块时先启动寻路，不再预先返回站位列表失败。标记 `SUPERWY_CONTAINER_GOAL_OK`。
- 完整 `runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 通过，日志 `interaction-chinese-full-gametest.log`。涵盖新交互目标、前后台转向、共享移动、跳跃、跨空隙、半砖、10 格交互及服务端拒绝后的靠近重试。
- 真实服务端收到的失败 `/say` 为中文，例如“收货假人物品栏已满，材料保留在原位”“任务执行超过五百秒，已强制停止”；订单和概览的 `errorMessage` 是中文，原 `errorCode` 保留供程序处理。缺货仍逐个物品 ID 公告“缺货”。
- 执行实际 app.js 的 Node DOM 验证：订单原因、导航错误、扫描失败、执行器阻挡说明均显示中文，不把英文代码当作说明展示。`node --check` 通过。
- 仍保留原地 GCA 交付、嵌套源盒原槽归还、多区域独立控制、断点持久化与失败跳过、最近 10 条订单轻量概览、私聊和玩家 Mod 往返、多人 FIFO、立即取消与 500 秒超时。
- 不再要求配置工作点，也不预枚举整格空位。客户端实际加载检查采用区块缓存；服务器仍决定开箱距离、箱盖能否打开及交互权限，拒绝时不伪造菜单或绕过物品核验。

# 0.4.4 多区域独立控制与轻量前端

2026-10-26，隔离 Minecraft 26.1.2 客户端/集成服务器验证，未连接用户服务器或修改用户游戏实例。

- `test build`：107 项 JUnit 测试通过。新增区域轮流调度、停止状态及断点重启保存、最近 10 条历史上限、全部运行订单保留、按结束时间选择历史、扫描明细裁剪，以及普通玩家队列历史数量/身份隔离验证。
- 完整 `runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 通过，日志 `region-dashboard-full-gametest.log`，包含原有前后台转向、32 秒共享移动、跳跃、跨空隙、半砖、10 格交互、持久断点和特殊容器跳过测试。
- 两区各 81 个容器，A 未结束前 B 已读取；停止 A 后 A 计数和下一个待读位置不变而 B 继续。全局关闭/开启没有解除个别停止标记；HTTP 和游戏命令分别恢复两区，最终各恰好读取 81 个且无错误跳过。标记 `SUPERWY_REGION_CONTROLS_OK`。
- 真实管理员轻量接口不含库存容器树、源坐标全集或订单明细；全部未结束订单保留，已结束记录恰好限制为最近 10 条。标记 `SUPERWY_COMPACT_DASHBOARD_OK`。
- 用 Node VM 执行实际 app.js，管理员/普通玩家两种响应各提供 30 条历史及 2 条未结束订单，实际 DOM 均仅显示 12 行；管理员每轮 2 次请求、普通玩家 3 次请求，无库存请求、无逐订单详情轮询；页面隐藏时没有新增请求。`node --check` 通过。
- 单个扫描操作使用独立 TaskScope；区域停止只中断所属扫描，不取消其他区域或取货 scope，不把正常停止记为永久失败。扫描停止标记保持独立于库存区域 enabled，历史库存继续可用。
- 潜影盒原槽归还、多堆材料分批、GCA 原地散装交付、多人 FIFO、缺货 /say、私聊及实际玩家 Mod 往返、500 秒超时和点击中立即取消均通过。
- 全套夹具场景间仅在测试代码重置写入限速计数，避免多个独立场景在一分钟内累计超过 30 次；发布程序的限速规则保持不变。历史列表裁剪不删除数据库订单及幂等记录。

# 0.4.3 箱内源潜影盒借读与原槽归还

2026-10-26，修复实际实例缺少 QuickShulker 客户端类导致的中断。只读取实例日志、mod 列表和订单错误进行诊断，未更改运行实例或连接其服务器测试。

- `test build`：100 个 JUnit 测试通过。
- `runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 通过，日志 `nested-cycle-gametest.log`。真实 QuickShulker、Carpet、GCA 和 Baritone 参与隔离集成测试。
- 新用例：双箱第二半第 13 槽中放置带自定义名称的紫色源潜影盒，内部稀疏槽位含 64+10 金锭及 7 泥土，仅留两个空闲主背包槽。请求 65 金锭，经历多次借读和分批交付；源盒回到同一物理箱子的原槽，剩余 9 金锭及 7 泥土，颜色/名称保留，机器人无残余源盒或材料，个人物品未移动。收货假人服务端库存核实为 65 金锭。标记 `SUPERWY_NESTED_SOURCE_CYCLE_OK`。
- 内置兼容消息编码为 4 字节菜单槽号，使用真实 QuickShulker 的服务端消息 codec 解码核对。客户端有 QuickShulker 时使用其消息类，不重复注册通道。
- `runQuickBridgeSmokeClient` 通过，日志 `quick-bridge-no-client-smoke.log`：全新隔离目录且未加载 QuickShulker，兼容通道注册、客户端入口、mixin、SQLite、HTTP 初始化成功。此项是无 QuickShulker 客户端启动验证；实际完整取物用例在双方安装真实 QuickShulker 的隔离集成世界执行。
- 头顶容器包交互、GCA 原地交付、缺货立即停止与 /say、FIFO、历史库存、私聊和实际玩家 Mod 往返、单空槽分批、满收货人异常及取消/500 秒超时回归通过。

# 0.4.2 异常立即终止与缺货公告

2026-10-26，在隔离 Minecraft 26.1.2 集成世界，加载真实 Carpet、GCA、QuickShulker、Baritone 及玩家取货端 0.3.1 验证。下方旧版本段落是历史记录；等待补货、恢复状态冻结队列等行为已由 0.4.2 替代。

- `test build`：100 个 JUnit 测试全部通过，覆盖重复行库存合计、组件库存隔离、缺货物品去重公告、命令文本校验及启动时异常订单终止和证据保留。
- `runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 通过，日志 `fail-fast-gametest.log`。
- 清单含有货物品和两种缺货物品，其中一种重复两行：即使 `allowPartial=true` 也立即 `failed / OUT_OF_STOCK`，未开始取物；服务端真实接收 `/say`，客户端收到每种缺货 ID 各一次公告；后续 FIFO 订单完成实际交付。标记 `SUPERWY_STOCK_FAIL_SAY_OK`。
- 满收货假人：已经取出的货物保留在机器人原槽，订单立即 `failed / RECIPIENT_FULL`，`needsItemReview=true`；已召唤假人保持原状；下一单实际交付成功。标记 `SUPERWY_DIRECT_FULL_FAIL_OK`。
- 正常单空槽 80 件材料仍分批交付，无溢出或个人物品移动。标记 `SUPERWY_DIRECT_BATCHES_OK`。
- 取货寻路期间推进本单单调时钟至 501 秒，watchdog 立即停止寻路，以 `ORDER_TIMEOUT` 结束并公告，下一单正常交付；未实际等待 500 秒。标记 `SUPERWY_FORCE_TIMEOUT_OK`。
- 槽位点击中立即取消后不再继续点击或走动；残留光标不被后续订单操作，后续订单直接 `failed / CURSOR_NOT_EMPTY`。标记 `SUPERWY_MID_CLICK_CANCEL_OK`。
- 地面潜影盒优先、嵌套借还、历史库存、多人 FIFO、原地 GCA 散装交付、原版及系统私聊接单、实际玩家 Mod 下单及回执往返均通过。

# 0.4.1 系统消息私聊兼容修复

2026-10-26。用户日志确认 0.4.0 已加载且机器人就绪，但服务器将 `PlayerA悄悄地对你说：swget1 ...` 作为 System Chat 发送，旧原版 CHAT 类型监听没有接收。

- 新增系统消息兼容：标准中文/英文完整私聊模板、原版翻译组件及包装组件。发送者匹配当前在线玩家 UUID；公屏作者前缀、普通提示、动作栏和发送方向不作为订单。
- 95 项 JUnit 测试零失败，包含用户同格式字符串、彩色文字、回执、公屏仿造和换行拒绝。
- `gradlew test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.1.jar` 成功，记录 `system-whisper-e2e-gametest.log`。
- 集成服务器实际发送中文私聊系统包、包装过的带名牌翻译组件：均创建订单并完成真实 GCA 收货核对；带公屏作者前缀的消息不创建订单。标记 `SUPERWY_SYSTEM_WHISPER_OK`。
- 两端同时加载的真实 /tell 请求与回执、短 key、原地交付、FIFO、分批、立即取消、500 秒超时回归通过。

测试为隔离本地世界，没有连接用户服务器或修改用户游戏实例；现场读取仅用于确认已加载版本、消息类别与就绪状态。

以下为历史版本记录。

---

# 0.4.0 游戏私聊接单与可选短 key

2026-10-26，Minecraft 26.1.2 / JDK 25.0.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build`：91 个 JUnit 测试通过。新增协议格式、物品清单、数量边界、选择器/换行拒绝、可选 5 位短 key、回执内容、同 UUID 请求 ID 幂等、跨玩家隔离、同 FIFO 与队列限额测试。
- `gradlew test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.0.jar` 完整通过。记录 `whisper-e2e-gametest.log`。
- 在真实集成服务器以另一位 Carpet 玩家执行 `/tell`：清单石头 1、铁锭 1 被机器人受理并实际交付到指定假人。将同一消息先发到公屏未创建订单；相同请求 ID 重发不新增订单。
- 游戏内 `/superwy whisper key Ab123` 立即保存配置：缺失 key 的消息未入队，正确 key 的消息成功交付。关闭私聊后不入队，重新开启并关闭验证后恢复无 key 模式。标记 `SUPERWY_WHISPER_INBOX_OK`。
- 同时加载两个真实 mod 后，用玩家端游戏命令设置接单机器人、收货假人、物品、数量和 chat 模式，通过真实 `/tell` 下单并收到匹配的受理回执，随后完成 GCA 原地交付与下线核对。标记 `SUPERWY_REQUESTER_WHISPER_E2E_OK`。
- 原地交付、远处旧平台忽略、嵌套潜影盒借还、多人 FIFO、历史库存、80 件分批交付、立即取消、500 秒超时与点击中途取消回归通过。均为隔离本地世界，未向用户服务器发送测试订单。
- 接收使用 Fabric 私聊类型，兼容本次真实测试中的无签名/profileless `/tell`。不从公屏或普通系统字符串中猜测私聊来源；协议消息在客户端显示前处理，回执不回显短 key。
- 发布包不包含测试代码、测试依赖或用户配置。共同协议源码打入独立源码归档的 `shared-src/`，完整规范随包提供。

以下为历史版本记录。

---

# 0.3.8 取货后原地召唤并交付

2026-10-25，Minecraft 26.1.2 / Java 25.0.2 / Fabric Loader 0.19.5。

`gradlew test build runClientGameTest -PdirectDeliveryTest` 成功，83 个 JUnit 测试零失败；真实客户端日志 `in-place-delivery-gametest3.log`。

- 清空全部站点并保存配置后，执行能力仍可接单；执行器不要求交付站点。
- 随后将旧交付平台设置到 (10000,70,10000)，实际完成石头 13、铁锭 9 的取货、嵌套源盒归还和原地 GCA 交付。观察实际假人与机器人都在保存的召唤坐标附近，旧平台未被使用；重新召唤假人核对散装物品数量正确。标记 `SUPERWY_IN_PLACE_DELIVERY_OK`。
- 召唤使用取货结束时机器人的精确 x/y/z，不先前往固定平台；坐标、维度和 `spawnMode=in_place` 在发送命令前持久保存。
- 单空槽 80 件分批取货、每批原地交付、多人 FIFO、历史库存、满背包立即取消并继续下一单、500 秒执行超时和点击中途取消完整回归通过。
- Web 移除交付平台表单，显示原地交付说明；JavaScript 语法及静态元素引用检查通过。请求协议 v1 保留，旧 `stationId` 字段接收但不用于定位，玩家取货端未修改。

所有游戏验证使用隔离本地世界，没有连接用户服务器或修改用户游戏实例。后续右键 GCA 放入、服务端 NBT 核对以及 `/player <名称> kill` 下线保存流程继续使用真实依赖。

以下为历史版本记录。

---

# 0.3.7 立即取消与 500 秒执行超时

2026-10-25，Minecraft 26.1.2 / Java 25.0.2 / Fabric Loader 0.19.5。

`gradlew test build runClientGameTest` 完整通过，83 个 JUnit 测试零失败。日志 `immediate-cancel-final-gametest.log`。

- HTTP 取消返回 200 和已持久保存的 `cancelled`，幂等重放仍返回 200；下一单排队位置立即前移。普通玩家仍不能取消其他玩家订单。
- 满假人订单已取货并等待时取消，剩余物品保留在机器人背包，假人保持在线，不再进行归还或下线；已入队的下一单实际完成取货、GCA 放入、收货核对和下线。标记 `SUPERWY_DIRECT_FULL_CANCEL_OK`。
- 等待缺货时模拟单调时钟已运行 501 秒，实际触发执行器 watchdog，以 `failed / ORDER_TIMEOUT` 结束本单；后续订单正常交付。排队订单取消时没有启动执行。标记 `SUPERWY_FORCE_TIMEOUT_OK`。测试通过推进本单单调时钟起点验证超时，没有实际等待 500 秒。
- 在真实槽位点击进行中取消，随后 45 tick 没有额外点击、移动或取消状态回写。光标和物品原样保留，下一单为 `waiting_inventory / CURSOR_NOT_EMPTY`；不会操作残留光标或连锁失败。标记 `SUPERWY_MID_CLICK_CANCEL_OK`。
- JUnit 覆盖精确 500 秒边界、取消只中断本单等待和 NBT 查询、旧回调无法借用下一单上下文、取消及超时后重启仍保留货物和交付数量且不冻结后续队列。
- 前台/后台转向、32 秒手动移动后恢复、跳跃、半砖、10 格交互、头顶遮挡箱与潜影盒直接发包、持久扫描和失败跳过、GCA 交付、源盒借还、多人 FIFO、历史库存、80 件分批交付完整回归。

测试使用隔离本地集成服务器，未修改用户游戏实例。为排除 Mojang 身份查询网络抖动，仅给测试世界的离线假人预置正常身份缓存；Carpet 召唤、GCA 菜单、物品点击与服务端 NBT 核对均由真实依赖执行，测试代码及依赖不进入发布 jar。玩家取货端未改动。

以下为历史版本记录。

---

# 0.3.6 头顶和遮挡容器数据包交互

2026-10-25，Minecraft 26.1.2 / Java 25.0.2 / Fabric Loader 0.19.5。

- `gradlew test build runClientGameTest` 成功，77 个 JUnit 测试零失败；完整真实客户端日志为 `overhead-full-gametest.log`。
- 在玩家头顶放置箱子，并在中间放实心方块阻断射线。断言普通视线命中失败后，直接指定目标坐标和底面发送交互包，服务器打开正确库存，石头数量为 7。玩家位置、镜头和手持黑曜石数量不变。
- 同一位置换为潜影盒并保留遮挡，服务器打开正确菜单且显示铁锭 5。标记 `SUPERWY_OVERHEAD_PACKET_OK`。
- 箱盖上方放实心方块使服务器拒绝打开，操作明确以 `OPEN_CONTAINER_TIMEOUT` 失败，没有把发包当作成功；移除阻挡后下一次交互正常，交互序号保持可用。
- 10 格可交互目标原地开箱、原版服务端拒绝超距后靠近重试均通过。前台/后台转弯、32 秒手动输入后恢复、跳跃与半砖、扫描断点与失败跳过完整回归。
- GCA 散装交付、源潜影盒借还、个人物品保留、多人 FIFO、历史库存取货、80 件单空槽分批交付、满假人等待及取消保留货物均通过。

数据包只针对已加载的容器、世界边界内且距离不超过 10 格的目标；使用原版选中槽同步和递增交互序号，等待实际菜单确认后才取物品。测试使用隔离本地世界，没有连接用户服务器或修改用户游戏实例。

以下为历史版本记录。

---

# 0.3.5 GCA 散装取货与持久缓存

2026-10-25，Minecraft 26.1.2 / Java 25.0.2 / Loader 0.19.5 / Fabric API 0.155.3+26.1.2 / Baritone API Fabric 1.18.0。

`gradlew test build runClientGameTest -PdirectDeliveryTest` 成功，新增动态背包、GCA 背包映射和货源优先级测试。记录 `direct-delivery-iteration3.log`。

同时加载 GCA、Baritone、QuickShulker 和 Carpet 后，真实客户端测试完整成功；Baritone 转向补丁仍可单独组合验证。

- 扫描运行中真实按键移动不被拦截；普通聊天输入 `/superwy stop` 成功停止执行，恢复后可继续任务。
- 前台/后台两转角实走、跳跃、缺口、10 格交互及扫描断点回归通过；持续按 W 32 秒沿镜头方向移动并抵住墙，路径和目标不被超时/卡住保护取消，松开后到达原目的地，标记 `SUPERWY_SHARED_PATH_INPUT_OK`。
- 安全箱整理、空盒领取、装箱和投掷流程已停用；交付只需配置 delivery 站点。
- 地面潜影盒优先于近处普通容器，嵌套潜影盒通过 QuickShulker 借读并归还；取货后使用 GCA `openFakePlayerInventory` 右键打开假人背包，散装写入 18–53 槽并核对实体 NBT，标记 `SUPERWY_DIRECT_GCA_OK`。
- 单空闲槽取 80 件物品时自动分批召唤、交付和 kill，已有个人物品不移动，标记 `SUPERWY_DIRECT_BATCHES_OK`。
- 空潜影盒省略 `Items` 字段时保存为空快照，旧数量被清除；满收货人等待、取消后货物保留机器人背包并进入恢复核对，标记 `SUPERWY_DIRECT_FULL_CANCEL_OK`。
- JUnit 覆盖升级旧 CLEAR_MANAGED_INVENTORY_FIRST 误阻塞恢复排队；有事务日志、已取物或召唤记录的订单保留恢复状态；纯等待重启后保留 FIFO 顺序。
- 多人 FIFO、扫描与订单交错、历史库存取货、库存无时间过期和一小时扫描策略回归通过。

所有游戏验证使用隔离本地集成服务器，不连接用户服务器、修改用户游戏实例。玩家取货端未改动；事务未核实的结果不自动重放。

以下为历史版本记录，旧输入锁和强制可见转向的描述不再代表当前行为。

---

# 0.3.3 持久库存、每小时更新与历史库存取货验证

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build runClientGameTest` 通过：73 个 JUnit 测试无失败，真实本地 Minecraft 客户端游戏回归通过。
- SQLite 关闭重开后，容器快照、潜影盒内物品数量和实际 `observedAt` 时间戳不变。再次保存同一来源后只保留更新的数量与时间戳，没有重复累加；一年以前的完整快照仍可用，明确失效的快照仍不能预留。
- 每小时调度使用持久化的完成时间戳 + 3600 秒；验证重启后到期时间不变、未完成任务立即续扫、旧版间隔迁移保留失败记忆与未扫描位置，以及迁移不重复覆盖后续自定义间隔。
- 扫描开关独立写入配置，重开配置后保留；执行器仍可启用。游戏内在后台扫描运行时通过实际 HUD 输入 `/superwy scan off`，确认配置保存关闭、接单能力保持开启。
- 游戏测试把库存时间戳设为 30 天前，确认 API 可用数量仍大于零且原时间戳没有被改成当前时间，再通过 HTTP 下单并实际完成取货、装箱、指定假人接收及下线保存。缺货订单等待期间也未重新开启扫描。日志标记 `SUPERWY_HISTORY_MODE_OK`。
- 扫描停止接口改为持久关闭；游戏回归显式重新开启后，从原检查点继续扫描，已确认快照时间戳不变。前台/后台转弯、跳跃、缺口与半砖、10 格开箱和服务端拒绝后靠近、失败容器跳过、扫描期间取货、满背包保护和双主体 FIFO 交付一并通过。
- HTTP 测试确认未就绪时返回具体原因、中文操作提示和全部阻挡项，不创建订单；已接受订单在机器人离线后仍可使用原幂等键重放。Web JavaScript 语法和 OpenAPI JSON 解析检查通过。
- 测试只连接隔离的本地集成服务器，HTTP 使用临时端口；没有连接用户远程服务器。玩家取货端由另一开发任务维护，本版仅发布机器人端。

以下为旧版本历史记录。

---

# 0.3.2 路径移动执行、跳跃与容器 reach 验证

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2 / Baritone API Fabric 1.18.0。

- `gradlew test build runClientGameTest` 实际通过：63 个 JUnit 测试全部通过，真实本地 Minecraft 客户端回归成功。
- 使用仅存在于 gametest 源集的冲突 Mixin，在 Baritone 普通移动钩子之后、每次物理移动之前，把玩家 yaw 强制改成固定 180 度。机器人仍在三格高墙体围成的通道内完成两个拐弯；前台、后台各验证一次，断言实际经过两个方向的通道并到达目标。断言冲突写入和 SuperWY 实际移动转向桥接均执行超过 10 次。日志标记 `SUPERWY_FOREGROUND_TURN_OK`、`SUPERWY_BACKGROUND_TURN_OK`。
- 上述两次寻路前均通过 API 执行 Baritone `pause`，验证新寻路会清除残留暂停，而非只生成路径渲染。
- 机器人实际起跳并落到一格高平台，验证离地和最终高度；封闭单格通道设置一个必须跳过的地板缺口，验证跨越时处于空中并完成路径。标记 `SUPERWY_JUMP_OK`、`SUPERWY_GAP_SLAB_OK`。
- 在底半砖上落脚，验证实际脚部高度 70.5 能满足 Baritone 的目标高度 71，避免使用普通 blockPosition 造成到达后超时。
- 本地服务器将玩家交互属性设为 10，机器人在距容器命中面约 9.56 格处原地打开木桶，断言没有走近。随后恢复默认 4.5，验证同一远处右键被拒绝后，机器人自动靠近并成功打开菜单。标记 `SUPERWY_REACH_OK`。没有绕过服务端距离或容器权限检查。
- 保留并回归任务输入锁、手动 stop 交还控制、持久扫描恢复、特殊容器失败跳过、扫描期间取货、嵌套潜影盒取物归还、装箱交付、收货后重新召回核对、满背包阻挡、安全取消和双主体 FIFO 排队。
- `capabilities.modVersion` 与 Web 管理区域可确认实际加载版本；管理状态的 `navigation` 提供目标、实际位置、最大离起点距离、转向应用次数、重规划及错误诊断。
- 仅在隔离本地集成服务器测试，没有连接用户远程服务器。注入的固定朝向冲突用于验证针对性修复，不代表已经确认用户服务器上的具体冲突模组。冲突测试类与测试 Mixin 不进入发布 jar。

以下为旧版本历史记录。

---

# 0.3.1 任务独占控制与失焦转弯验证

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build runClientGameTest` 实际通过：63 个 JUnit 测试全部通过，真实 Minecraft 客户端游戏测试成功。
- 扫描 64 个容器时模拟 W、Q、E、右键、滚轮及鼠标位移，确认位置、视角及界面不受人工输入影响，任务也不会被移动键自动暂停。在 HUD 输入 `/superwy stop` 后停止并交还控制，验证 W 可以正常移动，停止后的遗留自动移动请求被拒绝。标记 `SUPERWY_CONTROL_OWNERSHIP_OK`；截图 `docs/control-stop-command.png`。
- 在三格高石墙围成的转折通道内测试 Baritone。窗口焦点为 false、鼠标释放、失焦自动暂停选项开启，同时预先打开暂停菜单。机器人自动退出后台暂停菜单，通过两个拐弯到达目标，实际 yaw 改变；寻路期间没有重新获取焦点、抓取鼠标或被自动暂停。Baritone 的三项视角设置在结束后恢复原值。标记 `SUPERWY_BACKGROUND_TURN_OK`。
- 物理鼠标输入清零时保留转向处理流程；切回窗口时禁止原生按键轮询绕过任务输入锁。任务结束后保留游戏原有空闲/失焦行为，不全局修改焦点或暂停选项。
- 原有持久断点扫描、特殊容器失败跳过、扫描期间订单优先、缺货继续扫描、潜影盒借还与装箱、假人库存核对及下线保存、满背包阻挡、安全取消和多人 FIFO 交付测试一并回归。
- 测试只连接隔离的本地集成服务器，使用随机 HTTP 端口，没有连接用户远程服务器。仅修改和发布机器人端，玩家取货端由其他开发任务维护。

以下为旧版本历史记录。

---

# 0.3.0 多人 FIFO 排队验证

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build` 实际成功，56 个 JUnit 测试全部通过。
- 新增并发入队测试：24 个不同主体并发提交，得到唯一、连续的 SQLite 入队编号，重开数据库后顺序不变。
- 验证时间戳相同或时钟回退不影响排序，幂等重放不消耗位置/编号，结束或取消后位置推进，缺货队首继续占位，世界和维度隔离、旧数据库编号迁移及单主体 5 单限制。
- HTTP 验证队列授权、前方单数、普通用户仅查看自己的订单、机器人切换世界后仍保留已接受的订单、队首完成后的队列推进。
- `gradlew runClientGameTest` 实际成功。使用真实 Minecraft 客户端、Baritone、QuickShulker 和 Carpet：两个独立 API 主体同时提交石头 3 个与铁锭 4 个订单，按返回的 queueSequence 确认先后，断言前一单完成前后一单不执行取物。
- 两单完成后重新召回各自假人检查潜影盒：分别只有自己请求的物品和正确数量，没有串货。队列接口没有泄露另一个主体的订单。日志标记 `SUPERWY_MULTI_PLAYER_FIFO_OK`。
- 原来的扫描失败跳过、扫描期间订单优先、缺货时继续扫描、断点恢复、嵌套取物、满背包阻挡和安全取消测试一并通过。
- 客户端游戏测试使用独立临时 HTTP 端口，不连接或操作用户远程服务器。本版只发布机器人服务端；玩家取货端由另一个开发任务维护。

以下为旧版本历史记录。

---

# 0.2.1 验证记录

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build runClientGameTest` 实际通过，45 个 JUnit 测试全部通过。
- 新增旧版 80 个失败项迁移测试：保留为跳过记录，待扫描队列归零；失败记忆关闭/重开数据库及下一轮仍保留，普通未完成位置仍能续扫。
- 结束扫描时区域时间与检查点在同一事务保存，测试确认随后删除区域不会被延迟写入重新创建。
- 真实 Minecraft 测试放置不支持的特殊容器（饰纹陶罐）和普通木桶：失败容器跳过，本轮结束为 complete_with_skips，剩余为 0。将陶罐替换为正常木桶后开始下一轮，原失败位置仍不自动重试；手动清除失败记忆后才读取该位置。标记 `SUPERWY_SKIP_FAILURES_OK`。
- 在 64 个容器后台扫描未完成时通过 HTTP 提交石头与铁锭订单，断言扫描显示 paused_for_order 且剩余队列非空，同时订单实际完成取物、潜影盒装箱、指定 Carpet 假人接收及下线。标记 `SUPERWY_SCAN_ORDER_PRIORITY_OK`。
- 缺货订单进入 waiting_stock 时断言后台读取计数增加；取消订单成功。标记 `SUPERWY_WAITING_STOCK_SCAN_OK`。
- 原断点恢复、嵌套潜影盒归还、交付后召回核实库存、满背包阻挡和安全取消均通过。

本次没有连接用户远程服务器；客户端截图确认旧版处于 partial，但不能仅凭截图判断每个失败项的具体 NBT/路径错误。新版可通过 `/superwy failed <页>` 查看记录。以下保留旧版本验证历史，其中的失败重试与完整扫描门槛已由 0.2.1 替代。

---

# 0.2.0 验证记录

日期：2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build runClientGameTest` 实际通过；40 个 JUnit 测试全部通过。
- 新增 6 个断点测试：负坐标区块遍历、SQLite 关闭重开恢复下一个位置、读取检查点不提前确认、失败容器/区块保留重试、世界/维度/边界变更隔离、删除区域同时删除检查点。
- 在真实集成世界生成 16 个木桶，读取部分后停止，清空 Scanner 内存状态，从数据库恢复，完成剩余容器。原已确认快照的 observedAt 保持不变，最终成功数量为 16，证明没有从头重扫。
- 截图 `docs/scan-yellow-markers.png` 为真实运行截图：黄色线框、13 个待扫描容器、最近坐标及暂停状态可见。
- 在该版本重跑潜影盒内取物、借盒归还、输出装箱、Carpet 指定假人接收、kill 下线、再次召回验证库存、满背包阻挡及安全取消均通过。日志标记 `SUPERWY_SCAN_RESUME_OK`、`SUPERWY_GAME_TEST_OK`。
- 与玩家端对应 API 仍采用协议 v1；未连接用户远程服务器。区块尚未发现时只能显示待探索区块数；不能凭空获知其中容器坐标。

下文保留此前版本的验证细节。

---

# 0.1.0 验证记录

日期：2026-10-25（Asia/Hong_Kong）。以下为本机实际执行结果，不代表已经在用户的远程生产仓库验收。

## 运行矩阵

| 项目 | 实测版本 |
| --- | --- |
| 系统 | Windows 11 x64 |
| Java | Oracle JDK 25.0.2 |
| Minecraft | 26.1.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.155.3+26.1.2 |
| Fabric Loom / Gradle | 1.17.21 / 9.5.1 |
| SuperWY | 0.1.0 |
| SQLite JDBC | 3.50.3.0，嵌入发布 jar |
| Baritone | baritone-api-fabric-1.18.0.jar |
| QuickShulker | 工作区的 quickshulker-v3.2.4-mc26.1.1.jar |
| Carpet | fabric-carpet-26.1+v260402.jar |

第三方测试文件 SHA-256：

~~~text
Baritone: BEEC850E73591AB1DC81CB359D1D224EF3230A35D21DC6E72F8E8D287E7B79D2
QuickShulker: 88389C558C4F6902860EF595157150BEC2C6E4932C333E66C19CB394ADA0705A
Carpet: 59BD225D12423A7D7A635CA0C94FA786F97CCEBB116922B16D76072DA4EE67E7
~~~

QuickShulker 样本文件名写 26.1.1，但实际在上述 26.1.2 测试环境中通过共同加载及取物装箱测试。不能据此替其他来源、同名或其他版本的 jar 作兼容承诺。

## 自动化测试

运行 .\gradlew.bat test build：34 项测试通过，0 failures、0 errors。

- CoreTest：20 项，覆盖严格请求解析、名称授权、组件匹配、数量边界、区域边界、嵌套来源、维度隔离、陈旧缓存、加工容器策略、动态容量，以及凭证与恢复槽配置检查。
- StoreTest：6 项，覆盖并发幂等、冲突、离线重放、世界绑定、重启保留队列/冻结中断订单，以及空箱替换和重复扫描不累加。
- HttpTest：8 项，覆盖认证、幂等、本人订单隔离、管理员权限、跨 Origin 拒绝、静态资源、错误请求、请求大小、名称和 scope。

报告：build/reports/tests/test/index.html；机器报告：build/test-results/test/。

## 实际客户端启动

runSmokeClient 已通过：Fabric mod 入口、Mixin 加载、SQLite 初始化、HTTP 监听、资源加载和退出流程成功。另在 Baritone + QuickShulker 同时安装的开发实例上通过启动检查。

日志成功标记：SUPERWY_SMOKE_OK。没有登录用户的服务器，使用独立开发目录。

## 实际本地仓库端到端测试

runClientGameTest 使用 Fabric Client GameTest 创建临时本地平坦世界，客户端、集成服务器和真实依赖一起运行。所有操作通过实际 HTTP 接口提交，由客户端执行，不在服务端直接替机器人完成取货或交付。

场景 A：

1. 供应容器装空潜影盒；货源木桶有 64 石头，另有内含 21 铁锭的潜影盒。
2. 框选货源区域，NBT 扫描完成，缓存包含外层和盒内来源树。
3. 重复扫描后石头库存仍是 64。
4. 提交清单：石头 13、铁锭 9；收货假人名称 SW_delivery。
5. 自动取空盒、取石头、借出源盒、免放置开盒取铁锭、归还源盒、装箱。
6. 核实 SW_delivery 收到包裹，再令它下线。
7. 服务端独立检查货源石头剩 51，归还的源盒铁锭剩 12。
8. 重新召唤假人，检查背包恰有 1 个包裹，内含石头 13、铁锭 9，没有额外物品。

场景 B：

1. 预先将另一个假人 SW_full 的 36 个常规槽装满泥土并下线保存。
2. 提交石头 1 的请求，指定 SW_full。
3. 订单进入 waiting_recipient，fetched=0；没有先拿货或往地面投包裹。
4. 提交取消，订单安全进入 cancelled。
5. 独立检查货源石头仍为 51。

成功标记：

~~~text
SUPERWY_GAME_TEST_OK
SUPERWY_GAME_TEST_CAPACITY_OK
BUILD SUCCESSFUL
~~~

验证中修复了过早判定移动到达导致交付站位不足的问题：现在要求到达目标方块并落地，交付退让约 3 格、检查最小距离，并调整投掷方向。未确认收货时仍保持不确定状态，不会用超时直接当成功。

## Web 验证

浏览器检查了登录、运行概览、清单下单页面和刷新状态。离线 UI 测试服务返回 CAPABILITY_UNAVAILABLE 时，页面正确拒绝提交并保留幂等键。截图 docs/web-preview.png 为明确标记 UI_PREVIEW_ONLY 的离线界面，不能当作连接生产仓库的截图。

## 尚未覆盖的运行条件

- 未连接用户的远程服务器，未在真实仓库地形、延迟、漏斗并发和第三方拾取竞争下完成验收。
- 尚未对其他 26.1.x 小版本作兼容验证，发布元数据目前只允许 26.1.2。
- 初期只支持同维度工作站；不自动穿越传送门、合成补货或处理容器矿车。
- 双箱已做两半边界检查与同步失效/刷新，但端到端夹具使用木桶；复杂双箱、异形地形和特殊服务器保护规则仍需实服验证。
- 在途事务中断采用保守冻结和人工核对；未实现自动重放不确定的取放动作。
- 收货站必须由管理员设置为平整、无漏斗、无火、足够退让且没有无关掉落物的区域。大订单按盒分批，假人背包未释放时等待。
- 当前 Web 为普通 HTML/CSS/JavaScript，状态使用轮询；SSE、玩家专用 Fabric 请求 UI、语音和投影清单联动留作后续接入。
- SQLite 和组件快照已经落盘；索引缓存当前按十分钟过期判断。扫描触发间隔可配置，但这不保证所有区域都能在周期内走完。
