# VD 只读后端远程校验

`vd` 只读后端（`app/src/main/java/vd/`）的自动化校验放在 GitHub Actions 的
`.github/workflows/vd-integration-check.yml` 里，在远程 Ubuntu runner 上用与本地一致的
JDK 25 + Android SDK 配置跑 debug 单元测试并构建 debug APK。

这里是**编译与 JVM 单元测试**层面的校验，不安装、不发布、不推送，也不需要任何签名密钥。

## 触发方式

仅支持手动触发：

1. 打开仓库的 `Actions` 页面。
2. 选择 `Eta VD Integration Check`。
3. 点 `Run workflow`。

工作流没有任何 `push` / `pull_request` / `tag` 触发器，不会自动运行。

## Runner 与工具链

与现有 `build-debug.yml` / `android-release.yml` 保持一致：

- runner：`ubuntu-latest`
- JDK：Temurin 25（`actions/setup-java`）
- Gradle：`gradle/actions/setup-gradle`（`cache-provider: basic`）
- Android SDK：`sdkmanager --channel=3 "platforms;android-37.0"`，与
  `app/build.gradle.kts` 的 `compileSdk = 37` 对应

## 运行内容

| 步骤 | 命令 | 说明 |
| --- | --- | --- |
| 运行 Debug 单元测试 | `./gradlew --no-daemon --no-configuration-cache :app:testDebugUnitTest` | 运行 `app/src/test` 下的 JVM 单元测试，包含 `app/src/test/java/vd/android/ProbeInventoryProtocolTest.java` |
| 构建 Debug APK | `./gradlew --no-daemon --no-configuration-cache :app:assembleDebug` | 产出 `app/build/outputs/apk/debug/*.apk` |

签名的 Debug 构建不需要 secret：`app/build.gradle.kts` 只有在提供了 `keystore.properties`
或 `ETA_RELEASE_*` 环境变量时才会切换签名配置，因此缺失签名密钥时 `assembleDebug`
使用默认 debug 签名正常构建。

## 产物

工作流上传两个 Actions Artifact（保留 7 天）：

- `vd-unit-test-reports`：`app/build/test-results/testDebugUnitTest/` 和
  `app/build/reports/tests/testDebugUnitTest/`（单测报告，`if: always()`）
- `vd-debug-apk`：`app/build/outputs/apk/debug/*.apk`（debug APK）

## 覆盖范围

- `app/src/main/java/vd/android/`：`ReadOnlyProbe`、`AndroidBridge`、
  `AndroidReadOnlyBackend`、`ProbeInventoryProtocol`
- `app/src/main/java/vd/close/`：单会话关闭状态机与句柄类型
- 对应的单元测试，主要是 `ProbeInventoryProtocolTest`

只读契约的语义见 [`vd-readonly-backend-integration.md`](vd-readonly-backend-integration.md)。

## 明确不做的事

- 不安装 APK、不执行 `adb install`，也不连接任何设备
- 不创建或发布 GitHub Release，不推送标签，不修改远端分支
- 不需要 `ETA_RELEASE_*` 或任何签名 secret
- 不切换 App 运行时，不做真机 / root / `app_process` 端到端验证

## 边界

远程校验只证明代码在 JDK 25 下能编译、能通过 JVM 单元测试、能打出 debug APK。
它**不**证明 `root`、`app_process` 探针命令或 ActivityTaskManager 反射在真机上可用，
也不替代任何设备侧验收。
