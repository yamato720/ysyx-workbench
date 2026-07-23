#!/usr/bin/env bash
# 将底层工具的完整输出保存在阶段日志中，只向终端显示阶段状态和构建进度。
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
用法：
  phase-log.sh run <log-dir> <phase> <index> <total> -- <command> [args...]
  phase-log.sh note <log-dir> <phase> <index> <total> <message>
EOF
  exit 2
}

progress_line() {
  local index=$1 total=$2 phase=$3 state=$4 width=24 filled empty bar
  if [[ -t 1 ]]; then
    filled=$(( index * width / total ))
    empty=$(( width - filled ))
    printf -v bar '%*s' "$filled" ''
    bar=${bar// /#}
    printf -v empty '%*s' "$empty" ''
    empty=${empty// /-}
    printf '\r\033[2K构造 [%d/%d] [%s%s] %s%s' "$index" "$total" "$bar" "$empty" "$phase" "$state"
  else
    printf '构造 [%d/%d] %s%s\n' "$index" "$total" "$phase" "$state"
  fi
}

finish_line() {
  if [[ -t 1 ]]; then
    printf '\n'
  fi
}

clear_transient_line() {
  [[ -t 1 ]] || return 0
  printf '\r\033[2K'
}

spinner_line() {
  local index=$1 total=$2 phase=$3 frame=$4 width=24 filled empty bar rest
  [[ -t 1 ]] || return 0
  filled=$(( index * width / total ))
  empty=$(( width - filled ))
  printf -v bar '%*s' "$filled" ''
  bar=${bar// /#}
  printf -v rest '%*s' "$empty" ''
  rest=${rest// /-}
  printf '\r\033[2K构造 [%d/%d] [%s%s] %s 正在执行 [%s]' \
    "$index" "$total" "$bar" "$rest" "$phase" "$frame"
}

passthrough_with_spinner() {
  local log=$1 combined=$2 phase=$3 index=$4 total=$5
  shift 5
  local temporary fifo input_fd log_fd combined_fd command_pid status=0 line read_status
  local idle_ticks=0 frame_index=0 spinner_active=0
  local -a frames=('#....' '.#...' '..#..' '...#.' '....#' '...#.' '..#..' '.#...')

  temporary=$(mktemp -d "${log}.stream.XXXXXX")
  fifo="$temporary/output"
  mkfifo "$fifo"
  "$@" >"$fifo" 2>&1 &
  command_pid=$!
  exec {input_fd}<"$fifo"
  exec {log_fd}>>"$log"
  exec {combined_fd}>>"$combined"

  while :; do
    line=''
    if IFS= read -r -t 0.2 -u "$input_fd" line; then
      clear_transient_line
      spinner_active=0
      idle_ticks=0
      printf '%s\n' "$line"
      printf '%s\n' "$line" >&"$log_fd"
      printf '%s\n' "$line" >&"$combined_fd"
      continue
    fi
    read_status=$?

    if [[ -n $line ]]; then
      clear_transient_line
      spinner_active=0
      idle_ticks=0
      printf '%s\n' "$line"
      printf '%s\n' "$line" >&"$log_fd"
      printf '%s\n' "$line" >&"$combined_fd"
      continue
    fi

    if ! kill -0 "$command_pid" 2>/dev/null; then
      break
    fi
    ((idle_ticks++))
    if [[ -t 1 && $idle_ticks -ge 5 && $((idle_ticks % 5)) == 0 ]]; then
      spinner_line "$index" "$total" "$phase" "${frames[$frame_index]}"
      frame_index=$(((frame_index + 1) % ${#frames[@]}))
      spinner_active=1
    fi
  done

  (( spinner_active == 0 )) || clear_transient_line
  exec {input_fd}<&-
  exec {log_fd}>&-
  exec {combined_fd}>&-
  rm -f "$fifo"
  rmdir "$temporary"
  wait "$command_pid" || status=$?
  return "$status"
}

stream_progress() {
  # sbt/mill 的下载和编译器会用 12/345 这类进度更新；其余历史输出仍完整保留在日志。
  awk '
    /(^|[^0-9])[0-9]+\/[0-9]+([^0-9]|$)/ {
      gsub(/\r/, "")
      print
      fflush()
    }
  '
}

run_phase() {
  [[ $# -ge 6 && $5 == -- ]] || usage
  local log_dir=$1 phase=$2 index=$3 total=$4
  shift 5
  [[ $index =~ ^[1-9][0-9]*$ && $total =~ ^[1-9][0-9]*$ && $index -le $total ]] || usage
  mkdir -p "$log_dir"
  local log="$log_dir/$phase.log" combined="$log_dir/all.log" status
  : > "$log"
  {
    printf 'PHASE=%s\n' "$phase"
    printf 'COMMAND='
    printf '%q ' "$@"
    printf '\n'
  } | tee -a "$log" >> "$combined"
  progress_line "$index" "$total" "$phase" '...'
  set +e
  if [[ ${PHASE_LOG_PASSTHROUGH:-0} == 1 ]]; then
    passthrough_with_spinner "$log" "$combined" "$phase" "$index" "$total" "$@"
    status=$?
  else
    "$@" 2>&1 | tee -a "$log" | tee -a "$combined" | stream_progress
    status=${PIPESTATUS[0]}
  fi
  set -e
  if [[ $status == 0 ]]; then
    progress_line "$index" "$total" "$phase" ' 完成'
    finish_line
  else
    progress_line "$index" "$total" "$phase" " 失败（完整日志：$log）"
    finish_line
  fi
  return "$status"
}

note_phase() {
  [[ $# == 5 ]] || usage
  local log_dir=$1 phase=$2 index=$3 total=$4 message=$5
  [[ $index =~ ^[1-9][0-9]*$ && $total =~ ^[1-9][0-9]*$ && $index -le $total ]] || usage
  mkdir -p "$log_dir"
  progress_line "$index" "$total" "$phase" '...'
  printf '%s\n' "$message" | tee "$log_dir/$phase.log" >> "$log_dir/all.log"
  progress_line "$index" "$total" "$phase" ' 完成'
  finish_line
}

[[ $# -ge 1 ]] || usage
command=$1
shift
case "$command" in
  run) run_phase "$@" ;;
  note) note_phase "$@" ;;
  *) usage ;;
esac
