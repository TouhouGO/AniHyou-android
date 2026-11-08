# AniHyou 中文本地化整分支复核

复核日期：2026-11-07
复核范围：当时本地化实现的完整差异；生成目录通过来源、内容哈希及校验逻辑检查。
该历史复核不代表当前版本重新通过检查。

## 结论

**代码正确性：未发现 P1 或 P2 问题。** 在静态完整分支复核、相关生产代码调用链和现有验证证据范围内，建议不发起代码修复轮次。

**本地交付判断：代码层面通过。** 这不等于设备运行、实时网络、性能、生产签名或 OTA 发布验收通过；这些证据限制在文末单列，不能混写成代码缺陷。

本轮没有构建、重跑测试、运行模拟器/设备、执行 adb、修改账号、读取签名密钥、推送或发布。先前记录的主机测试、APK 组装、ZIP 资源核对、Wear 编译和 v2 APK 签名验证作为既有证据接受，未独立复验。

## 高优先级发现

无。

下列路径已沿实际调用链核对，未发现可复现的跨模块回归：

1. **AniList 原始身份、Apollo 缓存、写操作及过滤键未被本地化层替换。**
   Apollo 客户端仍使用原有 service-scoped normalized cache（`core/network/.../NetworkModule.kt:34-53`）。标题投影在 Compose 边界读取，搜索结果、详情和列表更新的复制仍以原始 `id`、`mediaId`、原始 tag key 和原始标题对象为准（`core/ui/.../TitleDisplay.kt:9-23`、`feature/explore/.../SearchViewModel.kt:316-345`、`feature/mediadetails/.../MediaDetailsViewModel.kt:65-84`）。没有发现显示设置写入 Apollo 或修改 mutation payload 的路径。

2. **显示设置与目录切换能够跨 Activity、Worker、Widget 和 Wear 独立响应。**
   手机端在 Activity 边界收集目录与三个显示开关，并原子提供 `LocalizationDisplayContext`（`app/.../MainActivity.kt:107-159`）；Widget、通知 Worker 和 Wear 各自加载目录并收集本端偏好（`feature/widget/.../AiringWidget.kt:88-108`、`feature/worker/.../NotificationWorker.kt:57-120`、`wearos/.../MainActivity.kt:45-52`）。目录/标题开关变化时，应用存活期间的 Widget 刷新有显式观察器（`app/.../MainViewModel.kt:53-80`）。未发现显示开关静默改变搜索召回或原始字段。

3. **MEDIA、CHARACTER、STAFF 命名空间以及标签原始键保持分离。**
   实体键包含 kind、id 和 platform；快照拒绝重复实体、跨类型媒体证据冲突、重复 link target 和未声明来源（`core/localization/.../CatalogModels.kt:6-59`、`LocalizationSnapshot.kt:69-86`）。各 UI 调用点按 media/character/staff 使用对应实体键；标签只替换显示文本，选择和导航仍传 `tag.name`/原始键（`feature/genrestags/.../GenresTagsSheet.kt:203-217`、`feature/mediadetails/.../MediaInformationView.kt:194-219`）。同名中文标签没有合并身份。

4. **搜索覆盖、过滤、失败、重试和取消没有发现静默截断。**
   实际会话使用 `searchAllMedia`；真实 `id_in` 池以 5,000 分区，服务端每分区最多翻到第 5,000 个结果，不会丢弃后续分区（`core/localization/.../LocalizationSnapshot.kt:44-56`、`feature/explore/.../CandidatePageSession.kt:9-84`）。请求映射保留全部 AniList 媒体过滤参数，并显式传入类型和 `id_in`（`feature/explore/.../MediaSearchRequest.kt:10-67`、`core/network/.../MediaApi.kt:77-127`）。分页完整性、意外候选、空页无进展、错误重试和分区推进分别返回成功、空成功或错误。

5. **本地结果与渐进补充结果互不阻塞，迟到响应受 generation/key 保护。**
   本地结果先发布；Bangumi 发现和原文 AniList 补充位于独立会话，具有独立 loading、failure、retry、depth 和 further-discovery 状态（`feature/explore/.../SupplementalSearchSession.kt:42-192`、`SearchViewModel.kt:479-549`）。查询、类型、过滤、排序、目录激活和标题语言排序变化会使旧代次失效；发布前仍比较 generation 与完整请求 key（`SearchViewModel.kt:600-703`）。未发现自动滚动把补充分页绑定到主分页的行为。

6. **目录描述符、schema、更新 token 与事务顺序一致。**
   Kotlin、Python packager/merger 对 schema 1、实体命名空间、来源引用、媒体 kind、别名、link 唯一性、标签占位符和日期版本执行对应校验。应用只在严格 UTF-8、大小、日期、版本冲突和哈希校验后事务化处理；`AtomicFile` 写入后会显式 `fd.sync()` 并回读精确字节（`core/domain/.../LocalizationCatalogRepository.kt:43-149`、`CatalogStorage.kt:18-60`、`core/network/.../CatalogUpdateClient.kt:28-121`、`scripts/localization/merge_catalogs.py:32-202`、`package_catalog.py:14-38`）。

   下载检查捕获 generation；提交前再次检查 token；取消发生在提交前时保留当前快照，进入短提交区后使用 `NonCancellable`，持久提交成功后才发布新快照（`CatalogUpdater.kt:16-43`、`LocalizationCatalogRepository.kt:96-148`）。未发现“下载完成即视为启用”或旧下载覆盖 reset/import 的路径。

7. **DI 与独立生命周期没有明显漏绑定。**
   手机和 Wear 均注册 `appVersion`、network/repository/view-model 模块；新的 Transport、目录仓库、更新器和搜索 ViewModel 都有显式或编译期生成绑定（`app/.../App.kt:31-57`、`wearos/.../App.kt:15-36`、`core/domain/.../RepositoryModule.kt:31-57`、`core/network/.../NetworkModule.kt:24-32`、`app/.../ViewModelModule.kt:50-96`）。Assembly/Wear compile 的既有通过证据支持静态绑定完整；运行时生命周期仍属于证据限制。

## 已核对的非问题与既定边界

- 分区结果不承诺跨分区全局排序；普通 `id_in` 池在一次服务端排序/分页范围内保持服务端顺序。
- `SEARCH_MATCH` 在 ID-only 查询中明确退回 popularity，并在 UI 声明；它没有被冒充为相关性排序。
- OpenCC 只用于搜索等价折叠，不重写显示文本，也不保证语义同义词或地区用语。
- 源别名允许未知媒体类型，由 AniList 显式 `type` 过滤；已知类型冲突会被拒绝。
- 标题/人物/标签来源覆盖仍属部分数据集，不能解释为所有 AniList 实体都有准确中文值。
- Bangumi 简介仅使用显式 anime link；manga 映射与完整中文简介覆盖仍是来源边界。
- 更新文件哈希和 TLS 不构成独立发布者签名；文档已披露，不把 digest 冒充签名。
- 账号态写入、`userPreferred` 原始字段、显式 native/alias 值、自由文本活动和评论内容仍保留原文。

## 低优先级维护性说明

`SearchViewModel.kt` 当前 845 行，已经成为规划、分页、补充源、偏好和 catalog generation 的汇合点。现有分层和测试边界足以支撑本次合并；若未来再加入另一搜索来源或多种持久过滤语义，可把“请求规划与 generation 生命周期”抽成独立协调器，以减少重复状态和更新顺序维护成本。当前不应为了缩短行数而拆稳定 UI setter；这项说明不是合并阻塞项。

`git diff --check` 仅报告 `core/localization/.../LocalizationSnapshot.kt:144` 与 `feature/mediadetails/.../DescriptionSourceStatus.kt:58` 的文件尾空行。它们没有行为影响，且代码已冻结，归为未来格式化清理，不算 P2。

## 剩余证据限制

以下均未被本轮或现有主机验证闭合，不能宣称已验证：

- Android 运行时资源加载、Compose 视觉/交互、Activity/ViewModel 生命周期、返回栈和恢复行为。
- 真机/模拟器上的启动耗时、首屏、内存峰值、滚动手感、低端机索引行为、Airing Widget host、系统通知、Wear 表盘/列表运行时。
- 真实 AniList/Bangumi 服务延迟、429 顺序、重定向当前形态、完整 5,001+ 分区网络验收和生产网络 SLA。
- catalog 更新渠道当前未部署；未执行真实 OTA、升级或回滚。
- 未进行生产签名发布；虽已有主机 v2 签名结构验证，未替代 release 发布验收。
- 来源覆盖只证明已收录、已校验的记录，不证明翻译准确率、时效性或全网完整性。
- 既有 `core/ui` 的 3 个 `MarkdownUtilsTest` YouTube 期望失败属于基线遗留；本轮没有修改或重跑它们来制造绿色结论。

因此，最终表述应保持为“整分支代码复核未见 P1/P2；主机测试和 APK 打包/结构检查通过；设备、实时服务、性能与发布验收未完成”，不要扩展为生产就绪或完整中文覆盖。
