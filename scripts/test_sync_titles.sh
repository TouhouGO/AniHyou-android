#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT

# Test 1: sync-titles.sh must output EXACTLY what source provides without applying any local override file
printf '%s' '{"1001":"星际牛仔旧译|253","1":"星际牛仔|253"}' > "${TEST_DIR}/source.json"

TITLE_SOURCE_URL="file://${TEST_DIR}/source.json" \
TITLE_TARGET_FILE="${TEST_DIR}/result.json" \
"${PROJECT_DIR}/scripts/sync-titles.sh"

test "$(jq -r '.["1001"]' "${TEST_DIR}/result.json")" = "星际牛仔旧译|253"
test "$(jq -r '.["1"]' "${TEST_DIR}/result.json")" = "星际牛仔|253"

# Test 2: Local override file must not exist and must not be accepted
if [ -f "${PROJECT_DIR}/scripts/title_overrides_zh_cn.json" ]; then
  echo "FAIL: title_overrides_zh_cn.json must not exist in repository!"
  exit 1
fi

echo "test_sync_titles passed!"

# Test 3: sync-titles.sh with BANGUMI_ARCHIVE_PATH calibrates title with type 2 anime
printf '%s' '{"1001":"旧标题|253"}' > "${TEST_DIR}/source3.json"
cat << 'ARCHIVE_EOF' > "${TEST_DIR}/subject.jsonlines"
{"id":253,"type":2,"name":"Cowboy Bebop","name_cn":"星际牛仔","summary":""}
ARCHIVE_EOF

TITLE_SOURCE_URL="file://${TEST_DIR}/source3.json" \
TITLE_TARGET_FILE="${TEST_DIR}/result3.json" \
BANGUMI_ARCHIVE_PATH="${TEST_DIR}/subject.jsonlines" \
"${PROJECT_DIR}/scripts/sync-titles.sh"

test "$(jq -r '.["1001"]' "${TEST_DIR}/result3.json")" = "星际牛仔|253"
echo "test_sync_titles with archive calibration passed!"
