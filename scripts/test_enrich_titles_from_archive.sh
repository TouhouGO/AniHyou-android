#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENRICH_SCRIPT="${PROJECT_DIR}/scripts/enrich-titles-from-bangumi-archive.mjs"

TEST_DIR="$(mktemp -d)"
trap 'rm -rf "${TEST_DIR}"' EXIT

INPUT_TITLES="${TEST_DIR}/titles_in.json"
ARCHIVE_FILE="${TEST_DIR}/subject.jsonlines"
OUTPUT_TITLES="${TEST_DIR}/titles_out.json"
REPORT_FILE="${TEST_DIR}/report.json"
MEDIA_TYPES_FILE="${TEST_DIR}/media_types.json"

# Prepare test titles input:
# - 1001: AniList ID 1001, Bangumi 253, anime (type 2), valid match -> should update to "星际牛仔|253|ANIME"
# - 2001: AniList ID 2001, Bangumi 456, manga (type 1), valid match -> should update to "某轻小说书籍|456|MANGA"
# - 1002: AniList ID 1002, Bangumi 456, type mismatch (AniList ANIME, but Bangumi subject is type 1 Book) -> retain original
# - 2002: AniList ID 2002, Bangumi 253, type mismatch (AniList MANGA, but Bangumi subject is type 2 Anime) -> retain original
# - 1003: AniList ID 1003, Bangumi 789, name_cn is empty -> retain original
# - 1004: AniList ID 1004, Bangumi 999999, not in archive -> retain original
# - 1005: AniList ID 1005, no Bangumi ID -> retain original
# - 1006: AniList ID 1006, Bangumi 888, duplicate candidate in archive with conflicting name_cn -> retain original
cat << 'JSON_IN' > "${INPUT_TITLES}"
{
  "1001": "星际牛仔旧译|253|ANIME",
  "2001": "某轻小说旧译|456|MANGA",
  "1002": "动画误指书籍|456|ANIME",
  "2002": "漫画误指动画|253|MANGA",
  "1003": "无中文名动画|789|ANIME",
  "1004": "未收录作品|999999|ANIME",
  "1005": "仅有标题无ID",
  "1006": "歧义条目|888|ANIME"
}
JSON_IN

# Prepare mock Bangumi subject.jsonlines archive:
# schema: {"id":..., "type":..., "name":"...", "name_cn":"...", ...}
# type 1 = book / manga
# type 2 = anime
cat << 'JSON_LINES' > "${ARCHIVE_FILE}"
{"id":253,"type":2,"name":"Cowboy Bebop","name_cn":"星际牛仔","summary":""}
{"id":456,"type":1,"name":"Some Book","name_cn":"某轻小说书籍","summary":""}
{"id":789,"type":2,"name":"No CN Name","name_cn":"","summary":""}
{"id":888,"type":2,"name":"Ambiguous One","name_cn":"歧义译名一","summary":""}
{"id":888,"type":2,"name":"Ambiguous Two","name_cn":"歧义译名二","summary":""}
{"broken_json": true
JSON_LINES

# Ensure enrich script exists before execution
if [ ! -f "${ENRICH_SCRIPT}" ]; then
  echo "RED: ${ENRICH_SCRIPT} does not exist"
  exit 1
fi

node "${ENRICH_SCRIPT}" \
  --titles "${INPUT_TITLES}" \
  --archive "${ARCHIVE_FILE}" \
  --output "${OUTPUT_TITLES}" \
  --report "${REPORT_FILE}"

# Assertions
echo "Verifying 1001 (ANIME -> Bangumi 2) enriched with correct Bangumi name_cn..."
TITLE_1001=$(jq -r '.["1001"]' "${OUTPUT_TITLES}")
test "${TITLE_1001}" = "星际牛仔|253|ANIME"

echo "Verifying 2001 (MANGA -> Bangumi 1) enriched with correct Bangumi name_cn..."
TITLE_2001=$(jq -r '.["2001"]' "${OUTPUT_TITLES}")
test "${TITLE_2001}" = "某轻小说书籍|456|MANGA"

echo "Verifying 1002 (ANIME expecting 2, got 1) type mismatch retains original..."
TITLE_1002=$(jq -r '.["1002"]' "${OUTPUT_TITLES}")
test "${TITLE_1002}" = "动画误指书籍|456|ANIME"

echo "Verifying 2002 (MANGA expecting 1, got 2) type mismatch retains original..."
TITLE_2002=$(jq -r '.["2002"]' "${OUTPUT_TITLES}")
test "${TITLE_2002}" = "漫画误指动画|253|MANGA"

echo "Verifying 1003 empty name_cn retains original..."
TITLE_1003=$(jq -r '.["1003"]' "${OUTPUT_TITLES}")
test "${TITLE_1003}" = "无中文名动画|789|ANIME"

echo "Verifying 1004 missing archive retains original..."
TITLE_1004=$(jq -r '.["1004"]' "${OUTPUT_TITLES}")
test "${TITLE_1004}" = "未收录作品|999999|ANIME"

echo "Verifying 1005 without Bangumi ID retains original..."
TITLE_1005=$(jq -r '.["1005"]' "${OUTPUT_TITLES}")
test "${TITLE_1005}" = "仅有标题无ID"

echo "Verifying 1006 duplicate archive conflict retains original..."
TITLE_1006=$(jq -r '.["1006"]' "${OUTPUT_TITLES}")
test "${TITLE_1006}" = "歧义条目|888|ANIME"

echo "Verifying stats report output..."
test -f "${REPORT_FILE}"
test "$(jq -r '.input' "${REPORT_FILE}")" = "8"
test "$(jq -r '.matched' "${REPORT_FILE}")" = "6"
test "$(jq -r '.replaced' "${REPORT_FILE}")" = "2"
test "$(jq -r '.typeMismatch' "${REPORT_FILE}")" = "2"
test "$(jq -r '.duplicate' "${REPORT_FILE}")" = "1"
test "$(jq -r '.invalidLine' "${REPORT_FILE}")" = "1"

# Test external media types file option
cat << 'JSON_TYPES' > "${MEDIA_TYPES_FILE}"
{
  "3001": "MANGA"
}
JSON_TYPES

cat << 'JSON_IN2' > "${TEST_DIR}/titles_in2.json"
{
  "3001": "外部类型声明漫画|456"
}
JSON_IN2

node "${ENRICH_SCRIPT}" \
  --titles "${TEST_DIR}/titles_in2.json" \
  --archive "${ARCHIVE_FILE}" \
  --output "${TEST_DIR}/titles_out2.json" \
  --media-types "${MEDIA_TYPES_FILE}"

echo "Verifying external media types file successfully maps 3001 as MANGA..."
TITLE_3001=$(jq -r '.["3001"]' "${TEST_DIR}/titles_out2.json")
test "${TITLE_3001}" = "某轻小说书籍|456"

echo "test_enrich_titles_from_archive passed!"
