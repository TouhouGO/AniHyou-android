# FOSS Release 发布

手动运行 `Publish FOSS release`，输入与 `version.properties` 完全一致的版本。
工作流固定当前主分支提交，使用 JDK 21 和已校验的 Gradle 分发包。

## 签名配置

仓库 Actions Secrets 需要四项：

- `KEYSTORE_B64`：原有 Release keystore 文件的 base64 内容。
- `KEYSTORE_PASSWORD`：keystore 密码。
- `KEY_ALIAS`：签名密钥别名。
- `KEY_PASSWORD`：该私钥的密码。

这些值只通过 Secrets 或本地进程环境提供，不写入仓库、命令参数、构建日志和
发布附件。工作流会在受限临时文件中解码 keystore，签名结束后清理。不要为了
完成发布改用 Debug 签名或临时生成新密钥；签名变化会影响已安装版本覆盖升级。

发布工具固定核对旧版 1.7.1 的公开签名证书 SHA-256：

```text
c868d6a46cb0468d1fa87a9a52380db3c534dfee00e7cb356ac1264d3e7ea57e
```

## 构建与发布检查

1. 检查版本、源码提交和干净工作区。
2. 运行 Release dry-run，禁止设备测试、安装和 baseline profile 生成任务。
3. 运行 `assembleFossRelease` 和 Release lint，保留已提交的 baseline profile。
4. 记录源码提交、源码树、五种架构 APK 和 Gradle 元数据的哈希。
5. 签名前重新验证构建记录；签名后检查包名、版本、非调试状态、旧签名证书、
   baseline 文件与完整内置汉化资源。
6. 上传 draft Release，回读核对每个附件，再正式发布。已有同名附件只验证，
   不覆盖；版本、标签或内容冲突时停止。

发布附件包含五种架构 APK、`SHA256SUMS` 和 `release-manifest.json`。
普通手机可优先选择 universal APK。检查不涉及模拟器或设备安装；主机检查
不能替代运行时验证。

## 本地准备

在干净工作区执行以下命令，以生成与当前提交绑定的 unsigned APK：

```sh
python3 scripts/release/release_foss.py build \
  --version 1.8.0 --head "$(git rev-parse HEAD)"
```

补齐签名环境后调用 `sign`，再用 `publish` 发布。运行脚本的 `--help` 查看参数。
本地路径不应写入发布说明，keystore 和密码不得随 APK 上传。

当前仅完成主机构建和发布工具验证；实际签名与 v1.8.0 发布需要可用的原签名配置。
