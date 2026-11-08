# 项目协作约定

- 本仓库是 `axiel7/AniHyou-android` 的 fork；保留官方历史、版权和许可证。
- 我们新增提交的 author 和 committer 必须均为 `TouhouGO <93237754+TouhouGO@users.noreply.github.com>`。
- 提交前核对 `git var GIT_AUTHOR_IDENT` 与 `git var GIT_COMMITTER_IDENT`；显式设置
  `GIT_AUTHOR_NAME`、`GIT_AUTHOR_EMAIL`、`GIT_COMMITTER_NAME`、`GIT_COMMITTER_EMAIL`，
  防止环境变量或本机默认值覆盖。推送前再次检查实际提交元数据。
- 禁止推回旧 fork 的历史分支、备份标签或私人身份提交。不要在文档、日志或 PR
  中写本机用户名、绝对用户目录、私人邮箱和旧提交入口。
- 上游更新通过正常 fetch/merge 到独立集成分支，经检查和 PR 审阅后合并；禁止
  reset 或强推主分支来同步上游。第三方官方提交保留原始署名。
- 未获授权时不操作模拟器、设备、adb、安装/启动任务或连接测试。使用主机测试、
  编译和经过 dry-run 任务图检查的 Debug assemble。
- 原始 AniList 模型、缓存、筛选键和变更请求保持原值；汉化在独立显示和搜索层实现。
- 中文搜索必须同时保留速度、别名/繁简/续作覆盖、完整分页和在线补充。
- 新 JSON 资源频道为 `catalog-latest`；发布前先验证日期版资源，再更新 manifest。
