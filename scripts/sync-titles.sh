#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_FILE="${TITLE_TARGET_FILE:-${ROOT_DIR}/core/network/src/main/resources/titles_zh_cn.json}"
SOURCE_URL="${TITLE_SOURCE_URL:-https://raw.githubusercontent.com/TouhouGO/anilist-zh-cn-userscript/main/data/titles_zh_cn.json}"
ARCHIVE_PATH="${BANGUMI_ARCHIVE_PATH:-}"

DOWNLOAD_FILE="$(mktemp)"
CALIBRATED_FILE="$(mktemp)"
trap 'rm -f "${DOWNLOAD_FILE}" "${CALIBRATED_FILE}"' EXIT

echo "Syncing Chinese title dictionary from ${SOURCE_URL}..."
curl -sSfL "${SOURCE_URL}" -o "${DOWNLOAD_FILE}"

# Validate valid JSON shape and numeric key structure before replacing
jq -e 'type == "object" and (keys | length > 0)' "${DOWNLOAD_FILE}" > /dev/null

FINAL_FILE="${DOWNLOAD_FILE}"

# If Bangumi Archive subject.jsonlines is provided, enrich/calibrate titles
if [ -n "${ARCHIVE_PATH}" ] && [ -f "${ARCHIVE_PATH}" ]; then
  echo "Enriching titles with Bangumi archive: ${ARCHIVE_PATH}..."
  node "${SCRIPT_DIR}/enrich-titles-from-bangumi-archive.mjs" \
    --titles "${DOWNLOAD_FILE}" \
    --archive "${ARCHIVE_PATH}" \
    --output "${CALIBRATED_FILE}"
  FINAL_FILE="${CALIBRATED_FILE}"
fi

# Atomically copy validated automatic upstream artifact
mv "${FINAL_FILE}" "${TARGET_FILE}"
echo "Successfully updated: ${TARGET_FILE}"
