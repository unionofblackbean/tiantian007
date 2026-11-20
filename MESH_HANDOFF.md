# 超级调度模式（mesh）交接说明

日期：2026-10-29。状态：**代码完成，两端编译和单元测试通过（机器人 191 项、取货端 53 项），版本 0.6.0；已打包（含 private jar），未实服验收。** 日志：`verification/mesh-final-robot.log`、`verification/mesh-final-requester.log`。

## 一、任务目标

两端（机器人端 superwy-fabric、玩家端 superwy-requester）各增加“超级调度模式”，默认关闭，需要手动开启。

1. **调度机器人**：默认是 `tiantian996`，负责排队和派活，自己也参与取货。其他机器人是工人。
2. **自动连接**：
   - 看服务器玩家列表，判断调度机器人在不在线。
   - 工人私聊调度机器人握手（HMAC 签名），拿到它的 FRP 地址。
   - 之后工人通过签名 HTTP，每秒向它上报状态并领活。
   - 签名密钥由 private jar 内置的 FRP token 派生，所有机器人都要用 private jar。
3. **私聊**：玩家下单彻底不再用私聊。私聊只用于机器人之间握手。玩家端只走 HTTP（FRP）。
4. **调度规则**：
   - 简单任务（单项取货）优先，派给手上没有批量任务的空闲机器人。
   - 批量单全局排队，前方任务数通过 HTTP 订单状态返回给玩家。
   - 批量单按需切成小块派发：散装单每块不超过 25 格，装盒单每块默认 2 盒。
   - 插队只在一块做完时发生。
5. **掉线**：
   - 机器人掉线后等 2 分钟。超时没回来，就把它没交付的部分改派给其他机器人。
   - 掉线的机器人回来后继续原来的任务块；如果剩余部分已经改派，它只交付身上已经拿到的货。
6. **旧版玩家端**：
   - 旧版玩家端私聊下单，一律拒绝，并私聊提示更新取货端。
   - 超级模式下，玩家端没开超级模式就提交 HTTP 订单，会被拒绝（`MESH_MODE_REQUIRED`），并提示执行 `/swrequest mesh on`。
   - 工人收到玩家的 HTTP 请求，自动转发给调度机器人。
7. **共享资源**：
   - 收货假人锁：同名收货假人（含 `_1`、`_2`）同一时间只能由一台机器人召唤。
   - 箱子避让：选货源时尽量避开其他机器人正在开的箱子。
   - 空盒补充箱、存箱箱子：被别的机器人占用时，最多等 30 秒。
   - 工人不在公屏说话，由调度机器人统一播报缺货和“搞定了，我歇逼了”。

## 二、已完成（已写入文件，未编译）

### 共享协议 `shared/src/main/java/dev/superwy/protocol/`
- 新增 `MeshProtocol.java`：`swmesh1 hello / welcome / wait` 握手报文。
- `PrivateMessages.java`、`SystemWhisperFormat.java`：识别并隐藏 `swmesh1` 报文。

### 机器人端 `superwy-fabric/src/main/java/dev/superwy/`

**新增 `mesh/` 包**：
- `MeshSettings`：配置项 `enabled=false`、`authorityName=tiantian996`、`offlineAfterSeconds=15`、`reassignAfterSeconds=120`、`chunkSlots=25`、`parcelChunkBoxes=2`。
- `MeshKey`：从 `/superwy-private.json` 的 `authToken` 派生 HMAC 密钥；没有 private jar 就不能开启。
- `MeshScheduler`：纯逻辑调度器，便于单元测试。
  - 数据：Job（玩家任务）/ Chunk（任务块）/ 机器人状态。
  - heartbeat 回复：派活、取消、继续执行、遗忘、锁授权、需要避开的箱子。
  - tick：离线判定、2 分钟后改派。
  - 其他：排队位置、汇总进度。
- `MeshAuthority`：只在调度机器人上运行。
  - 把带 `meshParent` 标记的玩家订单转成调度任务。
  - 把汇总进度写回玩家订单（`meshAhead`、`meshRobots`、每行数量），完成时结束订单并公屏播报。
  - 玩家取消时停止相关任务块（`cancelled()`）。
- `MeshNode`：负责以下几件事。
  - 工人：每个任务块在本机建本地单；掉线后建续单，继承身上已有的货和盒子（`adopt`）；把本地进度上报给调度机器人。
  - 握手、签名 HTTP、向调度机器人转发玩家请求、`gate()`。
  - 实现 `MeshHooks`：收货假人锁、箱子避让。
  - `status()`、`setEnabled()`（还有任务块没做完时拒绝关闭）。
  - 状态保存在数据库 mesh 表：`worker`（本机任务块）、`authority`（调度状态）。
- `MeshHooks`：执行器调用的接口；没开超级模式时 `NONE` 什么都不做。

**修改的文件**：
- `core/Model.java`：
  - Request 新增 `meshParent`、`forcePacking`、`meshChunkId`。
  - Options 新增 `batch`、`projectionParcels`。
  - Order 新增 `meshAhead`、`meshRobots`、`interruptedState`、`restartInterrupted`、`meshResumed`。
- `core/Requests.java`：HTTP `options.batch` 或 `options.projectionParcels` 视为批量单，`batchId` 由“下单者 + clientRequestId”确定性生成。
- `core/Store.java`：
  - 新增 `mesh` 文档类型，`saveMesh` / `mesh(id)`。
  - `nextQueued` 跳过 `meshParent` 订单；重启时不把 `meshParent` 订单标记为失败。
  - `accept(...,prepare)` 重载：订单可见之前先修改它。
- `core/OrderFailurePolicy.interrupted`：记录 `interruptedState`、`restartInterrupted`。
- `core/ErrorMessages.java`：新增 `MESH_*` 和 `WHISPER_ORDERS_DISABLED` 的中文说明。
- `core/Config.java`：新增 `mesh` 字段并校验。
- `game/OrderEngine.java`：
  - `hooks()`；`quiet()` 时任务块不在公屏或私聊说话。
  - 交付前 `lockRecipient()`，交付后 `unlockRecipient()`。
  - 空盒补充箱和存箱箱子用 `claim()` 排队；选货源时避开其他机器人正在用的箱子。
  - `verifyAdopted()`；任务块超时 1800 秒；`forcePacking`；`failOrder` 记录 `interruptedState`。
  - 所有私聊回报改为只在 `notifyPlayer!=null` 时发送。
- `game/WhisperInbox.java`：玩家私聊单一律拒绝（`WHISPER_ORDERS_DISABLED`）并私聊提示。旧逻辑保留为未使用的 `acceptLegacy`，可删除。
- `game/BatchInbox.java`：收到 `begin` 时拒绝并私聊提示。旧逻辑挪到了永远不会触发的 `legacy-begin` 分支，可删除。
- `web/WebServer.java`：
  - `Backend.mesh()`；请求体只读一次（`readBody` / `body(x,raw)`）。
  - `/api/v1/mesh/heartbeat`；签名的转发请求（`x-mesh-*` 请求头）。
  - 工人转发 `orders` / `queue`；下单检查 `X-Superwy-Mesh: 1`（管理员不受限）。
  - 调度机器人下单时设置 `meshParent`；对外订单的 `queue.ahead` 用 `meshAhead`；新增错误码对应的 HTTP 状态码。
- `SuperwyClient.java`：
  - 创建并注册 `MeshNode`，绑定到执行器。
  - 进服时 `join`，断线时 `disconnect`，每秒 `tick`。
  - 命令 `/superwy mesh`、`/superwy mesh on`、`/superwy mesh off`；网页管理接口 `GET mesh`、`POST mesh/enable|disable`。
  - `workerReady` 是本机能否取货；调度机器人始终接单。
  - `cancel` 时通知 mesh；capabilities 增加 `mesh` 字段。

## 三、后续已完成（第二台电脑接手前的这一轮）

- 机器人端编译通过；删除私聊旧逻辑残留（`acceptLegacy`、`legacy-begin`）。
- 新增测试 `MeshSchedulerTest`（13 项）、`MeshProtocolTest`（4 项）；`WhisperInboxTest` 改为验证一律拒绝并提示。
- 网页“接入”页新增“超级调度模式”卡片：状态、开关、机器人列表。
- 玩家端：只走 HTTP（单项、批量、投影都是一次 HTTP 订单，3 秒查询进度）；`/swrequest mesh on|off`；设置窗口的模式按钮改为超级调度开关；HUD 多机器人进度；`MESH_MODE_REQUIRED` 等错误提示；相关测试已更新并新增批量与 mesh 请求头测试。
- 两端版本 0.6.0，README、根 README、`shared/PRIVATE_MESSAGES.md` 已更新。

## 仍未完成

1. ~~打包 private jar~~：已完成，`superwy-fabric/dist/superwy-fabric-0.6.0-private.jar`。签名密钥由 FRP 凭证派生，重新打包不变。
2. 实服多账号验收：握手、派活、单项插队、批量排队、掉线 2 分钟改派、回来继续、收货假人锁。
3. 旧的游戏内测试（GameTest）里依赖私聊下单的用例已不适用，需要按 HTTP 重写或删除；目前只确认能编译。

## 四、已知取舍

- 调度机器人掉线不会自动选出新的调度机器人。它重启后从数据库恢复调度状态继续派。
- 剩余部分改派之后，原机器人回来仍会交付身上已经拿到的货，玩家可能多收到一些。
- 机器人被手动 `/superwy stop` 时，它的任务块仍归它，不会改派。
- 每台机器人各自扫描库存，不互相同步。
