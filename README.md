# <img alt="AniHyou 图标" height="50" src="https://github.com/axiel7/AniHyou-android/blob/master/core/resources/src/main/res/mipmap-hdpi/ic_launcher_round.webp" /> AniHyou 中文增强版

基于 [axiel7/AniHyou-android](https://github.com/axiel7/AniHyou-android) 的简体中文增强分支，在保留原版 AniList 客户端体验的基础上，补充作品、简介、分类标签、角色与制作人员的自动中文化能力。

> 本项目是社区维护的非官方分支。AniHyou 原版及其作者链接均保留在下方“原版项目”章节。

## 下载

从 [GitHub Releases](https://github.com/TouhouGO/AniHyou-android/releases/latest) 下载最新稳定版 FOSS APK。

首次安装建议选择文件名包含 `universal` 的版本；了解设备架构的用户也可以选择对应 ABI 的安装包。

## 中文增强功能

- **作品标题**：在首页、探索、搜索、榜单、收藏与详情等页面优先显示中文译名，未命中时保留 AniList 原始标题。
- **剧情简介**：优先使用严格匹配的 Bangumi 中文简介，支持繁体转简体；未取得可靠中文内容时回退到 AniList 原始简介。
- **分类与标签**：流派、题材和标签支持中文展示，并在需要时自动转换回 AniList 查询键。
- **角色与制作人员**：通过离线实体包、本地缓存、Wikidata、Bangumi 关联和日文汉字转换逐级解析中文名，匹配失败时安全保留原名。
- **独立开关**：标题、分类标签、角色与声优、剧情简介均可在“设置 → 语言与本地化”中分别启用或关闭。
- **语言包 OTA**：内置基础语言包支持在线检查、校验、原子安装、热重载和恢复默认版本。

## 实现原则

中文增强集中在独立的数据提供器、网络响应处理和本地化状态层，通过明确边界接入现有页面。自动匹配结果必须通过媒体类型、原名和关联数据校验，不使用人工条目 ID 修补表；匹配置信度不足时直接回退原始数据。

项目保留独立的上游同步脚本和边界检查，便于持续引入原作者更新，同时避免中文增强逻辑扩散到无关业务模块。

## 语言包更新

打开：

`设置 → 语言与本地化 → 下载 / 更新最新语言包`

下载内容会经过文件大小、SHA-256、Manifest 和文件白名单校验。安装采用临时目录与原子切换，失败时继续使用当前可用版本。

## 本地构建

环境要求：JDK 21、Android SDK，以及项目声明的对应 SDK/Build Tools。

```bash
# FOSS Debug
./gradlew :app:assembleFossDebug

# GMS Debug
./gradlew :app:assembleGmsDebug
```

稳定版 APK 由仓库的 GitHub Actions 工作流构建和签名，不在仓库中保存签名文件或口令。

## 同步原作者更新

```bash
git fetch upstream
./scripts/sync-upstream.sh --dry-run
```

确认报告后再执行实际同步。`upstream` 指向原作者仓库，`origin` 指向 TouhouGO 中文增强分支。

## 数据来源

- [AniList](https://anilist.co/)：作品、角色、制作人员和用户媒体数据。
- [Bangumi](https://bgm.tv/)：经过严格匹配的中文标题、简介和人物关联数据。
- [Wikidata](https://www.wikidata.org/)：部分人物与制作人员的中文名称。
- [TouhouGO/anilist-zh-cn-userscript](https://github.com/TouhouGO/anilist-zh-cn-userscript)：中文资源聚合与自动化数据流程。

## 原版项目

- 原版 Android 项目：[axiel7/AniHyou-android](https://github.com/axiel7/AniHyou-android)
- 原版项目主页：[axiel7.github.io/anihyou](https://axiel7.github.io/anihyou/)
- Google Play：[AniHyou](https://play.google.com/store/apps/details?id=com.axiel7.anihyou)
- F-Droid：[com.axiel7.anihyou](https://f-droid.org/packages/com.axiel7.anihyou)
- iOS 版本：[axiel7/AniHyou-iOS](https://github.com/axiel7/AniHyou-iOS)
- Crowdin：[AniHyou localization](https://crowdin.com/project/anihyou)
- Discord：[AniHyou community](https://discord.gg/CTv3WdfxHh)
- 支持原作者：[Ko-fi](https://ko-fi.com/axiel7)

## 开源协议

本项目遵循 [GPL-3.0](LICENSE) 许可证。原项目版权与署名归原作者及贡献者所有。
