package dev.superwy.core;

import java.util.*;

/** Stable protocol codes remain machine-readable; user-facing explanations are always Chinese. */
public final class ErrorMessages {
    private static final Map<String,String> MESSAGES=new HashMap<>();
    private static final String TEXT="""
OUT_OF_STOCK|缺货
MESH_MODE_REQUIRED|该机器人处于超级调度模式，请在取货端执行 /swrequest mesh on 开启超级调度模式后重新提交，任务会自动发给调度机器人
MESH_AUTHORITY_OFFLINE|调度机器人当前不在线或尚未连通，请稍后再试
MESH_UNAUTHORIZED|机器人之间的调度认证失败，所有机器人都需要使用同一主人的 private jar
MESH_KEY_MISSING|超级调度模式需要 private jar（内置 FRP 凭证）
MESH_BUSY|仍有调度任务未完成，完成后再关闭超级调度模式
MESH_BLOCK_LOST|机器人丢失了分配给它的任务块，剩余部分已交给其他机器人
MESH_ROBOT_OFFLINE|机器人掉线超时，任务已改派给其他机器人
WHISPER_ORDERS_DISABLED|私聊下单已停用，请更新取货端并通过 HTTP 连接
EMPTY_SHULKER_BOX_REQUIRED|取盒点没有空潜影盒，请补充空盒
QUICKSHULKER_REQUIRED_FOR_PACKING|打包需要可用的 QuickShulker 背包潜影盒接口
PARCEL_CONTENT_CHANGED|货盒内容与已核实清单不符，请核对货盒
PARCEL_SYNC_TIMEOUT|货盒内容同步超时，已停止并保留物品
PARCEL_FULL|货盒没有可用空间
INVALID_CLEANUP_SLOT|只允许清理主背包 9–35 槽
CLEANUP_INCOMPLETE|主背包余物尚未全部存入清理箱
CLEANUP_STORAGE_REQUIRED|清理位置必须是可打开的箱子或木桶
CLEANUP_STORAGE_FULL|清理箱空间不足，请腾出空间
CLEANUP_STORAGE_MISMATCH|存入清理箱的数量无法核实，请检查背包及箱子
CLEANUP_PROTECTED_CHANGED|清理期间快捷栏或装备数据发生变化，请检查物品
CLEANUP_POSITION_CHANGED|清理时已离开全物品入口，停止丢弃
BATCH_BUSY|正在批量取货中，等待完成后再发送新的请求
BATCH_CAPACITY|批量清单超过可用空槽容量，请减少材料或数量
BATCH_EXPIRED|批量编辑已超时，请重新按队列键
INVALID_SCAN_PROTOCOL|扫描协议必须为 f3 或 servux
SERVUX_UNAVAILABLE|服务器未提供 Servux 批量扫描；可用 /superwy scan protocol f3 切回 F3
SERVUX_CLIENT_INCOMPATIBLE|客户端 Litematica 的 Servux 接口不兼容；请切回 F3
SERVUX_HANDSHAKE_TIMEOUT|Servux 握手超时，请检查服务端数据提供器和权限；可切回 F3
SERVUX_PROTOCOL_UNSUPPORTED|服务器的 Servux 协议版本尚未适配；请切回 F3
SERVUX_QUERY_TIMEOUT|Servux 批量读取超时，待扫描位置已保留；请切回 F3 或重新连接
SERVUX_RESPONSE_TOO_LARGE|Servux 回复超过批量读取预算；请切回 F3 分别读取容器
SERVUX_RESPONSE_INVALID|Servux 回复格式异常，历史库存和待扫描位置已保留；请切回 F3
SERVUX_RESPONSE_PENDING|上次 Servux 回复尚未收齐，等待回复后重启该区域或切回 F3
SERVUX_RESYNC_REQUIRED|Servux 数据流需要重新连接；可先切回 F3 继续扫描
SERVUX_CONTAINER_MISSING|Servux 回复缺少该容器，保留历史库存并标为未完整读取
NO_INTERACTION_STAND|无法靠近目标容器，不需要配置工作点
CONTAINER_PATH_UNREACHABLE|无法走到容器可交互的范围内
CONTAINER_OUT_OF_REACH|目标容器仍超出可交互距离
CONTAINER_UNLOADED|目标容器所在区块尚未加载
SOURCE_CONTAINER_MISSING|缓存位置已没有可用容器，请更新扫描
OPEN_CONTAINER_TIMEOUT|服务器未打开容器，请检查距离、箱盖阻挡或开箱权限
PATH_UNREACHABLE|无法到达目标位置
NAVIGATION_NOT_MOVING|寻路长时间没有移动，任务已停止
BARITONE_MISSING|未安装可用的寻路模组
BARITONE_GOAL_CHECK_FAILED|无法确认寻路是否到达目标
BARITONE_REPLAN_FAILED|重新规划路径失败
BARITONE_STEERING_FAILED|寻路方向控制失败
AUTOMATION_DEPENDENCY_MISSING|缺少自动寻路依赖
AUTOMATION_DISABLED|自动执行已暂停，请先启用执行器
CAPABILITY_UNAVAILABLE|机器人尚未就绪，请检查执行能力
EXECUTOR_BUSY|机器人正在执行其他操作，请稍后再试
EXECUTOR_INTERRUPTED|机器人执行环境已变化，本单已停止
CONTROL_STOPPED|玩家已停止自动控制
USER_CANCELLED|订单已取消
EXPIRED|订单已超过有效期
ORDER_TIMEOUT|任务执行超过本单时限，已强制停止
RESTART_INTERRUPTED|程序重启中断了任务，已保留现有物品
EXECUTION_ERROR|执行异常，任务已停止
DISCONNECTED|游戏连接已断开
DISCONNECTED_OR_PAUSED|游戏连接已断开或自动执行已暂停
OFFLINE|机器人尚未连接游戏服务器
SOURCE_CHANGED|来源物品与缓存不一致，请更新扫描
SOURCE_BOX_CHANGED|源潜影盒内容与缓存不一致，已停止取物
SOURCE_BOX_RETURN_BLOCKED|源潜影盒原槽被占用，盒子保留在机器人背包
SOURCE_BOX_RETURN_MISMATCH|源潜影盒归还后原槽内容不一致，请核对物品
SOURCE_BOX_SYNC_TIMEOUT|源潜影盒内容同步超时，已停止操作
QUICKSHULKER_UNAVAILABLE|服务器的潜影盒免放置打开功能不可用
QUICKSHULKER_REQUIRED_FOR_NESTED_SOURCE|箱内潜影盒需要服务器开启免放置打开功能
QUICKSHULKER_CLIENT_INCOMPATIBLE|已安装的潜影盒打开模组接口不兼容
QUICKSHULKER_OPEN_TIMEOUT|服务器未打开背包中的源潜影盒
QUICKSHULKER_MENU_MISMATCH|服务器打开的界面不是预期的潜影盒
FREE_CARGO_SLOTS_REQUIRED|背包没有足够空闲主物品栏，请先腾出空间
INVENTORY_FULL|目标物品栏已满
CURSOR_NOT_EMPTY|鼠标光标携带着物品，请先放回物品栏
CLOSE_PLAYER_MENU|有未关闭的物品界面，请先关闭后重新下单
CLEAR_MANAGED_INVENTORY_FIRST|工作物品栏尚有需核对的物品
CURSOR_OR_MENU_DESYNC|鼠标物品或容器界面与服务器不同步
MENU_DESYNC|容器界面已变化，操作已停止
UNEXPECTED_MENU|存在意外打开的容器界面
INVALID_TRANSFER|无法执行该物品转移，请检查槽位和物品数量
TRANSFER_PREDICTION_FAILED|物品转移结果与预期不一致
SERVER_INVENTORY_MISMATCH|服务器物品栏与预期不一致，请核对物品
PLAYER_SLOT_NOT_FOUND|无法找到玩家对应的物品栏槽位
ORDER_CARGO_CHANGED|订单物品被移动或内容发生变化，已停止交付
FAKE_PLAYER_BUSY|指定的收货假人已在线，请先让其下线或更换名称
FAKE_PLAYER_UNAVAILABLE|未能召唤指定收货假人，请检查权限和名称
FAKE_PLAYER_DESPAWN_TIMEOUT|收货假人下线超时，请核对其在线状态
INVALID_FAKE_PLAYER_NAME|收货假人名称不符合要求
INVALID_RECIPIENT|收货假人状态不符合交付要求
RECIPIENT_FULL|收货假人物品栏已满，材料保留在原位
RECIPIENT_DISAPPEARED|收货假人已离开，交付已停止
RECIPIENT_IDENTITY_LOST|无法确认收货假人身份，交付已停止
RECIPIENT_NOT_REACHABLE|无法靠近或右键交互收货假人
RECIPIENT_MENU_MISMATCH|收货假人界面与服务器库存不一致
GCA_INVENTORY_OPEN_TIMEOUT|右键假人未打开背包，请检查假人背包规则
GCA_INVENTORY_LAYOUT_UNSUPPORTED|假人背包界面布局不受支持，交付已停止
DELIVERY_UNCERTAIN|交付结果尚未核实，请核对双方物品
RECONCILIATION_REQUIRED|存在需要核对的物品记录
MANUALLY_RECONCILED|已人工核对并结束订单
CANCEL_CARGO_RETAINED_ON_ROBOT|订单已取消，剩余材料保留在机器人背包
NOTHING_DELIVERED|本单没有完成物品交付
STORAGE_UNAVAILABLE|本地数据库不可用，请检查机器人日志
QUERY_BUSY|库存查询过于频繁，请稍后再试
NBT_QUERY_TIMEOUT|服务器库存查询超时，请检查查询权限和连接
EMPTY_NBT_RESPONSE|服务器没有返回容器或实体库存数据
NBT_RESPONSE_TOO_LARGE|容器或实体库存数据过大，已停止读取并记录跳过
NBT_RESPONSE_INVALID|服务器返回的库存数据格式异常，已停止读取并记录跳过
FRP_NOT_READY|FRP 尚未就绪，请先启动并等待公网连接成功
INVALID_RECIPIENT_NAME|收货假人名称应为 1–16 位字母数字下划线
NBT_TOO_LARGE|库存数据过大，无法读取
MALFORMED_ITEM|库存物品数据格式异常
MALFORMED_SLOT|库存槽位数据格式异常
PLAYER_INVENTORY_QUERY_UNVERIFIED|玩家库存查询尚未通过，请检查查询权限
UNGENERATED_LOOT|容器战利品尚未生成，请先手动打开容器
BOUNDARY_CONFLICT|双箱另一半不在扫描区域内，请调整区域
UNSUPPORTED_CONTAINER|不支持该特殊容器，已记录跳过
UNLOADED|目标区块尚未加载
UNLOADED_OR_UNREACHABLE|目标区块尚未加载且无法到达
PREVIOUS_SCAN_FAILED|该容器曾扫描失败，已保留跳过记录
PREVIOUS_CHUNK_FAILED|该区块曾扫描失败，已保留跳过记录
UNKNOWN_REGION|找不到当前世界的指定扫描区域
NO_REGION|当前世界尚未设置仓库区域
NO_REGION_CONFIGURED|当前世界尚未设置可用仓库区域
REGION_DISABLED|该仓库区域已禁用
SCAN_DISABLED|全局扫描已关闭，请先开启扫描
SCAN_REGION_STOPPED|该区域扫描已停止，断点已保存
SCAN_STOPPED|扫描已停止，断点已保存
SPECTATOR_MODE|旁观模式不能执行取货
TIMER_TIMEOUT|操作等待超时
INVALID_ITEM|物品编号不存在或不受支持
AMBIGUOUS_ITEM|该物品存在多个不同组件版本，请指定准确版本
ITEM_NOT_PACKABLE|该物品无法按当前方式存放
INVALID_REQUEST|请求格式或参数不正确
REQUEST_LIMIT_EXCEEDED|请求数量超过允许上限
INVALID_SHORT_KEY|验证短钥不正确
WHISPER_DISABLED|机器人已关闭私聊接单
INVALID_WHISPER_REQUEST|私聊请求格式不正确
QUEUE_FULL|订单队列已满，请稍后再试
RATE_LIMITED|操作过于频繁，请稍后再试
IDEMPOTENCY_CONFLICT|相同请求编号已用于不同订单
CURSOR_EXPIRED|分页数据已更新，请重新查询
ORDER_NOT_CANCELLABLE|订单已经结束，不能再取消
NOT_FOUND|未找到对应记录
FORBIDDEN|无权访问，请检查令牌、允许访问的地址或操作权限
UNAUTHENTICATED|身份验证失败，请重新登录
PAYLOAD_TOO_LARGE|请求内容过大
INTERNAL_ERROR|程序发生异常，请检查机器人日志
""";
    static {
        for(String line:TEXT.split("\n")){int p=line.indexOf('|');if(p>0)MESSAGES.put(line.substring(0,p),line.substring(p+1));}
    }
    private ErrorMessages(){}
    public static String message(String code){
        if(code!=null&&code.startsWith("INCOMPLETE_NBT_"))return "服务器返回的库存数据不完整";
        return MESSAGES.getOrDefault(code,"发生异常，任务已停止，请检查机器人日志");
    }
    public static String order(Model.Order order){String reason=order.errorCode!=null?order.errorCode:order.blockingReason;return reason==null?null:message(reason);}
}
