# 依赖与来源

本项目没有复制 LiteStock、Baritone 或 QuickShulker 的实现代码。游戏 API 调用、仓库执行器和 Web 控制台为本工程实现。

| 依赖 | 用途 | 分发方式 / 来源 |
| --- | --- | --- |
| Fabric Loader / Fabric API | 客户端入口、事件、命令、网络能力 | 外部运行依赖；https://fabricmc.net/ |
| Xerial sqlite-jdbc 3.50.3.0 | 本地持久化库存与订单 | 嵌入 nested jar；Apache-2.0，原 jar 保留许可证；https://github.com/xerial/sqlite-jdbc |
| Gson | JSON 与请求规范化 | 使用 Minecraft 运行时已有依赖，不另行打包；https://github.com/google/gson |
| Baritone API Fabric 1.18.0 | 移动与寻路 | 独立安装，不嵌入发布包；LGPL-3.0；https://github.com/cabaletta/baritone |
| QuickShulker Multi | 免放置打开潜影盒 | 服务端安装、客户端可选；内置兼容开盒消息（菜单槽号 int），不嵌入原 mod；MIT；https://github.com/EnderPhantomWing/quickshulker-multi |
| Carpet | 假人创建、下线 | 服务端独立安装，不嵌入发布包；https://github.com/gnembon/fabric-carpet |
| Servux / Litematica | 实验性仓库区块批量扫描协议与可选客户端通道共存 | 独立实现 `servux:litematics` v1 网络格式；参考 LGPL-3.0 项目公开协议源码，不嵌入其 mod 或实现类；https://github.com/sakura-ryoko/servux 、https://github.com/sakura-ryoko/litematica |
| JUnit Jupiter | 自动化测试 | 仅测试依赖，不嵌入发布包；https://junit.org/ |

Servux 定向游戏测试通过隔离类加载器使用原始 Servux 0.10.4 发行 jar 的编解码器及 PacketSplitter；兼容测试另加载原始 Litematica / MaLiLib jar。上述 jar 和测试夹具不打入机器人发行包。

开发设计参考 https://github.com/unionofblackbean/litestock 。完整历史需求与源码调研位于旧项目 docs/fabric-rebuild 目录。
# 内置 FRP

frpc 0.71.1 Windows amd64 取自官方发行版。FRP 项目：https://github.com/fatedier/frp ，Apache License 2.0。完整许可证随 jar 的 `frp/LICENSE` 和源码资源发布。Mod 解压时验证随包 SHA-256；独立子进程管理，不与游戏 JVM 链接。普通 jar/源码不含所有者 FRP 凭证；自用私有 jar 单独注入认证预设。

