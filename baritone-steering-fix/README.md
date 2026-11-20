# Baritone Steering Fix 0.2.0

独立 Fabric 客户端补丁，适用于 Minecraft **26.1.2**、Java 25、Fabric Loader ≥0.19.5、对应 Fabric API 和 **Baritone API Fabric 1.18.0**。不依赖 SuperWY。

替换旧版 `baritone-steering-fix-0.1.0.jar` 为 `dist/baritone-steering-fix-0.2.0.jar`，重启后正常使用 `#goto x y z`。每个实例仅保留一份匹配 Minecraft 版本的 Baritone；声明支持 ~26.3 的 1.20.0 jar 不适用于 26.1.2。

寻路期间可自由转向。按住方向键时按玩家镜头方向移动，保持原任务和目的地；松开后继续路径。聊天、鼠标、滚轮都不被锁定。`/baritonefix` 查看状态；`/baritonefix off|on` 临时切换补丁，重启默认开启。`#pause`、`#cancel` 和到达终点后释放移动角度及临时设置。

补丁在 Baritone 每次路径更新后，通过 LookBehavior 公共接口缓存当前移动步骤的有效 yaw/pitch。实体移动和跳跃使用这些角度，不写入可见镜头，也不直接朝最终目标移动。缓存绑定玩家、世界、路径执行器及位置，250 毫秒过期，支持旋转目标提前被清除的情况。

路径运行时临时启用 freeLook/blockFreeLook，关闭 antiCheatCompatibility/smoothLook，结束时恢复原值（外部已修改的值不覆盖）。手动输入在 Baritone 输入更新后融合，方向按镜头转换到当前路径的坐标系；不会取消或暂停寻路。SuperWY 0.3.4 检测到本补丁后跳过自身重复处理。

仅客户端安装。发布 jar 不包含 Baritone、Fabric API 或其他第三方 Mod。

构建和隔离测试：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25.0.2'
.\gradlew.bat build runClientGameTest --console=plain
```

编译依赖为 `../superwy-fabric/test-libs/baritone-api-fabric-1.18.0.jar`。测试新建本地世界，不连接用户服务器、不操作用户实例。

上游：https://github.com/cabaletta/baritone ，参考 26.1 分支提交 `016cc210434894f371baf0c327ac94907d12dcd3`。本项目不修改或重新分发 Baritone。
