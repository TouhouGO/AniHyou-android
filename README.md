# AniHyou 中文增强版

面向简体中文用户的 AniList Android 客户端，基于 [axiel7/AniHyou-android](https://github.com/axiel7/AniHyou-android) 持续同步开发。在 AniHyou 原有的追番、阅读进度管理、作品探索和社区功能基础上，增加作品译名、剧情简介、分类标签、人物名称和中文搜索支持。

本仓库是 TouhouGO 维护的社区分支。原版 AniHyou 由 axiel7 及其贡献者开发；本分支的 APK 和语言包通过本仓库的 GitHub Releases 分发。

[下载 APK](https://github.com/TouhouGO/AniHyou-android/releases/tag/v1.7.0) · [全部发布](https://github.com/TouhouGO/AniHyou-android/releases) · [反馈问题](https://github.com/TouhouGO/AniHyou-android/issues) · [原版项目](https://github.com/axiel7/AniHyou-android)

## 安装与使用

需要 **Android 7.0（API 24）或更新版本**。当前应用版本为 **1.7.0（versionCode 138）**，正式发布渠道为 FOSS APK。

在 [Releases](https://github.com/TouhouGO/AniHyou-android/releases) 中选择标题为「AniHyou 中文增强版」的应用版本，下载对应 APK。语言包发布条目包含 ZIP 和 JSON 文件，通过应用内更新入口安装。

| APK 文件名中的架构 | 适用情况 |
| --- | --- |
| `universal` | 包含多种架构，适合不确定设备架构时选择 |
| `arm64-v8a` | 常见的现代 Android 手机和平板 |
| `armeabi-v7a` | 使用 32 位 ARM 的旧设备 |
| `x86` / `x86_64` | 使用对应 Intel 架构的 Android 设备 |

安装后可浏览作品；个人列表、进度同步和社区操作需要登录 AniList。作品及账户服务依赖访问 AniList，中文简介和部分人物补全还需要访问相应数据源。

若从其他渠道安装的 AniHyou 切换到本分支，Android 可能因签名不同而拒绝覆盖安装。切换前请确认进度已经同步到 AniList，并留意本地设置和数据的备份。

## 中文增强功能

| 功能 | 当前行为 |
| --- | --- |
| 作品标题 | 首页、探索、搜索、榜单、列表和详情等页面优先展示中文译名，未命中时保留原始标题 |
| 中文搜索 | 使用本地译名索引检索作品，并结合 Bangumi 搜索补全匹配 |
| 剧情简介 | 结合 Bangumi 关联及匹配结果补充中文简介，支持繁体转简体；缺少可靠内容时保留原始简介 |
| 分类与标签 | 展示中文流派、题材和标签，查询时转换回 AniList 使用的键值 |
| 角色与制作人员 | 结合离线人物资源、本地缓存、Bangumi、Wikidata 和字符转换补全中文名称 |
| 独立开关 | 在「设置 → 语言与本地化」分别控制标题、分类标签、角色与声优、剧情简介增强 |
| 语言包管理 | 查看当前版本、检查更新、下载最新语言包和恢复内置默认资源 |

中文化依赖数据覆盖范围和匹配质量。应用会校验关联、媒体类型等信息，匹配不足时保留原始数据。人物名、同名作品、冷门条目及新作仍可能存在缺失或误匹配；繁简和日文汉字转换也可能受语境影响。

## 更新应用与语言包

**应用更新**通过安装新版本 APK 获取，包含代码、界面和上游功能变更。

**语言包更新（OTA）**独立于 APK 发布，可在现有应用中更新中文数据：

1. 打开「设置 → 语言与本地化」。
2. 在「语言包管理」中点击「检查更新」。
3. 有新版本时点击「下载 / 更新最新语言包」。

语言包包含标题、标签、人物名称和繁简字符表。下载与安装会校验清单、文件大小、SHA-256、ZIP 条目和资源内容；安装采用临时目录与原子切换，失败时保留可用资源。安装成功后热重载，并通知相关页面刷新。遇到资源问题时可选择「恢复内置默认」。

内置资源是应用构建时的基线，已安装的扩展语言包可以拥有更新的版本。语言包发布后，持续集成（CI）会将已验证的资源回填到 `master`，供后续 APK 构建使用。

### 每周自动发布

[Release Localization Bundle](.github/workflows/release-localization-bundle.yaml) 每周一 **04:17 UTC / 北京时间 12:17** 计划运行，实际启动时间可能受 GitHub Actions 调度影响。

```text
同步标题、标签、人物名称和繁简资源
  → 运行 core/network 全量单元测试
  → 使用最新 Bangumi Archive 校准标题
  → 打包并校验语言包
  → 上传草稿并下载回验 SHA-256
  → 正式发布并更新 localization-latest 下载清单
  → 回填仓库内置资源
```

定时任务使用 `vYYYY.MM.DD` 作为语言包标签。当天版本已发布或同步后资源文件没有变化时跳过发布；测试、下载或校验失败时停止后续步骤。繁简表同步会保留现有表中上游导出缺失的字符映射，以维持日文汉字转换兼容性。

原有 [Sync titles from upstream](.github/workflows/sync-titles-from-upstream.yaml) 保留为手动标题同步入口。定时更新及 OTA 发布统一由语言包发布工作流负责。

## 开发与维护

### 本地构建

准备 JDK 21、Android SDK，以及项目声明的 SDK 和 Build Tools。具体依赖版本见 [版本目录](gradle/libs.versions.toml)，应用版本见 [version.properties](version.properties)。Android SDK 路径可通过本地 `local.properties` 配置。

```bash
# FOSS 调试版
./gradlew :app:assembleFossDebug

# GMS 调试版
./gradlew :app:assembleGmsDebug

# 网络与本地化模块单元测试
./gradlew :core:network:test
```

调试 APK 输出到 `app/build/outputs/apk/`。正式版签名由 GitHub Actions 配置的密钥完成。

### 代码结构与边界

中文增强的主要实现位于以下位置：

| 位置 | 职责 |
| --- | --- |
| `core/network/.../network/localization/` | 中文数据提供器、搜索与响应处理、资源安装、在线更新及缓存 |
| `core/network/src/main/resources/` | 内置语言包和资源清单 |
| `feature/settings/.../settings/localization/` | 本地化开关和语言包管理界面 |
| `core/domain/` 与各功能模块 | 偏好设置、依赖接入和页面刷新订阅 |
| `scripts/` | 上游同步、标题校准、发布验证和仓库检查 |

本分支会修改网络接入、偏好设置、依赖注入、导航及部分页面 ViewModel，以连接中文功能。`LocalizationConfigState` 统一传播开关和资源变更，并配合缓存失效机制刷新展示数据。相关页面清单见 [本地化刷新覆盖说明](docs/localization-refresh-surfaces.md)。

维护时应保持外部中文数据源访问集中在本地化网络层，遵循现有资源契约和匹配规则。边界检查会拒绝业务模块直接访问本地化数据源，以及人工条目 ID 覆盖表等越界改动。

### 提交前检查

```bash
bash scripts/check-repository-hygiene.sh
bash scripts/verify-localization-boundary.sh --self-test
bash scripts/verify_localization_refresh_coverage.sh
bash scripts/test_release_localization_workflow.sh
./gradlew :core:network:test
```

涉及应用代码或上游合并时，还应执行对应渠道构建。运行时验证应使用可用且已获准使用的设备。

### 同步原作者代码

`origin` 指向本中文增强分支，`upstream` 指向 `axiel7/AniHyou-android`。在工作区干净的前提下先预检，再执行同步：

```bash
git fetch upstream
bash scripts/sync-upstream.sh --dry-run
bash scripts/sync-upstream.sh
```

同步脚本会检查工作区、创建备份引用、合并上游并执行检查与构建。发生冲突时需结合双方功能解决，尤其留意依赖注入、页面刷新订阅、设置项和版本号。

### 发布应用

[Release AniHyou FOSS APK](.github/workflows/build-upload-android.yml) 支持推送应用版本标签或手动运行：

1. 修改 `version.properties`，递增 `code`，设置 `name`。
2. 完成测试和构建检查，提交代码。
3. 推送与 `name` 一致的标签，例如 `v1.7.0`；也可在 Actions 中手动填写 `release_tag` 和 `prerelease`。

工作流校验标签与版本一致性，构建并签名 FOSS APK，对每个安装包执行签名校验后发布 GitHub Release。应用版本标签和语言包日期标签分别管理两类产物。

### 手动发布语言包

除每周定时任务外，也可手动运行语言包工作流，或推送新的 `vYYYY.MM.DD` 日期标签。手动和标签发布使用所选提交中的资源，按需先执行 `bash scripts/sync-titles.sh` 更新标题。已正式发布的语言包受保护，发布修订包时应使用更高版本。

## 问题反馈

请在 [Issues](https://github.com/TouhouGO/AniHyou-android/issues) 中提供应用版本、语言包版本、Android 版本和复现步骤。译名或简介问题请附 AniList 作品链接、当前显示内容与期望内容；人物问题请附角色或制作人员链接，便于追查数据来源和匹配过程。

离线词典可以用于本地名称查找，在线浏览、账户操作、简介补全和部分人物解析仍依赖网络。资源更新失败时请检查 GitHub 下载及相关服务的连通性。

## 数据来源与致谢

- [AniList](https://anilist.co/)：作品、角色、制作人员、社区及用户列表数据。
- [Bangumi](https://bgm.tv/) 与 [Bangumi Archive](https://github.com/bangumi/Archive)：中文标题、简介及关联数据。
- [Wikidata](https://www.wikidata.org/)：部分人物的中文名称与关联信息。
- [TouhouGO/anilist-zh-cn-userscript](https://github.com/TouhouGO/anilist-zh-cn-userscript)：中文资源聚合与导出。
- [soruly/anilist-chinese](https://github.com/soruly/anilist-chinese)、[bangumi-data](https://github.com/bangumi-data/bangumi-data) 与 [OpenCC](https://github.com/BYVoid/OpenCC)：译名及字符转换数据来源。

感谢原作者 axiel7、AniHyou 贡献者和各数据项目的维护者。原版项目及社区入口：

[Android 源码](https://github.com/axiel7/AniHyou-android) · [项目主页](https://axiel7.github.io/anihyou/) · [Google Play](https://play.google.com/store/apps/details?id=com.axiel7.anihyou) · [F-Droid](https://f-droid.org/packages/com.axiel7.anihyou) · [iOS 源码](https://github.com/axiel7/AniHyou-iOS) · [Crowdin](https://crowdin.com/project/anihyou) · [Discord](https://discord.gg/CTv3WdfxHh) · [支持原作者](https://ko-fi.com/axiel7)

上述原版商店和社区入口由上游项目维护。本分支的安装包请使用本仓库 Releases。

## 许可证

代码遵循 [GNU GPL v3.0](LICENSE)。原项目版权与署名归相应作者及贡献者所有，外部数据遵循各来源项目的许可和使用条款。
