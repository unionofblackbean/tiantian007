# 上游与依赖

- Fabric Loader / Fabric API / Fabric Loom：FabricMC 官方发行版；依赖由游戏实例独立安装。
- Litematica：sakura-ryoko LTS/26.1，0.27.14。仅反射调用公开接口，不复制/重打包上游源码或 jar；Litematica 项目采用 GNU LGPL v3。
- MaLiLib 0.28.12：Litematica 的独立依赖，不随本 mod 分发。
- Minecraft 官方客户端由游戏实例提供，不打包 Minecraft 或 Mojang 资产。
- Gradle Wrapper 9.5.1：构建工具。

参考：[Litematica 源码](https://github.com/sakura-ryoko/litematica/tree/LTS/26.1)，[Litestock](https://github.com/unionofblackbean/litestock)（机器人扫描可视化参考），[Fabric API](https://github.com/FabricMC/fabric)。
