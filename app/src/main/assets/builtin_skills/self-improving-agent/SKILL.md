---
name: self-improving-agent
description: Built-in self-improvement loop. Use to record non-trivial failures, user corrections, and reusable best practices into structured learnings.
---

# Self Improving Agent

This built-in skill is fixed-injected for agent runs.

Use it to maintain a lightweight learning loop without interrupting the user's main task.

The runtime may auto-read this skill after a tool failure and auto-record the failure into `data/ERRORS.md`.

## When To Record

Record after the immediate task is safe or complete when any of these happens:

1. a non-trivial command, tool, or device action fails
2. the user corrects your understanding, path, rule, or project assumption
3. you discover an outdated runtime/project convention
4. you find a reusable workaround or best practice that will likely save future retries
5. the same mistake repeats in the same task or across tasks

Do not record ordinary chat, tiny one-off slips, or anything the user asked not to save.

## Default Storage

- skill-local learnings: `self-improving-agent/data/`
- error log: `self-improving-agent/data/ERRORS.md` (auto-managed by the failure hook)

## Logging Workflow

1. Finish or stabilize the current user-facing step first.
2. The runtime automatically logs structured errors after tool failures — you do not need to manually write to ERRORS.md.
3. Use skill scope by default.
4. Use `learning` for corrected knowledge or best practices.
5. Use `error` for concrete failures with stderr, HTTP errors, stack traces, or invalid assumptions.
6. Use `feature` for recurring capability gaps the user actually wants.

## Memory Promotion

Promote a lesson into a stable rule only when it is short, reusable, and broadly applicable.

Good candidates:

- a rule like "遇到 X 先检查 Y"
- a stable workspace convention
- a long-term user preference the user explicitly wants remembered

Prefer this order:

1. errors are auto-logged into the skill data by the failure hook
2. review `data/ERRORS.md` when similar failures recur
3. distill stable rules into your system prompt or user-facing guidance

## Output Discipline

- keep summaries short and specific
- include the concrete command/tool/context that failed
- include the corrected rule, not only the symptom
- avoid logging secrets, tokens, and personal data
