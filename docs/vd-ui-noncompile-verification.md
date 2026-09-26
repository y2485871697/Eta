# 副屏界面与内存优化：非编译验证记录

记录日期：2026-09-26。基线：`09146fc6`；分支：`fix/vd-settings-quiet-ui`。

## 本轮补充修复

- `AgentRuntimeResultStore.add`：在生成实体、编码 transcript 前检查已接收 ACK；仍保留编码后与入库同锁的第二次检查。空 result.runId 使用 handoff.id，与入库映射一致。
- `VirtualDisplayRecoveryScreen`：页面真正退出组合时停止预览 HTTP 服务并清空单例；不在 ON_PAUSE/ON_STOP 停止，避免打开外部浏览器就中断预览。此清理不迁移或释放真实副屏，也不等待或中断正在进行的 capture。

## 已执行的非编译检查

命令：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -W error::ResourceWarning -m unittest discover -s app/src/test/python -q
git diff --check
```

结果：29 项 Python 检查通过；差异空白检查通过。其中新增或扩充的检查覆盖：

- 实际迁移 SQL 在临时 SQLite 表上保持旧数据、默认 false，以及新值读写。
- Room 声明与迁移默认值一致，三种语言的完成提示资源存在且唯一。
- 240 组随机数据及其所有后缀的 compact JSON 长度算式，按 UTF-16 单位计量并覆盖中文、emoji、控制字符和转义。此项是数学参考模型，不执行 Kotlin 编码器。
- ACK 前后检查的源码顺序，以及预览的 onDispose 清理且不在暂停时停止。源码契约检查不等同运行时验证。

## 已补充但未执行的 Kotlin 用例

- `AgentConversationCodecEquivalenceTest`：2 项，包含冻结旧算法、72 组输入和多种容量边界对照，以及最新保护回合超限行为。
- `AgentRuntimeResultStoreTest`：2 项新增用例，分别覆盖 ACK 已存在时不读取 transcript（含空 runId 回退）、编码期间到达 ACK 仍不重新入库。

没有运行 Gradle、Kotlin 编译、Android 单测或 CI；上述 Kotlin 用例不能标记为通过。

## 静态审查

- 运行时浮层与完成提示：PASS，任务 `cc46aa57-a42e-4e96-8327-ded2948108b5`。冻结任务模式、严格交接回执、取消/失败保护、提示去重与正文保留均已审阅。
- 编码器、ACK 与存储：PASS，任务 `087f780d-82da-4866-84ff-cb53fcf12818`。
- 设置与滚动：任务 `e9ee289b-030a-4475-9a04-4bba6ff2d010` 最初发现预览离页清理缺口；其余检查未发现阻断。修复后的定点复核 `9c9b6d70-1848-4944-b334-54d49efd800a` 为 PASS。

父级另外核对了实际源码中的交接布尔条件、提示过滤和去重、HTTP stop 行为、测试 DTO 参数及 ACK 检查顺序。审查仅针对可见源码，不代表编译或实机通过。

## 未验证边界

- ACK 去重保证仍限于同进程且标识尚在既有缓存中（32 条、12 小时）；不是跨重启的永久去重机制。本次不修改该原有契约。
- 编码优化减少反复全量 JSON 分配；仍存在单条编码、清洗列表和最终编码分配，不能宣称 OOM 根因已完全排除。
- SQLite 检查使用临时简化表，尚未验证真实 Room schema、APK 升级迁移或 Android 生命周期。
- 未验证真实副屏浮层、浏览器回退、取消/失败并发、长会话堆占用及滑动帧率。上述项目需要获准构建并安装新版本后验收；旧提交的 CI 结果不能覆盖本轮改动。
- 本轮仅本地提交；没有推送、编译、触发 CI、安装、强制 GC、重启，也没有变更网关或操作真实副屏会话。
