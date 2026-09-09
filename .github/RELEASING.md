# Eta 发布流程

## 配置签名 Secrets

发布证书和密码不得提交到 Git。首次使用前，在仓库的
`Settings > Secrets and variables > Actions` 中添加：

- `ETA_RELEASE_KEYSTORE_BASE64`：发布证书的 Base64 文本
- `ETA_RELEASE_STORE_PASSWORD`：KeyStore 密码
- `ETA_RELEASE_KEY_ALIAS`：Key alias
- `ETA_RELEASE_KEY_PASSWORD`：Key 密码

macOS 可以用下面的命令复制证书的 Base64 文本：

```bash
base64 < /path/to/eta-release.p12 | tr -d '\n' | pbcopy
```

也可以使用 GitHub CLI。密码类 Secret 不要直接写在命令参数中，运行命令后按提示输入：

```bash
base64 < /path/to/eta-release.p12 | gh secret set ETA_RELEASE_KEYSTORE_BASE64
gh secret set ETA_RELEASE_STORE_PASSWORD
gh secret set ETA_RELEASE_KEY_ALIAS
gh secret set ETA_RELEASE_KEY_PASSWORD
```

## 构建与发布

发布证书不要提交到仓库。本地把 `keystore.properties` 放到仓库根目录，或把同一份 PKCS12 配进 Actions Secrets。

以下情况会构建经过签名验证的 Release APK，并作为 Actions Artifact 保存 14 天：

- 向 `main` 推送提交
- 推送 `v*` 标签
- 在 GitHub 的 `Actions > Eta Build` 中手动运行

工作流不会创建、修改或发布 GitHub Release。

正式发布前先更新 `versionCode` 和 `versionName`，然后创建与
`versionName` 对应的标签。例如发布 `2.2.2`：

```bash
git tag v2.2.2
git push origin v2.2.2
```

标签推送后，等待 `Eta Build` 工作流完成，然后：

1. 从该次工作流的 `Artifacts` 下载 `app-release.apk`。
2. 在仓库的 `Releases > Draft a new release` 中选择已有标签。
3. 填写 Release Notes 并上传 APK。
4. 检查版本、说明和附件后，由维护者手动发布。
