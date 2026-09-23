# 只读后端源码接入

本次只把只读后端留在源码里，没有打包、安装，也没有切换运行时。

- 只读 tool：inventory 结果固定为 `mode=read_only`、`mutations_enabled=false`、`session_authenticated=false`、`ready=false`。`ok` 和 `query_supported` 只表示这一次查询成功，不表示会话可用。
- 权限与超时：classpath 为空、含 NUL 或换行时直接拒绝。探针命令固定为 `/system/bin/timeout -k 1s 8s` 下的 `app_process`，只跑 `vd.android.ReadOnlyProbe --inventory`。超时、非零退出、输出超限和协议错误都返回失败，不打开会话。
- R8：`app_process` 按字面类名启动探针，看不到 shell 字符串引用。现有规则保留 `ReadOnlyProbe.main`，以及 `AndroidBridge`、`AndroidReadOnlyBackend`（含内部类）、`TaskHandle`、`ActionResult`、`CloseSurface`，避免 release 裁剪或改名。
- 协议：有效 focused task 必须属于非空 roots，因此 `rootCount` 和 `rootCount2` 都必须大于 0；任一为 0 都拒绝。
- 没有执行构建或测试。
- 完整关闭仍被阻断。探针头 `PROBE_ONLY_NOT_CLOSE_AUTHORIZATION` 不是关闭授权。
