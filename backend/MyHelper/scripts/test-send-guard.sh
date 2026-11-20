#!/usr/bin/env bash
# 测试本地后端的发送互斥；不直接接触模型密钥。兼容 macOS Bash 3.2。
set -euo pipefail

usage() {
  cat <<'HELP'
用法：bash test-send-guard.sh USER_ID CONVERSATION_ID [BASE_URL]

示例：
  bash test-send-guard.sh 实际用户ID 10 http://localhost:8080

前提：后端已启动并配置真实模型；用户、会话已存在且归属匹配。
依赖：bash、curl、jq。BASE_URL 默认 http://localhost:8080，不含 /api。
可选环境变量：REQUEST_TIMEOUT=180（每次发送请求的超时秒数）。

检查：非法消息返回 400 且释放占用；同会话重叠发送返回 409；
      首轮完成后可再次发送；被拒绝的消息没有入库。

注意：会调用真实模型并保存测试消息，不自动删除数据，不自动重试。
正常通过需要两次模型调用；并发未拦截时可能产生第三次调用。
请使用专用测试会话，运行时不要从其他客户端向该会话发消息。
本脚本不主动制造上游故障，不验证流式、多实例或前端按钮行为。

退出码：0=通过，1=失败，2=并发窗口不足或结果不确定。
HELP
}

if [[ "${1:-}" == '--help' || "${1:-}" == '-h' ]]; then
  usage
  exit 0
fi
if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage >&2
  exit 1
fi

user_id=$1
conversation_id=$2
base_url=${3:-http://localhost:8080}
base_url=${base_url%/}
request_timeout=${REQUEST_TIMEOUT:-180}
if [[ -z "$user_id" || ! "$conversation_id" =~ ^[1-9][0-9]*$ ]]; then
  printf '[FAIL] 请提供非空用户 ID 和正整数会话 ID。\n' >&2
  exit 1
fi
if [[ ! "$request_timeout" =~ ^[1-9][0-9]*$ ]]; then
  printf '[FAIL] REQUEST_TIMEOUT 必须为正整数。\n' >&2
  exit 1
fi
for dependency in curl jq; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf '[FAIL] 缺少依赖：%s\n' "$dependency" >&2
    exit 1
  fi
done

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/myhelper-send-guard.XXXXXX")
first_pid=''
cleanup() {
  if [[ -n "$first_pid" ]] && kill -0 "$first_pid" 2>/dev/null; then
    printf '\n[WARN] 正在停止本地等待；后端任务可能仍在执行，请先查询历史，不要直接重发。\n' >&2
    kill "$first_pid" 2>/dev/null || true
    wait "$first_pid" 2>/dev/null || true
  fi
  rm -f "$tmp_dir/history.json" "$tmp_dir/invalid.json" \
    "$tmp_dir/first.json" "$tmp_dir/first.status" "$tmp_dir/first.err" \
    "$tmp_dir/duplicate.json" "$tmp_dir/next.json" "$tmp_dir/curl.err"
  rmdir "$tmp_dir"
}
trap 'script_status=$?; cleanup; exit "$script_status"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf '[FAIL] %s\n' "$*" >&2
  exit 1
}

show_body() {
  if [[ -s "$1" ]]; then
    jq . "$1" 2>/dev/null || cat "$1"
  fi
}

payload() {
  jq -cn --arg user "$user_id" --argjson conversation "$conversation_id" \
    --arg content "$1" \
    '{userId: $user, conversationId: $conversation, content: $content}'
}

post_message() {
  if ! http_status=$(curl --silent --show-error --connect-timeout 5 \
    --max-time "$request_timeout" --output "$2" --write-out '%{http_code}' \
    --header 'Content-Type: application/json' --data "$1" \
    "$base_url/api/chat/send" 2>"$tmp_dir/curl.err"); then
    cat "$tmp_dir/curl.err" >&2
    fail '发送请求网络失败或超时，结果未知；请查询历史，脚本不会自动重发。'
  fi
}

fetch_history() {
  if ! history_status=$(curl --silent --show-error --connect-timeout 3 \
    --max-time 5 --get --data-urlencode "userId=$user_id" \
    --data-urlencode "conversationId=$conversation_id" \
    --output "$tmp_dir/history.json" --write-out '%{http_code}' \
    "$base_url/api/messages/history" 2>"$tmp_dir/curl.err"); then
    cat "$tmp_dir/curl.err" >&2
    fail '无法查询历史记录。'
  fi
  if [[ "$history_status" != 200 ]] || ! jq -e \
    '.code == 0 and (.data | type == "array")' "$tmp_dir/history.json" >/dev/null; then
    show_body "$tmp_dir/history.json"
    fail "历史查询失败（HTTP ${history_status}），请检查用户、会话及服务地址。"
  fi
}

assert_round() {
  local body=$1 content=$2
  if ! jq -e --arg content "$content" --argjson conversation "$conversation_id" '
    .code == 0 and
    .data.userMessage.conversationId == $conversation and
    .data.userMessage.role == "user" and
    .data.userMessage.content == $content and
    .data.userMessage.status == "COMPLETED" and
    .data.assistantMessage.conversationId == $conversation and
    .data.assistantMessage.role == "assistant" and
    .data.assistantMessage.status == "COMPLETED" and
    (.data.assistantMessage.content | type == "string" and test("\\S"))
  ' "$body" >/dev/null; then
    show_body "$body"
    fail '响应中没有完整的用户消息与非空 AI 回复。'
  fi
}

run_id="$(date +%Y%m%d-%H%M%S)-$$-$RANDOM"
first_content="[send-guard-$run_id-A] 自动化测试，请只回复 OK。"
duplicate_content="[send-guard-$run_id-B] 这条消息应被并发保护拒绝。"
next_content="[send-guard-$run_id-C] 自动化测试，请只回复 OK。"

printf '目标：%s，会话：%s，测试标记：%s\n' "$base_url" "$conversation_id" "$run_id"
printf '[WARN] 将保存测试消息并产生真实模型调用费用。\n'
fetch_history

# 非法消息在取得会话占用后被 MessageService 拒绝，后续 A 可验证 finally 释放。
post_message "$(payload '   ')" "$tmp_dir/invalid.json"
if [[ "$http_status" != 400 ]] || ! jq -e '.code == 40000' "$tmp_dir/invalid.json" >/dev/null; then
  show_body "$tmp_dir/invalid.json"
  fail "空白消息应返回 HTTP 400 / code 40000，实际 HTTP ${http_status}。"
fi
printf '[PASS] 空白消息被拒绝。\n'

printf '[INFO] 启动请求 A，等待用户消息入库后发送并发请求 B。\n'
curl --silent --show-error --connect-timeout 5 --max-time "$request_timeout" \
  --output "$tmp_dir/first.json" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' --data "$(payload "$first_content")" \
  "$base_url/api/chat/send" >"$tmp_dir/first.status" 2>"$tmp_dir/first.err" &
first_pid=$!

observed=false
deadline=$((SECONDS + 15))
while kill -0 "$first_pid" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
  fetch_history
  if jq -e --arg content "$first_content" \
    'any(.data[]; .role == "user" and .content == $content)' \
    "$tmp_dir/history.json" >/dev/null; then
    observed=true
    break
  fi
  sleep 0.1
done

concurrency_verified=false
duplicate_status='not-sent'
if [[ "$observed" == true ]] && kill -0 "$first_pid" 2>/dev/null; then
  post_message "$(payload "$duplicate_content")" "$tmp_dir/duplicate.json"
  duplicate_status=$http_status
  if [[ "$duplicate_status" == 409 ]] && jq -e \
    '.code == 40900' "$tmp_dir/duplicate.json" >/dev/null; then
    concurrency_verified=true
    printf '[PASS] 同会话请求 B 被 HTTP 409 / code 40900 拒绝。\n'
  fi
fi

if wait "$first_pid"; then
  first_pid=''
else
  first_pid=''
  cat "$tmp_dir/first.err" >&2
  fail '请求 A 网络失败或超时，结果未知；请查询历史，不要直接重发。'
fi
first_status=$(<"$tmp_dir/first.status")
if [[ "$first_status" != 200 ]]; then
  show_body "$tmp_dir/first.json"
  fail "请求 A 应成功，实际 HTTP ${first_status}；本次未完成真实模型联调验证。"
fi
assert_round "$tmp_dir/first.json" "$first_content"
printf '[PASS] 请求 A 完成真实模型调用；先前空白消息的占用已释放。\n'

if [[ "$concurrency_verified" != true ]]; then
  if [[ "$duplicate_status" == 200 || "$duplicate_status" == 'not-sent' ]]; then
    printf '[INCONCLUSIVE] 未确认并发拦截：A 可能过快完成，也可能拦截未生效。\n' >&2
    printf '请查询历史确认后再测，或使用可控延迟的模拟模型验证；本次不计为通过。\n' >&2
    exit 2
  fi
  show_body "$tmp_dir/duplicate.json"
  fail "请求 B 应返回 HTTP 409 / code 40900，实际 HTTP ${duplicate_status}。"
fi

post_message "$(payload "$next_content")" "$tmp_dir/next.json"
if [[ "$http_status" != 200 ]]; then
  show_body "$tmp_dir/next.json"
  fail "首轮结束后的请求 C 应成功，实际 HTTP ${http_status}。"
fi
assert_round "$tmp_dir/next.json" "$next_content"
printf '[PASS] 首轮完成后，请求 C 可正常发送并收到 AI 回复。\n'

fetch_history
for result in first next; do
  if ! jq -e --slurpfile round "$tmp_dir/$result.json" '
    .data as $history |
    [$round[0].data.userMessage, $round[0].data.assistantMessage] | all(.[];
      . as $expected | any($history[];
        .id == $expected.id and .role == $expected.role and
        .content == $expected.content and .status == "COMPLETED"))
  ' "$tmp_dir/history.json" >/dev/null; then
    fail '历史中缺少响应里已完成的消息。'
  fi
done
if ! jq -e --arg content "$duplicate_content" \
  'all(.data[]; .content != $content)' "$tmp_dir/history.json" >/dev/null; then
  fail '被拒绝的请求 B 仍然出现在历史记录中。'
fi
printf '[PASS] A、C 两轮消息已入库，被拒绝的 B 未入库。\n'
printf '\n全部通过。测试消息已保留在会话 %s 中，不会自动删除。\n' "$conversation_id"
