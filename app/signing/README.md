# 发布签名

发布证书不放在 Git 仓库里。

本地构建读取仓库根目录的 `keystore.properties`（已 gitignore），或环境变量 `ETA_RELEASE_STORE_FILE`、`ETA_RELEASE_STORE_PASSWORD`、`ETA_RELEASE_KEY_ALIAS`、`ETA_RELEASE_KEY_PASSWORD`。

CI 从 GitHub Actions Secrets 还原同一份证书，保证本机和 CI 打出的包可以互相覆盖安装。
