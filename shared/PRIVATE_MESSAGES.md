# SuperWY 私聊协议

> **0.6.0 起玩家私聊下单已停用。** 机器人收到 `swget1` / `swbatch1` 一律拒绝（`WHISPER_ORDERS_DISABLED`）并私聊提示更新取货端；玩家订单只走 HTTP。私聊只用于机器人之间的超级调度握手：
>
> ```
> swmesh1 hello   <32位十六进制随机数> <毫秒时间戳> <签名>
> swmesh1 welcome <随机数> <base64url(调度机器人 FRP 地址)> <签名>
> swmesh1 wait    <随机数> <签名>
> ```
>
> 签名为 HMAC-SHA256，密钥由 private jar 内置的 FRP 凭证派生；hello 时间戳须在 2 分钟内，同一发送者 3 秒内只处理一次。以下为历史协议说明，仅供参考。

# SuperWY 游戏私聊协议 v1

机器人端 0.4.5、玩家取货端 0.3.1。私聊与 HTTP 共用持久 FIFO、库存和执行器。默认开启私聊接单，不要求短 key；HTTP 的 Bearer token 验证保持不变。

## 名称与发送方式

- 接单机器人：正在运行仓库 Mod 的在线玩家，是 `/tell` 的目标。
- 收货假人：订单中指定的假人名。取齐材料后在机器人当前位置召唤，打开 GCA 背包放入材料，核对后 kill 下线。
- 两个名称含义不同，可分别在玩家设置窗口配置。双方须在同一个 Minecraft 服务器，私聊模式不需要公网 HTTP/frp 地址。

## 请求

```
/tell <接单机器人> swget1 <收货假人> <物品ID>=<数量>[,<物品ID>=<数量>...] [5位短key或-] [id=32位小写十六进制]
```

示例（无需玩家取货端也可手工发送）：

```
/tell WarehouseBot swget1 MyCargo minecraft:stone=64
/tell WarehouseBot swget1 MyCargo minecraft:stone=13,minecraft:iron_ingot=9 Ab123
```

`/msg`、`/w` 等原版 `/tell` 别名也可使用。支持原版 `MSG_COMMAND_INCOMING`，以及服务器将私聊转为系统消息的兼容格式：`Player悄悄地对你说：正文`、`Player whispers to you: 正文`、原版私聊翻译组件及其外层包装。系统消息中的发送者必须匹配当前在线玩家，按真实 UUID 入队；公屏、队伍聊天、动作栏、带公屏作者前缀的仿造文字和发送方向消息不会创建订单。兼容路径按完整模板匹配，不对任意正文做子串搜索。其他服务器自定义私聊格式仍需单独适配。

玩家名为 1–16 位字母、数字、下划线，禁止选择器。短 key 留空或为区分大小写的 5 位 ASCII 字母数字；只有机器人设置了非空 `whisperKey` 时才必须匹配。物品使用 namespaced ID，数量为正整数，单条清单合计最多 100 万；玩家设置窗口/投影浮窗每次取 1–64 个。每条协议正文不超过 230 字符，整个 `tell` 命令不超过 256 字符；清单太长需拆分。

玩家 Mod 每次主动提交生成新的请求 ID；同一发送者 UUID 与同一 ID 重放不会创建第二单，改变清单或收货人返回 `IDEMPOTENCY_CONFLICT`。手工消息不提供 ID 时，每次私聊视作独立请求。短 key 不保存到订单正文，也不会回显在回执中。

## 回执

机器人使用 `/tell <真实发送者>` 返回：

```
swreply1 <请求ID> accepted <订单ID> <当前排队位置>
swreply1 <请求ID> rejected <错误码>
```

accepted 表示已持久入队，不代表完成交付。玩家 Mod 仅接受来自配置中接单机器人、且与本次待确认 ID 匹配的回执。约 15 秒没有回执则提示未确认，不自动重试；每次主动提交独立发送，不维护本地订单队列，不自动查询服务端队列。迟到回执可在游戏私聊中查看，订单以机器人记录为准。

常见错误：`INVALID_SHORT_KEY`、`WHISPER_DISABLED`、`INVALID_WHISPER_REQUEST`、`QUEUE_FULL`、`CAPABILITY_UNAVAILABLE`、`IDEMPOTENCY_CONFLICT`。

## 队列和管理

发送者以服务器提供的玩家 UUID 区分，保留每人 5 单、全机器人 100 单的未结束订单限制，协议消息另有每发送者每分钟 30 条的接收上限。HTTP 和私聊按同一个数据库入队序号逐单执行；执行最多 500 秒，立即取消、背包保留和原地交付规则一致。

缺货时订单立即失败，并执行 `/say <物品ID> 缺货`；其他执行异常也立即结束本单。已取物品留在原位，不自动重试或补发；队列继续下一单。accepted 回执仅表示成功入队，不代表有货或交付成功。

私聊订单可由管理员在 Web 中查看/立即取消。HTTP 玩家凭据与游戏 UUID 是不同身份；玩家端的 HTTP status/cancel 命令仍要求 HTTP 模式及相应玩家 token，不使用 5 位短 key 访问 HTTP。

## 机器人游戏内配置

```
/superwy whisper
/superwy whisper on
/superwy whisper off
/superwy whisper key Ab123
/superwy whisper key off
```

开关与短 key 立即持久保存；状态只显示验证是否开启，不回显 key。关闭私聊不影响 HTTP。机器人仍需启用执行器、保存仓库区域并具备原有 Carpet/GCA/NBT 权限。

## 玩家游戏内配置

`/swrequest` 或 `/swrequest config` 打开设置窗口。设置接单机器人名、收货假人名、物品、数量、可选短 key，模式按钮在私聊与 HTTP 间切换；保存后即刻生效。HTTP 模式额外填写原有地址和玩家 token。

也可用命令：

```
/swrequest bot WarehouseBot
/swrequest recipient MyCargo
/swrequest key off
/swrequest item minecraft:stone
/swrequest count 64
/swrequest mode chat
/swrequest send
/swrequest request minecraft:iron_ingot 12
/swrequest mode http
```

设置保存在各自游戏目录的 `config/superwy/config.json` 和 `config/superwy-requester/config.json`。原有中键投影、数量 1–64、自由移动和连续移动 1 秒关闭浮窗均保留。


## 批量协议 v1（0.4.17 / 0.3.4）

通过认证的同服私聊，正文 `swbatch1 <32位十六进制会话ID> <操作> <参数...>`，每条不超过 230 字符，全部同一发送者 UUID。操作为 `begin <收货名> <短key或->`、`add <从0开始的连续行号> <物品ID> <1–64数量>`、`commit <总行数>` 和 `abort`。每行分包，有序且数量一致才受理为单张持久订单；短 key 仅 begin 校验，不写订单。容量查询期间暂停新扫描步骤，等待现有步骤安全结束后读取双方实体库存。

回复为 `swbatchreply1 <会话ID> capacity <上限>`、`added <行号>`、`accepted <订单ID>`、`progress <完成行数> <总行数> <fetching|delivering|done|error>` 或 `error <错误码>`。发送者、会话ID与总行数匹配才更新 HUD。进度通过 `/tell`，成功公屏文本为 `搞定了，我歇逼了`。HTTP 不接受 batchId/notifyPlayer 字段；所有普通入口与批量共用 FIFO，幂等重放保留原响应。


## 批量跳过消息扩展（机器人 0.4.18 / 收货端 0.3.5）

新增 `swbatchreply1 <会话ID> skipped <从0开始的行号> <原因码>`，仅发送给本单请求玩家。`progress` 的完成数现在表示已经处理完的项目数，包含跳过项；状态新增 `partial`（已交付可取材料）和 `empty`（全部未取到材料），二者均结束 HUD 会话。缺货或可核实的界面异常不会使用终止草稿的 `error` 消息。实际原因另通过普通聊天发送；订单每行持久保存 skipped、errorCode、shortfall，已取/已交数量继续保留真实值。取消、超时、无法核实物品和交付异常仍为 progress/error。旧收货端不识别新结束状态，需要同步升级。


## 混合任务队列扩展（机器人 0.4.19 / 收货端 0.3.7）

批量草稿和批量执行不再全局拒单；不同草稿按发送者 UUID + 会话ID隔离，确认后共用普通任务 FIFO。accepted 回复扩展为 `swbatchreply1 <会话ID> accepted <订单ID> <队列位置>`。繁忙时 capacity 回复为 `capacity <暂定上限> deferred`，空闲实际查询为 `capacity <上限> checked`；等待中的批量由执行器在取物前复核实际容量。

新增 `swbatchreply1 <请求ID或批量会话ID> queue <前方任务数> <queued|active|终态> <自己的订单ID>`，仅发送给该任务原始认证玩家。只在位置或状态变化时发送，不包含其他玩家、假人名或物品清单。普通私聊单项也存储回执目标；快速缺货、取消等在首次队列检测前结束的任务仍回传终态。HTTP 单项由取货端通过受凭据限制的订单查询获取 queue.ahead，约每 3 秒更新。重复提交原会话 commit 仅返回旧单，不再新增任务。

## 投影选区打包扩展（机器人 0.5.6 / 收货端 0.5.0）

begin 增加可选第三参数：`begin <收货名> <短key或-> parcels`。带 `parcels` 的会话受理为投影选区订单（`request.projectionParcels=true`，HTTP JSON 不能设置），无论清单大小都以潜影盒交付：清单按行顺序取货，每件物品先补满当前盒内同物品的未满堆叠，再占新格，满 27 格换下一盒；中途因携盒上限或空槽交付时，当前未装满的盒子留在机器人身上继续装，因此除最后一盒外每盒都是满的。请求的潜影盒物品不能装盒，在其他材料装盒后散装交付。收货假人满时仍依次改用 `_1`、`_2`。add/commit/abort、回复与进度消息不变；旧机器人对三参数 begin 回复 `error INVALID_REQUEST`。
