# VD integration candidate — not production accepted

This isolated branch wires restored Eta task-surface settings and GUI dispatch to the packaged root `vd.runtime.VirtualDisplayOwnerMain`, through authenticated `VirtualDisplayOwnerClient`. It is not merged into the main Eta checkout and no installed module has been replaced.

## Actual path

1. The run freezes its surface preference. BACKGROUND routes ordinary GUI calls before physical-screen preflight; unsupported operations fail rather than falling back to display zero.
2. Session startup invokes the real client, verifies READY/socket credentials, and queries status. Root creates a retained ImageReader-backed display; no external `/system/bin/vd` is used.
3. Supported calls: exact-package launch, screenshot, coordinate tap/area/swipe/long press, BACK/ENTER/PASTE, clipboard text paste, bounded wait. Nodes and unsupported system tools explicitly fail.
4. `keep_virtual_result` marks exact owner task IDs (or resolves packages launched in this session). It is local selection, not a nonexistent owner `keep` protocol.
5. `finish_virtual_session` calls owner handoff, checks `handedOff`, then calls release and checks `released`. Only then is completion reported. A verified-empty unselected session may be released without destroying tasks.
6. Handoff uses a distinct, excluded-recents source cover, hides selected fresh tasks, moves them to display zero, parks them below the observed main foreground, restores default fresh-task flags, removes verified intermediates and cover, verifies source emptiness, and releases.

## Deliberate constraints

- Exclusive experimental session; **no system_server atomic admission fence or desktop-icon interception**. Do not launch/clear target apps concurrently.
- Existing active/recent tasks for the target package are rejected. New launch provenance requires a unique base-intent URI and standalone task shape. This is not support for adopting an arbitrary existing user task.
- No process killing, package force-stop, automatic release on IPC failure, or fallback to physical display.
- Partial handoff/release uncertainty retains owner/display. Nonempty cancelled sessions are held; automatic recovery after client/app death is not implemented. Do not restart owners or kill processes to conceal this state.
- Restoring hidden=false/focusable=true is restricted to fresh tasks, not general prior-state restoration.
- The prompt advisory is not a sandbox against separately enabled root-terminal operations.
- Only foreground physical tools require the physical accessibility service. Virtual capability publication uses the same frozen surface as execution.
- Snapshot metadata checks source base/top packages against the run's screenshot exclusions; unknown metadata fails closed.

## Validation evidence

- No local Gradle/Java/Kotlin build executed for this candidate.
- GitHub Actions run `35898346141`, SHA `bec200e`: compilation succeeded; 2120 unit tests, one failure (missing new tool icon). Icons corrected in `59f1d69`.
- GitHub Actions run `35899778183`, SHA `59f1d69`: unit tests and Debug APK build both passed. Subsequent lifecycle/capability changes require a new run.
- Source reviews are not runtime certification. Prior isolated calculator/XHS migration success does not validate the new Eta client/owner/anchor chain.
- Full installed-Eta end-to-end acceptance remains pending; no production-ready claim.
