#!/bin/sh
# Build on Linux; never install, stop, or restart anything on a device.
set -eu

fail() {
    printf '错误：%s\n' "$*" >&2
    exit 1
}

[ "$#" -eq 0 ] || fail '此脚本不接受参数；输出固定在 build/vd-preview-gateway-arm64/。'
[ "$(uname -s)" = Linux ] || fail '请在 Linux 上运行此脚本。'

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -- "$script_dir/.." && pwd -P)
source_dir="$repo_root/tools/vd-preview-gateway"
output_dir="$repo_root/build/vd-preview-gateway-arm64"
readme="$repo_root/docs/vd-preview-gateway/README.md"

for command in go node git sha256sum mktemp; do
    command -v "$command" >/dev/null 2>&1 || fail "缺少命令：$command"
done
for file in "$source_dir/go.mod" "$source_dir/LICENSE" "$readme"; do
    [ -s "$file" ] || fail "缺少必需文件：$file；请先集成完整的 MIT 网关源码和交付说明。"
done
grep -q 'MIT License' "$source_dir/LICENSE" || fail '网关 LICENSE 未识别为 MIT；请人工核对，不能遗漏或替换上游许可。'

# Use the installed toolchain; do not silently download another Go version.
# Ignore inherited cross-compilation/test-filter flags and external workspaces.
export GOTOOLCHAIN=local GOWORK=off GOFLAGS=
unset GOOS GOARCH GOARM GOARM64
node --eval 'if (Number(process.versions.node.split(".")[0]) < 18) process.exit(1)' || fail '需要 Node.js >= 18，不能跳过前端行为测试。'
go_version=$(go env GOVERSION)
if ! printf '%s\n' "$go_version" | awk -F. '
    /^go[0-9]+\.[0-9]+/ {
        sub(/^go/, "", $1)
        if ($1 + 0 > 1 || ($1 + 0 == 1 && $2 + 0 >= 22)) valid = 1
    }
    END { exit !valid }
'; then
    fail "需要已安装的 Go >= 1.22，实际为：$go_version"
fi
printf '使用 Go：%s\n源码目录：%s\n输出目录：%s\n' "$go_version" "$source_dir" "$output_dir"

source_commit=$(git -C "$repo_root" rev-parse --verify HEAD)
source_state=clean
if [ -n "$(git -C "$repo_root" status --porcelain --untracked-files=normal -- \
    tools/vd-preview-gateway scripts/build-vd-preview-gateway.sh \
    docs/vd-preview-gateway/README.md .github/workflows/build-debug.yml)" ]; then
    source_state=modified
fi

mkdir -p "$repo_root/build"
[ ! -L "$output_dir" ] || fail '输出目录不能是符号链接。'
stage_dir=$(mktemp -d "$repo_root/build/.vd-preview-gateway-arm64.XXXXXX")
trap 'rm -rf -- "$stage_dir"' EXIT
trap 'exit 1' HUP INT TERM

(
    cd "$source_dir"
    # Tests run on the Linux host, before cross-compiling; failures are fatal.
    go test ./...
    GOOS=linux GOARCH=arm64 CGO_ENABLED=0 \
        go build -trimpath -ldflags='-s -w' -o "$stage_dir/vd_server" ./
)
[ -s "$stage_dir/vd_server" ] || fail '未生成 vd_server。'

# Preserve the complete upstream copyright and MIT permission notice.
cp "$source_dir/LICENSE" "$stage_dir/LICENSE"
cp "$readme" "$stage_dir/README.md"
printf '%s\n' "$source_commit" > "$stage_dir/SOURCE_COMMIT.txt"
{
    printf 'source_commit=%s\n' "$source_commit"
    printf 'relevant_worktree_state=%s\n' "$source_state"
    printf 'source_directory=tools/vd-preview-gateway\n'
    printf 'go_version=%s\n' "$go_version"
    printf 'target=linux/arm64\nCGO_ENABLED=0\n'
    printf 'build_command=GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o <output>/vd_server ./\n'
    printf 'test_command=go test ./...\ntest_result=passed\n'
    printf 'built_at_utc=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    printf 'github_run_id=%s\n' "${GITHUB_RUN_ID:-local}"
    printf 'github_run_attempt=%s\n' "${GITHUB_RUN_ATTEMPT:-local}"
} > "$stage_dir/BUILD-INFO.txt"
(
    cd "$stage_dir"
    sha256sum vd_server LICENSE README.md SOURCE_COMMIT.txt BUILD-INFO.txt > SHA256SUMS
    sha256sum -c SHA256SUMS
)

# Publish only after tests, compilation, licensing, and checksums succeed.
# Move files rather than building over any pre-existing source-tree binary.
mkdir -p "$output_dir"
for file in vd_server LICENSE README.md SOURCE_COMMIT.txt BUILD-INFO.txt SHA256SUMS; do
    mv -fT -- "$stage_dir/$file" "$output_dir/$file"
done
printf '\n构建完成（未安装、未操作设备）：%s\n' "$output_dir"
cat "$output_dir/BUILD-INFO.txt" "$output_dir/SHA256SUMS"
