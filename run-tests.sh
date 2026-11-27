#!/usr/bin/env bash
#
# 一键运行测试：前端包测试 + 后端 Maven verify。
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

# 两条路径均执行 package.json 的完整 test 脚本，使用已有的 node_modules。
frontend_command() {
  cd "$FRONTEND_DIR" || return 1
  if [ ! -d node_modules ]; then
    echo "frontend/node_modules 不存在，请先运行 pnpm install" >&2
    return 1
  fi
  if pnpm --version >/dev/null 2>&1; then
    pnpm test
  else
    echo "pnpm 不可用，使用 Node 执行同一份前端 test 脚本。" >&2
    node --run test
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

# 在每个后台任务内记录其运行时间，避免 wait 的先后顺序改变统计。
run_suite() {
  local label=$1 log=$2 command=$3 start=$SECONDS result
  "$command" 2>&1 | tee "$log" | prefix_lines "$label"
  result=${PIPESTATUS[0]}
  printf '%s\n' "$(( SECONDS - start ))" > "$log.seconds"
  return "$result"
}

started_at=$(date +%s)

echo "${C_BOLD}运行范围${C_RESET}"
if [ "$RUN_FRONTEND" -eq 1 ]; then
  echo "  前端  package.json test"
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
  printf '  %s启动前端测试%s\n' "$C_YELLOW" "$C_RESET"
  run_suite 前端 "$FRONTEND_LOG" frontend_command &
  FE_PID=$!
fi

if [ "$RUN_BACKEND" -eq 1 ]; then
  if [ "$FULL_VERIFY" -eq 1 ]; then
    printf '  %s启动后端测试%s (Maven verify)\n' "$C_YELLOW" "$C_RESET"
  else
    printf '  %s启动后端测试%s (Maven test)\n' "$C_YELLOW" "$C_RESET"
  fi
  run_suite 后端 "$BACKEND_LOG" backend_command &
  BE_PID=$!
fi

echo

FE_RC=0
BE_RC=0
FE_SECONDS='?'
BE_SECONDS='?'

if [ -n "$FE_PID" ]; then
  wait "$FE_PID"
  FE_RC=$?
  if [ -r "$FRONTEND_LOG.seconds" ]; then read -r FE_SECONDS < "$FRONTEND_LOG.seconds"; fi
fi

if [ -n "$BE_PID" ]; then
  wait "$BE_PID"
  BE_RC=$?
  if [ -r "$BACKEND_LOG.seconds" ]; then read -r BE_SECONDS < "$BACKEND_LOG.seconds"; fi
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
