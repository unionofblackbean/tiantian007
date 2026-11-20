# 0.5.6 / 0.5.0 圈投影取货（Windows 本地验收完成，普通窗口人工性能复测待补）

2026-10-29，Windows，Minecraft 26.1.2 / JDK 25.0.2，仅本地隔离世界。两端最终 `test build` 通过，取货端 50 项、机器人端 171 项 JUnit，失败及错误均为 0。版本号未变。

- 旧功能：取货端 `test build runClientGameTest` 通过（`projection-requester-regression.log`，111 秒）；机器人带取货端 0.5.0 jar 的 batchPickupTest、batchSkipTest、packingTest、parcelCapTest、taskQueueTest、deliveryQueueTest、directDeliveryTest 与默认完整场景全部通过。各场景命令、耗时、日志和 SUPERWY 标记见完整报告。
- 新入口 `runClientGameTest -PprojectionParcelsTest`。机器人带 `-PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.5.0.jar` 的完整日志：`../superwy-fabric/projection-parcels-final.log`，426.8 秒，`SUPERWY_PROJECTION_PARCELS_OK`。真实双端私聊、QuickShulker、GCA 和下线重召背包核对通过：三盒 27/27/7 格与 ParcelPlan 逐槽一致，中途保留未满盒，散装空盒最后交付，73 个空盒在 sf/sf_1/sf_2 中分别为 36/36/1，16/1 堆叠，部分缺货及普通单项/批量不强制装盒。
- 取货端日志：`../superwy-requester/projection-requester-scale-final.log`，`SUPERWY_PROJECTION_REQUESTER_OK`、`SUPERWY_PROJECTION_H_OK`、`SUPERWY_PROJECTION_I_OK`。实际简易区域编辑器按钮、四处库存的需要/背包/缺少三列、已放置扣除、全部数量、行排除、预估、9×3 悬停、真实发包、模拟旧机器人更新提示和两种超限均通过。
- GUI 缩放 2/3/4 逐一断言实际生效，854 宽度通过。128×128×64、16 种实际投影方块统计耗时 366.4 ms，采样最低 FPS=111；扣除已放置的 1 块后计数 1048575。截图及可重载小投影在 `../superwy-requester/verification/projection/`。性能来自真实客户端 GameTest；普通 runClient 已进入本地创造世界并加载真实投影，后续界面操作因用户按 Esc 停止 Computer Use，未另做普通窗口同场 FPS 复测。
- 发现并修复 `OrderEngine.java:574` 的投影交付顺序缺陷：石头 1 + 空盒 2 原本把两个空盒先交付。先补稳定失败的实际背包测试，再最小修复为无后续可装箱材料时先交付最后材料盒。失败日志 `projection-recheck-projectionParcelsTest.log` 保留，修复后小场景与完整场景通过；普通订单路径未改。另修正旧 GameTest 对已移除字段的引用、区域就绪竞态，并让大型投影使用现有 packingTest 等级的等待预算，未放宽物品断言。
- 备份在 `../verification/projection-backup-20261029-192154/`，旧 jar 和日志未删除。没有连接真实服务器或操作 FRP、私有打包脚本、PRIVATE_MESSAGES.md 和真实凭据。未测生产服组合、所有分辨率和非简易编辑器；真实服验收及 FRP 私有 0.5.6 重新打包仍由人工完成。

完整报告：[圈投影验收报告](../verification/projection-test-report.md)。最终构建日志：各项目的 `projection-build-verified.log`。

# 0.4.2 响应体超时与断线释放

2026-10-29，Java 25。

- `test build` 通过，42 项 JUnit 全部通过，日志 `memory-final.log`，结果位于 `build-memory-final/test-results/test`。
- 新增真实 HTTP 服务器发送响应头和 1 字节后停住的测试，确认完整响应 10 秒截止时间触发、底层请求取消，后续请求成功。仅使用 `HttpRequest.timeout` 时此测试会卡住，现已修复。
- 新增断线取消所有未完成网络任务、取消后继续请求、慢响应时关闭在 2 秒内返回的测试。关闭后提交立即失败。原有响应过大/无效 JSON、各种 HTTP 错误、超时、重定向与独立提交回归全部通过。
- 响应上限 256 KiB，网络并发上限 32；取消的截止时间任务立即从调度器队列移除。任务跟踪上限 128，终态任务原有 15 秒回收逻辑保留。
- 本轮未重新运行取货端图形界面 GameTest，也未进行长期真实服务器堆采样。

# 0.4.1 草稿点击编辑与单行取货状态

2026-10-28。

- `test build runClientGameTest` 通过，日志 `draft-editor-release-gametest.log`。39 项 JUnit 通过，包含零/较小容量回复保留草稿、128 项限制、删除中间项后保序及释放数量额度、禁止编辑已提交清单、单行排队/执行/完成状态。
- 真实 Minecraft 按 T 打开原版 ChatScreen，实际鼠标左键点击删除指定行、翻到第二页删除、翻回第一页以及取消整个草稿全部通过；取消后聊天栏保持打开。原队列和自定义键鼠提交流程回归通过。标记 `SUPERWY_REQUESTER_DRAFT_EDITOR_OK`。
- 取货端不再根据容量回复限制、清空草稿或阻止提交；机器人现有打包和执行空间管理继续负责实际取物。
- 已目视核对 `docs/requester-draft-editor.png`、`docs/requester-active-line.png` 与 `docs/requester-queued-line.png`：未提交清单包含每行删除、翻页和取消整单；提交后的执行与排队仅有右下角单行文字，没有背景面板和材料列表。
- 批量普通进度按已完整取得的材料项数计数，跳过项不计入已取；装盒进度收到 `parcels` 回报后使用已取件数/总件数。多任务优先展示执行中的一张，否则显示最近将执行的一张。
- Litematica 投影、数量、实际移动关闭、HTTP 连续独立请求、系统私聊回执、原版按键绑定和自定义按键均回归通过。仅需替换玩家取货端；搭配现有机器人 0.5.0/0.5.1，无需重装机器人。

# 0.4.0 大清单、装盒进度与新版机器人兼容

2026-10-28。

- test build：36 项 JUnit 通过，包含百万数量输入、保持普通单项 64 上限、128 行清单及旧按键/队列验证。
- runClientGameTest 全部通过（packing-requester-gametest.log，BUILD SUCCESSFUL）：真实 Litematica 投影、原版按键界面、键鼠改绑及重置、配置同步、HTTP/私聊单项、数量窗与移动、连续请求。
- 搭配机器人 0.5.0 的真实私聊 3840 件、60 格任务完成装盒交付，右下角显示已取件数和携盒数。机器人日志 packing-final-gametest.log。
- 混合任务排队、队列数量推进、逐项缺货/界面异常跳过回归均通过，详见机器人 VERIFICATION.md。需要同时更新两端。

# 0.3.7 右下角混合任务队列与前方数量

2026-10-28。

- `test build`：35 项 JUnit 测试通过。新增单独任务队列数推进、终态不能被迟到通知恢复为排队、多批状态独立，以及 HTTP queue.ahead 与旧 position 的解析验证。
- 与机器人 0.4.19 的混合队列真实客户端测试通过：`../superwy-fabric/task-queue-final-gametest.log`（`SUPERWY_TASK_QUEUE_OK`）。活动首批时继续提交私聊单项、HTTP 单项及第二批，四张任务同时显示；前方数量 1/2/3，取消中间项后更新 1/2，随后正确 FIFO 交付。
- 第一批的进度在第二批草稿创建和提交后仍正常完成；新批量繁忙时先编辑并入队，执行时由机器人复核容量。私聊通知和 HTTP 查询各自使用提交时保存的身份，不因后来修改配置而查询到其他凭据。
- 完整 `test build runClientGameTest` 回归通过（`task-queue-requester-gametest.log`，`BUILD SUCCESSFUL in 49s`）。原版键鼠改绑、取消绑定、重置、options 重载、取货设置同步、投影输入与移动、连续请求、系统私聊回执均保留。
- 已目视检查真实四任务截图 `docs/requester-task-queue.png`，右下角各排队项清晰显示“队列中，前方还有 X 个任务”。需要机器人同步升级至 0.4.19；未连接生产服。

# 0.3.6 原版按键绑定页面注册与配置同步

2026-10-28。

- `test build runClientGameTest` 通过（`native-keys-gametest3.log`，`BUILD SUCCESSFUL in 31s`）。32 项 JUnit 测试通过，包含旧配置按键迁移数据、取消绑定、扫描码编码和原版临时冲突可持久保存但取货设置仍拒绝重复键。
- 在真实 Minecraft 原版 KeyBindsScreen 中确认三个 KeyMapping 及中文分类“SuperWY 取货端”可见；实际点击原版改键按钮，绑定键盘 J 取货、鼠标 5 确认、键盘 K 加队列，配置文件同步为 74、-5、75。Esc 取消绑定和三个逐项重置均核对默认值；options.txt 保存后清空运行时映射再调用原版 Options.load，绑定正确恢复。
- 实际 J 读取 Litematica 投影、鼠标 5 提交、K 将材料加入批量队列均通过；重新打开 /swrequest config 显示原版编辑后的 J。原自定义设置键鼠捕获、投影数量与移动、连续 HTTP 下单、系统私聊受理回执均继续通过。
- 中文原版页面截图 `docs/requester-native-bindings.png` 已目视检查，三个动作、当前键值及重置按钮均完整可见。标记 `SUPERWY_REQUESTER_NATIVE_BINDINGS_OK`。
- 仅更新收货端，机器人无需更换。没有连接远程服务器。

# 0.3.5 批量跳过标记和部分交付进度

2026-10-28。

- `test build`：31 项 JUnit 测试通过；新增跳过通知的行号和原因格式校验、重复通知不增加跳过数、无效通知拒绝以及含跳过项的完成进度。
- 与机器人 0.4.18 同时加载的真实客户端测试通过，详见机器人 `batch-skip-gametest3.log`（`SUPERWY_BATCH_SKIP_OK`）。跳过消息经真实 `/tell` 回传，HUD 已处理数包含跳过项，显示跳过数和 `[跳过]`；部分交付与全部无货均正确结束，未误显示一直取货中。
- 真实缺货、取到部分后缺货、点击前后界面关闭、源潜影盒归还后继续下一项及全部无货均覆盖。普通单项订单的失败行为保持原样。双方必须同时更新以识别新结束状态。

- 原有订单回归 `test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.5.jar` 通过（机器人 `batch-skip-regression.log`，`BUILD SUCCESSFUL in 3m 19s`）：普通失败、原地 GCA、源潜影盒借还、多人 FIFO、历史库存、连接弹窗、原生与系统私聊、80 物品单空槽分批、满收货人、立即取消及模拟 501 秒超时均通过。

# 0.3.4 自定义快捷键与右下角批量清单

2026-10-27。

- `test build`：30 项 JUnit 测试通过；新增旧配置自动采用默认按键、键鼠绑定持久化、冲突拒绝、实际堆叠槽容量约束、容量迟到时清空超额临时选择，以及进度总行数校验和禁止倒退。
- `runClientGameTest` 通过（`batch-gametest.log`，`BUILD SUCCESSFUL in 26s`）。真实设置按钮捕获键盘 G 取货、鼠标 4 确认、键盘 H 队列，保存文件再读取验证；真实 Litematica 投影材料与数量 2、3 分别加入清单，关闭数量窗后用鼠标 4 提交整批。标记 `SUPERWY_REQUESTER_CUSTOM_KEYS_QUEUE_OK`。
- 原中键投影、数量输入、HTTP 独立连续请求、HTTP 出错后继续请求、系统私聊回执身份匹配和实际移动一秒自动关闭继续通过，标记 `SUPERWY_REQUESTER_GAME_TEST_OK`。
- 两个真实 Mod 的完整批量交付通过机器人项目 `batch-final-gametest.log` 验证，包括空槽容量、编辑/执行拒单、三行私聊清单、0/3 → 3/3 进度、取齐后统一召唤交付、既有物品保留、结束和放弃草稿后恢复接单。截图 `docs/requester-hotkeys.png`、`docs/requester-queue.png` 已目视检查，854×480 小窗口中保存按钮完整可见。
- 批量私聊不自动重试，普通单项 HTTP 行为保留；实际服务器仍需 NBT 查询、Carpet/GCA 权限和同服机器人名，生产服未连接验收。

- 原有交付回归 `test build runClientGameTest -PdirectDeliveryTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.4.jar` 通过（机器人 `batch-regression-gametest.log`，`BUILD SUCCESSFUL in 3m 13s`）：多区控制、遮挡容器交互、GCA 原地交付、QuickShulker 借还、多玩家 FIFO、历史库存、连接弹窗、原生/系统私聊往返、单空槽 80 物品分批、收货人满背包、立即取消和模拟 501 秒超时均通过。

# 0.3.3 连接弹窗与收货名配置

2026-10-26。

- 完整隔离 `runClientGameTest -PrequesterJar=../superwy-requester/build/libs/superwy-requester-0.3.3.jar` 回归通过，`connection-port-gametest.log` 显示 `BUILD SUCCESSFUL in 4m 27s`。覆盖新连接弹窗及原有寻路、扫描、多区控制、QuickShulker、HTTP/私聊交付、FIFO、分批、防溢出、立即取消、500 秒超时与轻量历史。

- `test build`：26 项 JUnit 测试通过。新增 swc2 默认 sf、确认前不落盘、选择和修改收货名的保存验证；保留旧 swc1 导入与物品偏好。
- 与机器人 0.4.8 同时加载的隔离游戏实际验证通过，日志位于机器人项目 `connection-port-gametest.log`，标记 `SUPERWY_CONNECTION_IMPORT_OK`：默认 sf 的连接弹窗、无效名称拒绝、取消不覆盖配置、确认后保存、HTTP 下单及 GCA 交付、同凭证改名后再次交付、重新连接弹窗。
- 已目视检查新弹窗截图 `docs/connection-recipient.png`。执行连接指令只准备草稿，点击“保存连接”才生效；无自动下单、队列轮询或重发。`/swrequest config`、`/swrequest recipient <名称>` 和重新导入连接均可继续使用。
- 新指令搭配机器人 0.4.8，玩家 Fabric API/Loader 依赖版本继续为 `*`；旧 swc1 的凭证名称限制保持不变。

# 0.3.2 一条指令导入 HTTP 连接

2026-10-26。

- `test build`：25 项 JUnit 测试通过。新增连接指令导入与落盘、HTTP 配置校验、保留物品数量偏好，以及非法指令不改变已有配置。
- 与机器人 0.4.7 同时加载的隔离 Minecraft 26.1.2 游戏测试验证：管理员实际生成连接指令，玩家游戏内执行 `/swrequest connect` 后保存独立玩家 token、地址、收货名与 HTTP 模式，随后真实 HTTP 下单并完成 GCA 收货核对。标记 `SUPERWY_CONNECTION_IMPORT_OK`，日志位于机器人项目 `frp-full-gametest2.log`。
- 指令不超过原版 256 字符限制，只在客户端解析，不通过聊天发给服务器。失败说明不回显凭证；不自动下单或重发。普通玩家 jar 不含 FRP 认证。
- 保留 Fabric API/Loader 依赖但不限制版本；MC 仍明确支持 26.1.2。

# 0.3.1 系统私聊回执与物品 ID 命令修复

2026-10-26。

- 与机器人共用新的系统私聊解码器，接收服务器发成系统包的标准中文/英文回执，以及包装过的原版翻译组件。
- 修复 `/swrequest item minecraft:stone` 和 `/swrequest request minecraft:stone 1` 对带冒号物品 ID 的解析，使用 Minecraft 的 IdentifierArgument。
- 23 项 JUnit 测试零失败；`gradlew test build runClientGameTest` 通过，日志 `system-whisper-requester-gametest2.log`。
- 实际输入手工 request 命令后，在服务器向客户端发送中文私聊系统包回执，成功清除待确认状态并显示已受理；带公屏作者前缀的仿造回执不生效。标记 `SUPERWY_REQUESTER_SYSTEM_RECEIPT_OK`。
- 设置窗口、HTTP 模式切换、真实 Litematica 投影提交、HTTP 错误后继续提交和移动关闭回归通过；机器人项目同时加载两端的完整私聊取货交付测试通过。

配置保持兼容；需要与机器人 0.4.1 一起更新，HTTP Bearer 验证和可选短 key 规则不变。

以下为历史版本记录。

---

# 0.3.0 默认私聊、游戏内设置与 HTTP 切换

2026-10-26，Minecraft 26.1.2 / JDK 25.0.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

- `gradlew test build`：23 个 JUnit 测试通过，包含默认 chat 且无 HTTP token、配置与可选 5 位 key 重启保留、名称/key 校验，以及原有独立 HTTP 请求和数量浮窗测试。
- `gradlew test build runClientGameTest` 真实游戏通过，记录 `whisper-requester-final-gametest.log`。`/swrequest config` 打开实际设置窗口，填写并保存接单机器人、收货假人、物品、数量、短 key 后，从磁盘核对字段。标记 `SUPERWY_REQUESTER_SETTINGS_OK`。
- 实际输入 `/swrequest mode http` 后配置保存为 HTTP，随后在真实 Litematica 投影中键取材，通过 HTTP 提交数量 12 的正确半砖物品。保持移动透传、实际移动一秒关闭、重复独立提交、HTTP 503 后继续请求及无队列轮询。标记 `SUPERWY_REQUESTER_HTTP_SWITCH_OK` 和 `SUPERWY_REQUESTER_GAME_TEST_OK`。
- 设置窗口截图 `docs/requester-settings.png` 为实际客户端截图，已目视检查字段、按钮完整显示，短 key 掩码可见；HTTP token 同样使用掩码。
- 机器人项目的两 mod 联合测试通过真实 `/tell` 完成玩家端请求、机器人受理回执和 GCA 原地交付，见机器人 `whisper-e2e-gametest.log` 的 `SUPERWY_REQUESTER_WHISPER_E2E_OK`。
- 玩家端没有本地订单队列、自动重发、排队查询或队列 HUD。只暂存待确认的私聊请求 ID；收到机器人匹配回执才显示受理，超时提示未确认。
- Fabric API / Loader 的依赖声明继续为 `*`；Minecraft 仍为 26.1.2，其他版本未作运行兼容承诺。测试只在隔离本地世界运行，没有连接用户服务器。

以下为历史版本记录。

---

# SuperWY Requester 0.2.0 验证记录

2026-10-25，Windows 11 / JDK 25.0.2 / Minecraft 26.1.2 / Fabric Loader 0.19.5 / Fabric API 0.155.3+26.1.2。

本次移除玩家客户端队列、持久待确认请求和提交锁后，`gradlew test build` 与 `gradlew runClientGameTest` 实际成功。日志：`independent-request-gametest.log`。

随后按用户要求将发布元数据中的 `fabricloader` 和 `fabric-api` 版本条件均改为 `*`，保留依赖本身。编译版本和上述运行实测环境不变。

## 自动测试

20 个 JUnit 测试全部通过：6 个数量与移动计时测试、14 个独立 HTTP 请求测试。覆盖：

- 数量及收货假人、每次提交使用独立幂等键，服务端返回 queued 即反馈提交成功。
- 前一条请求故意挂起时，后一条独立提交并先收到成功响应，无提交锁或本地串行等待。
- 302、400、401、403、404、409、413、422、429、500、503 后均可继续成功提交；重定向不跟随。
- 真实 10 秒 HTTP 超时、断开连接、无效 JSON、非对象 JSON、超大响应后，下一条请求仍可提交。
- 失败后新建会话或变更凭据均可继续提交；无本地待确认恢复流程。
- 发送前校验数量和物品 ID；成功响应必须包含订单 ID；手动状态查询与取消请求路径正确，非法订单 ID 被拒绝。

## 真实客户端

加载官方 Litematica 0.27.14、MaLiLib 0.28.12 及本 mod，创建本地集成世界与 Litematica 投影世界测试夹具（双层石半砖）。使用游戏的真实输入回调触发中键及数字键：

- 投影读取为 `minecraft:stone_slab` 材料；Litematica 无接口或 Mixin 冲突。
- 中键打开 HUD，mc.screen 保持 null；键入 1、2 得到 12。
- W 按键正常传递；截图 `docs/requester-quantity.png` 为真实客户端显示，不是网页示意。
- Enter 后本地 HTTP 夹具收到一行石半砖 12 个、假人名称 BuilderCargo；浮窗关闭。
- 再次打开浮窗，持续按 W 实际行走，浮窗自行关闭，玩家位置确实改变。
- 服务端保持 queued 状态时连续提交两条；第三条 HTTP 503 失败后，第四条仍提交成功。聊天明确反馈三次“请求提交成功”和一次“请求提交失败：HTTP 503”。
- 夹具监测 `/api/v1/queue`，整个流程调用次数为 0；客户端已移除队列 HUD、轮询、retry/queue 命令及待确认文件读写。
- 日志标记 `SUPERWY_REQUESTER_INDEPENDENT_REQUESTS_OK` 和 `SUPERWY_REQUESTER_GAME_TEST_OK`。

截图中的投影来自测试直接填入 Litematica 已加载投影世界；没有覆盖所有用户 .litematic 文件及渲染设置组合。HTTP 测试接收端是本机夹具，仓库端的真实取物及 Carpet 交付由相邻项目的独立客户端测试覆盖。本次未接入用户远程服务器。

## 依赖来源

- [Litematica 26.1.2 0.27.14](https://cdn.modrinth.com/data/bEpr0Arc/versions/w8P6TokG/litematica-fabric-26.1.2-0.27.14.jar)
- [MaLiLib 26.1.2 0.28.12](https://cdn.modrinth.com/data/GcWjdA9I/versions/FItuNokS/malilib-fabric-26.1.2-0.28.12.jar)

测试依赖仅在 test-libs，不嵌入发布包或源码归档。
