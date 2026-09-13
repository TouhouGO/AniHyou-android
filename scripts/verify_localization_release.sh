#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <path/to/localization_bundle.zip> <path/to/remote_bundle_manifest.json>"
  exit 1
fi

ZIP_FILE="$1"
MANIFEST_FILE="$2"

if [ ! -f "$ZIP_FILE" ]; then
  echo "ERROR: ZIP file not found at $ZIP_FILE"
  exit 1
fi

if [ ! -f "$MANIFEST_FILE" ]; then
  echo "ERROR: Manifest file not found at $MANIFEST_FILE"
  exit 1
fi

echo "=== Verifying Localization Release Artifacts ==="
echo "ZIP: $ZIP_FILE"
echo "Manifest: $MANIFEST_FILE"

# 1. Verify remote manifest JSON shape & fields
echo "Checking remote manifest JSON format..."
node -e '
const fs = require("fs");
const manifest = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));

const required = ["formatVersion", "version", "downloadUrl", "archiveSize", "archiveSha256"];
for (const key of required) {
  if (manifest[key] === undefined || manifest[key] === null) {
    throw new Error(`Missing required field in manifest: ${key}`);
  }
}

if (!manifest.downloadUrl.endsWith("/localization_bundle.zip")) {
  throw new Error(`downloadUrl does not end with localization_bundle.zip: ${manifest.downloadUrl}`);
}

const versionRegex = /^\d{4}\.\d{2}\.\d{2}$/;
if (!versionRegex.test(manifest.version)) {
  throw new Error(`Invalid version in manifest: ${manifest.version}`);
}

const shaRegex = /^[0-9a-fA-F]{64}$/;
if (!shaRegex.test(manifest.archiveSha256)) {
  throw new Error(`Invalid archiveSha256 in manifest: ${manifest.archiveSha256}`);
}
' "$MANIFEST_FILE"

echo "Remote manifest schema verified successfully."

# 2. Verify ZIP size and SHA256 against manifest
ACTUAL_ZIP_SIZE=$(wc -c < "$ZIP_FILE" | tr -d ' ')
ACTUAL_ZIP_SHA256=$(shasum -a 256 "$ZIP_FILE" | awk '{print $1}')

MANIFEST_ZIP_SIZE=$(node -e 'console.log(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).archiveSize)' "$MANIFEST_FILE")
MANIFEST_ZIP_SHA256=$(node -e 'console.log(JSON.parse(fs.readFileSync(process.argv[1], "utf8")).archiveSha256)' "$MANIFEST_FILE")

if [ "$ACTUAL_ZIP_SIZE" -ne "$MANIFEST_ZIP_SIZE" ]; then
  echo "ERROR: Archive size mismatch! Actual: $ACTUAL_ZIP_SIZE, Expected in manifest: $MANIFEST_ZIP_SIZE"
  exit 1
fi

if [ "$ACTUAL_ZIP_SHA256" != "$MANIFEST_ZIP_SHA256" ]; then
  echo "ERROR: Archive SHA-256 mismatch! Actual: $ACTUAL_ZIP_SHA256, Expected in manifest: $MANIFEST_ZIP_SHA256"
  exit 1
fi

echo "ZIP archive size ($ACTUAL_ZIP_SIZE bytes) and SHA-256 ($ACTUAL_ZIP_SHA256) match manifest."

# 3. Verify exact ZIP entries and internal bundle_manifest.json
TMP_DIR=$(mktemp -d -t verify_loc_XXXXXX)
trap 'rm -rf "$TMP_DIR"' EXIT

unzip -q "$ZIP_FILE" -d "$TMP_DIR"

REQUIRED_ENTRIES=(
  "bundle_manifest.json"
  "titles_zh_cn.json"
  "tags_zh_cn.json"
  "staff_characters_zh_cn.json"
  "t2s_char_map.json"
)

# Check for unexpected files
ZIP_ENTRIES=($(zipinfo -1 "$ZIP_FILE" | sort))
for entry in "${ZIP_ENTRIES[@]}"; do
  found=false
  for req in "${REQUIRED_ENTRIES[@]}"; do
    if [ "$entry" == "$req" ]; then
      found=true
      break
    fi
  done
  if [ "$found" != "true" ]; then
    echo "ERROR: Unexpected entry found in ZIP: $entry"
    exit 1
  fi
done

if [ "${#ZIP_ENTRIES[@]}" -ne "${#REQUIRED_ENTRIES[@]}" ]; then
  echo "ERROR: ZIP entry count mismatch. Found ${#ZIP_ENTRIES[@]}, expected ${#REQUIRED_ENTRIES[@]}"
  exit 1
fi

# Verify internal bundle_manifest.json matches extracted files
node -e '
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const baseDir = process.argv[1];
const manifestPath = path.join(baseDir, "bundle_manifest.json");
if (!fs.existsSync(manifestPath)) {
  throw new Error("Missing bundle_manifest.json inside ZIP");
}

const internalManifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
if (!internalManifest.files) {
  throw new Error("Missing files in internal bundle_manifest.json");
}

const requiredFiles = [
  "titles_zh_cn.json",
  "tags_zh_cn.json",
  "staff_characters_zh_cn.json",
  "t2s_char_map.json"
];

for (const f of requiredFiles) {
  const filePath = path.join(baseDir, f);
  if (!fs.existsSync(filePath)) {
    throw new Error(`Missing extracted file: ${f}`);
  }
  const content = fs.readFileSync(filePath);
  const actualSize = content.length;
  const actualSha = crypto.createHash("sha256").update(content).digest("hex");

  const entry = internalManifest.files[f];
  if (!entry) {
    throw new Error(`Internal manifest missing file entry: ${f}`);
  }
  if (entry.size !== actualSize) {
    throw new Error(`Size mismatch for ${f}: actual ${actualSize}, expected ${entry.size}`);
  }
  if (entry.sha256 !== actualSha) {
    throw new Error(`SHA-256 mismatch for ${f}: actual ${actualSha}, expected ${entry.sha256}`);
  }
}
' "$TMP_DIR"

echo "All internal file entries and SHA-256 hashes inside bundle_manifest.json verified."
echo "=== Verification SUCCESS ==="
exit 0
