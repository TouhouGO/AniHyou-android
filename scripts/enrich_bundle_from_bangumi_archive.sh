#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_TITLES="${ROOT_DIR}/core/network/src/main/resources/titles_zh_cn.json"
REPORT_OUTPUT="${1:-${ROOT_DIR}/core/network/build/outputs/localization/bangumi_enrichment_report.json}"

WORK_DIR=$(mktemp -d)
trap 'rm -rf "${WORK_DIR}"' EXIT

ARCHIVE_METADATA_URL="https://raw.githubusercontent.com/bangumi/Archive/master/aux/latest.json"

echo "Fetching latest Bangumi Archive metadata..."
METADATA_JSON=$(curl -sSfL "${ARCHIVE_METADATA_URL}")
DOWNLOAD_URL=$(echo "${METADATA_JSON}" | jq -r .browser_download_url)
ARCHIVE_SHA256=$(echo "${METADATA_JSON}" | jq -r .digest | sed 's/^sha256://')
ARCHIVE_DATE=$(echo "${METADATA_JSON}" | jq -r .created_at)

if [ -z "${DOWNLOAD_URL}" ] || [ "${DOWNLOAD_URL}" = "null" ]; then
  echo "WARN: Failed to retrieve Bangumi archive download URL, skipping enrichment."
  exit 0
fi

echo "Downloading Bangumi archive from ${DOWNLOAD_URL}..."
curl -sSfL "${DOWNLOAD_URL}" -o "${WORK_DIR}/archive.zip"

echo "Extracting subject.jsonlines from archive..."
unzip -q -p "${WORK_DIR}/archive.zip" "subject.jsonlines" > "${WORK_DIR}/subject.jsonlines"

mkdir -p "$(dirname "${REPORT_OUTPUT}")"
INTERMEDIATE_REPORT="${WORK_DIR}/stats_report.json"

echo "Running title enrichment..."
node "${SCRIPT_DIR}/enrich-titles-from-bangumi-archive.mjs" \
  --titles "${TARGET_TITLES}" \
  --archive "${WORK_DIR}/subject.jsonlines" \
  --output "${WORK_DIR}/titles_calibrated.json" \
  --report "${INTERMEDIATE_REPORT}"

# Attach metadata to report
jq --arg url "${DOWNLOAD_URL}" \
   --arg sha "${ARCHIVE_SHA256}" \
   --arg date "${ARCHIVE_DATE}" \
   '. + {archiveUrl: $url, archiveSha256: $sha, archiveDate: $date}' \
   "${INTERMEDIATE_REPORT}" > "${REPORT_OUTPUT}"

mv "${WORK_DIR}/titles_calibrated.json" "${TARGET_TITLES}"
echo "Successfully enriched ${TARGET_TITLES} with latest Bangumi archive. Report saved to ${REPORT_OUTPUT}."
