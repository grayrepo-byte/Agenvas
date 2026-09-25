#!/usr/bin/env bash
#
# 一键运行测试：前端 Vitest + 后端 Maven verify。
#
# 用法:
#   ./run-tests.sh              # 前端单元测试 + 后端 verify（单元 + 集成），并行
#   ./run-tests.sh frontend     # 只跑前端
#   ./run-tests.sh backend      # 只跑后端
#   ./run-tests.sh --unit       # 后端只跑单元测试，跳过 *IT（快，无需 Docker）
#   ./run-tests.sh --help
#
# 退出码: 0 = 所选范围全部通过; 1 = 至少一端失败; 2 = 参数错误。
set -uo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_DIR="$ROOT_DIR/backend"

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RESET=$'\033[0m'
  C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'
  C_YELLOW=$'\033[33m'
  C_BOLD=$'\033[1m'
else
  C_RESET=''
  C_RED=''
  C_GREEN=''
  C_YELLOW=''
  C_BOLD=''
fi

# 按标记取注释块，不用行号，之后改动注释也不会错位。
usage() {
  sed -n '/^# 用法:/,/^# 退出码:/p' "$0" | sed 's/^# \{0,1\}//'
}

FULL_VERIFY=1
SEL_FRONTEND=0
SEL_BACKEND=0
ANY_SELECTED=0

while [ $# -gt 0 ]; do
  case "$1" in
    frontend)
      SEL_FRONTEND=1
      ANY_SELECTED=1
      ;;
    backend)
      SEL_BACKEND=1
      ANY_SELECTED=1
      ;;
    --unit)
      FULL_VERIFY=0
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

RUN_FRONTEND=1
RUN_BACKEND=1
if [ "$ANY_SELECTED" -eq 1 ]; then
  RUN_FRONTEND=$SEL_FRONTEND
  RUN_BACKEND=$SEL_BACKEND
fi

LOG_DIR=$(mktemp -d "${TMPDIR:-/tmp}/agenvas-tests.XXXXXX")
FRONTEND_LOG="$LOG_DIR/frontend.log"
BACKEND_LOG="$LOG_DIR/backend.log"

FE_PID=''
BE_PID=''

cleanup() {
  # Ctrl+C 时不让 Maven / Vitest 子进程留在后台。
  if [ -n "$FE_PID" ]; then kill "$FE_PID" 2>/dev/null; fi
  if [ -n "$BE_PID" ]; then kill "$BE_PID" 2>/dev/null; fi
  exit 130
}
trap cleanup INT TERM

# 给每一行加标签并立刻刷出去。两端并行时输出会交错，标签让来源可辨；
# fflush() 保证行到即出，不会攒在管道缓冲里。
prefix_lines() {
  awk -v tag="$1" '{ printf "[%s] %s\n", tag, $0; fflush() }'
}

# 前端测试命令：优先项目约定的 pnpm，pnpm 版本切换不可用时退回本地 Vitest 二进制，
# 并打印告警而不是静默改变测试入口。
frontend_command() {
  cd "$FRONTEND_DIR" || return 1
  if [ ! -d node_modules ]; then
    echo "frontend/node_modules 不存在，请先运行 pnpm install" >&2
    return 1
  fi
  if pnpm --version >/dev/null 2>&1; then
    pnpm test
  else
    echo "警告: pnpm 在本机不可用（packageManager 版本切换失败），改用 frontend/node_modules/.bin/vitest。" >&2
    echo "修复方式: pnpm approve-builds -g 后重装，或补跑 pnpm 包内的 install.js" >&2
    ./node_modules/.bin/vitest run
  fi
}

# 默认走 verify，连同 Testcontainers 的 *IT 一起跑；--unit 退回到 surefire 的 *Test。
backend_command() {
  cd "$BACKEND_DIR" || return 1
  if [ "$FULL_VERIFY" -eq 1 ]; then
    ./mvnw --batch-mode --no-transfer-progress verify
  else
    ./mvnw --batch-mode --no-transfer-progress test
  fi
}

started_at=$(date +%s)

echo "${C_BOLD}运行范围${C_RESET}"
if [ "$RUN_FRONTEND" -eq 1 ]; then
  echo "  前端  Vitest 单元测试"
fi
if [ "$RUN_BACKEND" -eq 1 ]; then
  if [ "$FULL_VERIFY" -eq 1 ]; then
    echo "  后端  Maven verify（单元测试 + 集成测试 *IT，需要 Docker 与 ffmpeg）"
  else
    echo "  后端  Maven test（仅单元测试）"
  fi
fi
echo

if [ "$RUN_FRONTEND" -eq 1 ]; then
  printf '  %s启动前端测试%s (Vitest)\n' "$C_YELLOW" "$C_RESET"
  # tee 留完整日志，prefix_lines 实时打到终端；PIPESTATUS[0] 取回测试本身的退出码，
  # 而不是管道末端 awk 的。
  (
    frontend_command 2>&1 | tee "$FRONTEND_LOG" | prefix_lines 前端
    exit "${PIPESTATUS[0]}"
  ) &
  FE_PID=$!
fi

if [ "$RUN_BACKEND" -eq 1 ]; then
  if [ "$FULL_VERIFY" -eq 1 ]; then
    printf '  %s启动后端测试%s (Maven verify)\n' "$C_YELLOW" "$C_RESET"
  else
    printf '  %s启动后端测试%s (Maven test)\n' "$C_YELLOW" "$C_RESET"
  fi
  (
    backend_command 2>&1 | tee "$BACKEND_LOG" | prefix_lines 后端
    exit "${PIPESTATUS[0]}"
  ) &
  BE_PID=$!
fi

echo

FE_RC=0
BE_RC=0
FE_SECONDS=0
BE_SECONDS=0

if [ -n "$FE_PID" ]; then
  fe_started=$(date +%s)
  wait "$FE_PID"
  FE_RC=$?
  FE_SECONDS=$(( $(date +%s) - fe_started ))
fi

if [ -n "$BE_PID" ]; then
  be_started=$(date +%s)
  wait "$BE_PID"
  BE_RC=$?
  BE_SECONDS=$(( $(date +%s) - be_started ))
fi

trap - INT TERM

status_line() {
  if [ "$1" -eq 0 ]; then
    printf '%s通过%s' "$C_GREEN" "$C_RESET"
  else
    printf '%s失败 (退出码 %s)%s' "$C_RED" "$1" "$C_RESET"
  fi
}

print_tail() {
  echo
  echo "${C_YELLOW}--- $1 日志尾部 ---${C_RESET}"
  tail -n 40 "$2"
}

echo
echo "${C_BOLD}结果${C_RESET}"
if [ "$RUN_FRONTEND" -eq 1 ]; then
  printf '  前端  %s  (%ss)\n' "$(status_line "$FE_RC")" "$FE_SECONDS"
else
  printf '  前端  %s未运行%s\n' "$C_YELLOW" "$C_RESET"
fi
if [ "$RUN_BACKEND" -eq 1 ]; then
  printf '  后端  %s  (%ss)\n' "$(status_line "$BE_RC")" "$BE_SECONDS"
else
  printf '  后端  %s未运行%s\n' "$C_YELLOW" "$C_RESET"
fi
printf '  总耗时 %ss\n' "$(( $(date +%s) - started_at ))"

[ "$RUN_FRONTEND" -eq 1 ] && [ "$FE_RC" -ne 0 ] && print_tail 前端 "$FRONTEND_LOG"
[ "$RUN_BACKEND" -eq 1 ] && [ "$BE_RC" -ne 0 ] && print_tail 后端 "$BACKEND_LOG"

if [ "$FE_RC" -ne 0 ] || [ "$BE_RC" -ne 0 ]; then
  echo
  echo "${C_RED}失败。${C_RESET}完整日志目录: $LOG_DIR"
  exit 1
fi

rm -rf "$LOG_DIR"
echo
echo "${C_GREEN}全部通过。${C_RESET}"
