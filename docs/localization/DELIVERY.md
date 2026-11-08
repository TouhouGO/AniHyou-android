# 本地交付

本文记录的是 2026-11-07 的历史交付检查点。2026-11-08 加入自动检查、同步和发布
流水线后的验证结果见 [MAINTENANCE.md](MAINTENANCE.md)；下列旧 APK 名称和哈希
不能用来代表新版源代码或当前构建输出目录。

下列 APK 为未发布的历史验证产物，不代表当前源码构建。

## 构建产物

| 产物 | 仓库相对路径 | 大小 |
| --- | --- | ---: |
| 手机 FOSS Debug，通用架构 | `app/build/outputs/apk/foss/debug/anihyou-1.7.0-foss-universal-debug.apk` | 27,729,890 字节 |
| 手机 FOSS Debug，arm64 | `app/build/outputs/apk/foss/debug/anihyou-1.7.0-foss-arm64-v8a-debug.apk` | 27,634,018 字节 |
| 手机其他架构 | 同目录中的 armeabi-v7a、x86、x86_64 APK | 见清单 |
| Wear Debug | `wearos/build/outputs/apk/debug/wearos-debug.apk` | 24,630,973 字节 |

完整 SHA-256、字节数及目录资源哈希以 [ARTIFACTS.json](ARTIFACTS.json) 为准。
六份 APK 都已通过 ZIP 内容校验，目录、繁简字典、OpenCC 许可、soruly 许可和
来源 NOTICE 与源码资源逐字节一致。

这些是使用开发环境 Debug 签名的本地包，应用 ID 使用上游既有 `.debug` 后缀。
没有生成生产签名 Release/AAB 或手机 GMS 包，没有安装、启动、上传或发布。
本次没有读取生产签名密钥；不应把本地包当作官方商店版本的已验证升级包。

## 验证范围

166 项相关 Kotlin/JVM 与 Android 主机测试通过（163 项批次加最终 3 项显示/标签
契约复测）；目录导入/打包 Python 测试
57 项和字形生成器测试 2 项通过。手机和 Wear 构建任务图先经过检查：手机
648 个任务、Wear 164 个任务，均不含设备安装、连接测试、托管设备或基线生成。
Wear 包也已单独构建并检查资源。

上游 `core/ui` 的三个 YouTube Markdown 测试预期与现有 renderer 行为不符，
在本轮改动前已存在；未通过修改测试隐藏，也不包含在上述成功批次中。更详细的
命令和行为范围见 [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md)。

尚未验证：真实 Android/手表/桌面组件的显示和生命周期、设备冷启动/内存性能、
在线服务时延、生产升级和 live OTA。用户要求期间不操作模拟器；本次同样没有
操作设备。目录更新客户端已实现，新 `catalog-latest` 通道尚未发布。

## 维护入口

- 架构与重构原因：[ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md)
- 搜索覆盖及性能边界：[SEARCH_PERFORMANCE.md](SEARCH_PERFORMANCE.md)
- 来源、许可、冲突和离线重建：[SOURCES.md](SOURCES.md)
- 导入、恢复、更新协议及离线打包：[CATALOG_UPDATES.md](CATALOG_UPDATES.md)

名称覆盖来自固定来源快照，存在缺项和冲突隔离。当前 12,492 个可检索媒体 ID
不等于 12,492 个已经人工核验的中文译名，也不保证 AniList 全库覆盖。

整分支代码复核未发现 P1/P2 问题；具体检查路径和证据边界见
[REVIEW.md](REVIEW.md)。手机通用包和 Wear 包还通过了 `apksigner verify`
的 v2 签名结构校验。该只读检查不代表生产发布或设备验收。

性能完成审计还补齐了独立会话构造以及 30 次热解析/建索引的 P50/P95；具体采样
方法、完整候选校验和结果见 `SEARCH_PERFORMANCE.md`。初始加载仍只有单次观察，
未冒充设备冷启动分布。后续测量工具和文档提交没有改变上述 APK 的生产代码。
