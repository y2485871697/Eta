# VirtualDisplay owner 协议（vd.runtime）

本次只落地 root `app_process` 侧 owner 的源码，位于 `app/src/main/java/vd/runtime/`。
**没有执行任何本地编译、打包或设备测试**，APK 侧的启动与集成由主流程处理。

## 目标

在 root `app_process` 进程里真正持有一个虚拟副屏，并通过已认证的本地 IPC 提供
`status / launch / input / snapshot / handoff` 等操作。owner 只管理自己创建的那一个显示，
命令串行执行，任务按 binder / task id 保留，释放前必须确认副屏为空。

不是骨架：显示创建、状态查询、启动、注入、截图都给出真实实现；未实现的操作
（当前是 `handoff`）显式返回失败，而不是伪装成功。

## 进程模型

- 入口：`vd.runtime.VirtualDisplayOwnerMain`，由 root 通过 `app_process` 按字面类名启动。
- 启动即校验 `Process.myUid() == 0`，否则 `VD_OWNER_ERROR code=ROOT_REQUIRED`。
- `Looper.prepareMainLooper()` 之后才做显示工作；ImageReader 的帧回调与所有命令都跑在
  这个主 looper 上，因此命令天然串行。
- 显示通过反射调用隐藏 API `DisplayManagerGlobal.createVirtualDisplay` 创建（源码仍按公开
  SDK 编译）。保留的 owner 是 `ImageReader`：它的 `Surface` 就是副屏输出面，存活于整个会话。
- 显示 id 来自创建结果，uniqueId 在创建时动态生成（`vd-owner-<pid>-<8字节hex>`），命令只能
  作用于该 id，传入其它 id 返回 `DISPLAY_REBOUND`。
- 单个 owner 实例 = 单个显示 = 单个会话。

### 启动参数

```
app_process /system/bin vd.runtime.VirtualDisplayOwner \
    --socket <abstract-name> --allow-uid <app-uid> \
    [--width W] [--height H] [--density DPI] [--name NAME]
```

- `--socket`：Linux abstract namespace 名称，必填；合法字符同标识符（字母数字 `._-/:@`）。
- `--allow-uid`：允许连接的客户端 uid，必填且必须大于 0。
- `--width/--height/--density`：可选；缺省时从设备当前 `DisplayInfo` 读取，**不写死** 任何分辨率。
- 未通过校验打印 `VD_OWNER_ERROR code=ARGS` 并退出码 2。

退出码：2=参数错误，3=非 root，4=显示创建失败，5=socket 绑定失败。成功路径返回 0。

### 握手

创建显示、绑定 socket 成功后，owner 向 stdout 打印**一行**并 flush：

```
VD_OWNER_READY v=1 socket=<name> token=<32位hex> pid=<pid> uid=0 allowUid=<uid> displayId=<id> uniqueId=<...>
```

失败则打印 `VD_OWNER_ERROR code=<CODE> message=<...>` 并以非零退出码结束。不存在“半就绪”。
进程结束前打印 `VD_OWNER_STOPPED displayId=<id> released=<true|false>`。

> token 只在这一行出现，是客户端唯一的秘密。它经 root shell 的 stdout 传给调用方，除此之外
> 不再回显，也不会写进任何响应。

## 传输与认证

- 抽象本地 socket（`LocalServerSocket` + `LocalSocketAddress.Namespace.ABSTRACT`），不是文件，
  其它应用无法用普通路径打开。
- 每行一个 UTF-8 JSON 对象，`\n` 结束；单行超过 64 KiB 直接拒绝（`REQUEST_TOO_LARGE`）。
- 每个请求都带 `v`（版本）、`op`（操作）、`token`（会话令牌）。令牌用常量时间比较。
- 双重校验：连接建立时用内核上报的 `peerCredentials.uid` 与 `--allow-uid` 比对，不符返回
  `PEER_REJECTED` 并断开；随后每个请求再校验 token，不符返回 `AUTH_REJECTED` 并断开。
- **单会话**：同时只允许一条活动连接；第二条返回 `SESSION_BUSY` 并断开。当前连接断开后，
  允许新的连接（顺序复用）。

## 操作

请求：`{"v":1,"op":"...","token":"...", ...}`。
响应：`{"v":1,"ok":true,"op":"...", ...}` 或
`{"v":1,"ok":false,"op":"...","error":"CODE","message":"..."}`。
`ok=true` 一定带有操作体；失败一律 `ok=false` + `error`。

### status
返回：`ready`、`displayId`、`uniqueId`、`name`、`width`、`height`、`densityDpi`、`flags`、
`ownerPackage`、`frameCount`、`hasFrame`、`frameTimestampNs`、`session`、
`sourceState`（`empty|occupied|unknown`）、`sourceEmpty`/`sourceTaskCount`（状态已知时）、
`sourceError`（未知时）、`retainedTaskIds`、`supported`、`missing`。

`missing` 明确列出未实现的操作（当前为 `["handoff"]`）。

### launch
请求字段：`package` / `component` / `action` 至少一个；可选 `categories`（数组，≤8）、
`flags`（int）、`displayId`（必须等于本 owner 的 id）。
实现方式：以 **参数数组**（无 shell）调用 `/system/bin/am start --display <id> ...`，
包名/组件/动作均按标识符白名单校验，用户文本无法变成额外选项。退出码非 0、或输出含
`Error:` / `Exception` / `Permission Denial` 都返回 `LAUNCH_FAILED`。

### input
请求字段：`kind` ∈ `tap|swipe|key|text`，加对应坐标 / 键值 / 文本，可选 `displayId`。
实现方式：参数数组调用 `/system/bin/input -d <id> ...`。坐标与 keyCode 为整数范围校验；
文本作为**单个字面参数**传入，不经 shell。失败返回 `INPUT_FAILED`。

### snapshot
请求字段：可选 `include`（默认 true）、`maxBytes`（上限 6 MiB）。
返回 `width`、`height`、`format=png`、`frameCount`、`timestampNs`，`include=true` 时附带
`bytes` 与 `data`（base64 PNG，`NO_WRAP`）。无帧返回 `NO_FRAME`，超限返回 `IMAGE_TOO_LARGE`。
帧由保留的 ImageReader 复制而来，转换按需进行。

### release
要求副屏当前**确认为空**：先枚举该 displayId 上的 root task，任一枚举失败或 binder 被替换
都返回 `SOURCE_STATE_UNKNOWN`（保守拒绝）；非空返回 `SOURCE_NOT_EMPTY`。通过后关闭
ImageReader、释放 VirtualDisplay，置会话为 released，并让 owner 退出。
**不会** kill 任何 task 或用户进程。

### handoff
未实现。返回 `ok=false` + `error=NOT_IMPLEMENTED_HANDOFF`。`status.missing` 同步标注。
这正是 `release` 在前台任务仍留在副屏时会被 `SOURCE_NOT_EMPTY` 拦住的原因：迁移能力缺失时
owner 选择拒绝释放，而不是把用户的 task 一起弄丢。

## 错误码

`PROTOCOL`、`REQUEST_TOO_LARGE`、`AUTH_REQUIRED`、`AUTH_REJECTED`、`PEER_REJECTED`、
`SESSION_BUSY`、`UNKNOWN_OP`、`NOT_IMPLEMENTED_HANDOFF`、`DISPLAY_NOT_READY`、
`DISPLAY_REBOUND`、`SOURCE_NOT_EMPTY`、`SOURCE_STATE_UNKNOWN`、`LAUNCH_FAILED`、
`INPUT_FAILED`、`SNAPSHOT_FAILED`、`IMAGE_TOO_LARGE`、`NO_FRAME`、`ALREADY_RELEASED`、
`INTERNAL`。

## 任务身份

`OwnerTaskInventory` 只调用 `ActivityTaskManager.getAllRootTaskInfos()`（只读），
`OwnerTaskRegistry` 按 task id 保留真实 token binder，用于发现“同 id 换了 binder”的替换。
任何反射失败都折算为 `SOURCE_STATE_UNKNOWN`，绝不把失败读成“空”。

## 安全边界与已知不确定点

- 隐藏 API 签名随版本变化：`createVirtualDisplay` 依次尝试“带 `VirtualDisplayConfig`”、
  “带 `String uniqueId`”、“旧 listener 形式”三种形态，都不匹配时返回 `DISPLAY_NOT_READY`。
- `IVirtualDisplayCallback` 是 AIDL 接口，无法按公开 SDK 实现其 `Stub`。这里用 `Proxy` 实现
  接口，`asBinder()` 返回一个真实 `Binder`，其 `onTransact` 对所有事务返回 `true`。这足以让
  显示服务持有回调令牌；生命周期回调按“尽力通知”处理。
- `packageName` 传 `android`（uid 0 名下），以通过显示服务的“包名需属于调用 uid”校验。
- `release` 之后的显示由进程退出兜底；绑定 socket 失败时以非零退出码结束进程。
- 该实现尚未在设备上验证，隐藏 API 差异可能需要在集成阶段调整。
- 帧转换在 owner 主 looper 上进行，超大分辨率会短暂占用该线程。

## 集成待办（非本切片）

- `app_process` 按字面类名加载 owner，release 构建需要为
  `vd.runtime.VirtualDisplayOwnerMain`（及其反射触达的 `vd.runtime.*`）保留 keep 规则；
  本切片不修改 `app/proguard-rules.pro`。
- 客户端侧（`VirtualDisplaySession` / `VirtualDisplayBackendBridge`）目前仍是拒绝态，需要主流程
  接入：用 root 启动 owner、读握手行、用 token 连接 abstract socket。

## 附录：可复制的纯 JVM 测试参考

以下类不依赖设备，可放在 `app/src/test/java/vd/runtime/`（与实现同包，才能触及包私有成员）：

```java
// OwnerProtocol.parseRequest / 校验
assertEquals("status", OwnerProtocol.parseRequest(
        "{\"v\":1,\"op\":\"status\",\"token\":\"ab\"}").op);
assertThrows(OwnerProtocolException.class, () ->
        OwnerProtocol.parseRequest("{\"v\":2,\"op\":\"status\",\"token\":\"ab\"}")); // 版本
assertThrows(OwnerProtocolException.class, () ->
        OwnerProtocol.parseRequest("{\"v\":1,\"op\":\"handoff\"}"));             // 缺 token
assertFalse(OwnerProtocol.isSafeIdentifier("pkg;rm -rf /"));
assertTrue(OwnerProtocol.isSafeIdentifier("com.example/.Main"));
assertTrue(OwnerProtocol.constantTimeEquals("deadbeef", "deadbeef"));
assertFalse(OwnerProtocol.isSafeText("a\nb", 8));

// ShellCommands：argv 形态 + display=0 保护（第一段是 ShellCommands.INPUT 常量）
assertArrayEquals(
        new String[]{ShellCommands.INPUT, "-d", "12", "tap", "3", "4"},
        ShellCommands.inputTapArgv(12, 3, 4));
assertThrows(IllegalArgumentException.class, () -> ShellCommands.inputTapArgv(0, 1, 2));
assertThrows(IllegalArgumentException.class, () -> ShellCommands.inputTextArgv(-1, "x"));

// 编码行里不含裸换行：org.json 会转义
String line = OwnerProtocol.encodeLine(OwnerProtocol.fail("status", "X", "a\nb"));
assertFalse(line.contains("\n\n"));
```
