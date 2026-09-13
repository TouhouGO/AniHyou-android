#!/usr/bin/env bash
set -euo pipefail

echo "=== Testing Release Workflow Shell Logic ==="

# Test 1: Tag validation regex
validate_tag() {
  local tag="$1"
  if [[ ! "$tag" =~ ^v[0-9]{4}\.[0-9]{2}\.[0-9]{2}$ ]]; then
    return 1
  fi
  return 0
}

echo "Testing tag validation..."
if validate_tag "v2026.09.09"; then
  echo "  ✓ Valid tag v2026.09.09 accepted"
else
  echo "  ✗ Valid tag rejected"
  exit 1
fi

if ! validate_tag "invalid-tag"; then
  echo "  ✓ Invalid tag rejected"
else
  echo "  ✗ Invalid tag accepted"
  exit 1
fi

if ! validate_tag "v2026.9.9"; then
  echo "  ✓ Non-padded tag rejected"
else
  echo "  ✗ Non-padded tag accepted"
  exit 1
fi

# Test 2: Mocking gh CLI calls for Draft-First, Non-Destructive logic
TMP_TEST_DIR=$(mktemp -d -t test_wf_XXXXXX)
trap 'rm -rf "$TMP_TEST_DIR"' EXIT

MOCK_GH="$TMP_TEST_DIR/gh"
CALL_LOG="$TMP_TEST_DIR/calls.log"

cat << 'MOCK_EOF' > "$MOCK_GH"
#!/usr/bin/env bash
echo "$@" >> "$MOCK_CALL_LOG"

if [ "$1" = "release" ] && [ "$2" = "view" ]; then
  if [ "${MOCK_RELEASE_EXISTS:-false}" = "true" ]; then
    for arg in "$@"; do
      if [ "$arg" = "--json" ]; then
        if [ "${MOCK_IS_DRAFT:-false}" = "true" ]; then
          echo '{"isDraft":true}'
        else
          echo '{"isDraft":false}'
        fi
        exit 0
      fi
    done
    exit 0
  else
    exit 1
  fi
fi

if [ "$1" = "release" ] && [ "$2" = "download" ]; then
  dest_dir=""
  while [ $# -gt 0 ]; do
    if [ "$1" = "-D" ]; then
      dest_dir="$2"
      break
    fi
    shift
  done
  if [ -n "$dest_dir" ]; then
    cp "$MOCK_LOCAL_ZIP" "$dest_dir/localization_bundle.zip"
  fi
  exit 0
fi

exit 0
MOCK_EOF
chmod +x "$MOCK_GH"

# Create dummy zip and manifest
TEST_ZIP="$TMP_TEST_DIR/localization_bundle.zip"
TEST_MANIFEST="$TMP_TEST_DIR/remote_bundle_manifest.json"
echo "dummy-zip-content" > "$TEST_ZIP"
echo '{"dummy":"manifest"}' > "$TEST_MANIFEST"

run_workflow_publication() {
  local tag="$1"
  local publish="$2"
  local release_exists="$3"
  local is_draft="$4"

  > "$CALL_LOG"
  export MOCK_CALL_LOG="$CALL_LOG"
  export MOCK_RELEASE_EXISTS="$release_exists"
  export MOCK_IS_DRAFT="$is_draft"
  export MOCK_LOCAL_ZIP="$TEST_ZIP"
  export PATH="$TMP_TEST_DIR:$PATH"

  TAG="$tag"
  PUBLISH="$publish"
  ZIP_FILE="$TEST_ZIP"
  MANIFEST_FILE="$TEST_MANIFEST"

  # Run the exact publication script block
  if "$MOCK_GH" release view "$TAG" > /dev/null 2>&1; then
    IS_DRAFT=$("$MOCK_GH" release view "$TAG" --json isDraft -q .isDraft | grep -o '"isDraft":[^}]*' | cut -d: -f2)
    if [ "$IS_DRAFT" != "true" ]; then
      echo "ERROR: Release $TAG is already published!"
      return 42
    fi
  else
    "$MOCK_GH" release create "$TAG" --title "Localization Bundle $TAG" --notes "..." --draft
  fi

  "$MOCK_GH" release upload "$TAG" "$ZIP_FILE" --clobber

  VERIFY_DIR=$(mktemp -d -t mock_ver_XXXXXX)
  "$MOCK_GH" release download "$TAG" -p "localization_bundle.zip" -D "$VERIFY_DIR"
  LOCAL_SHA=$(shasum -a 256 "$ZIP_FILE" | awk '{print $1}')
  REMOTE_SHA=$(shasum -a 256 "$VERIFY_DIR/localization_bundle.zip" | awk '{print $1}')
  rm -rf "$VERIFY_DIR"

  if [ "$LOCAL_SHA" != "$REMOTE_SHA" ]; then
    echo "ERROR: Uploaded ZIP integrity check failed!"
    return 43
  fi

  "$MOCK_GH" release upload "$TAG" "$MANIFEST_FILE" --clobber

  if [ "$PUBLISH" = "true" ]; then
    "$MOCK_GH" release edit "$TAG" --draft=false
  fi

  return 0
}

echo "Testing Scenario A: New tag, publish=true..."
run_workflow_publication "v2026.09.09" "true" "false" "false"
grep -q "release create v2026.09.09 .* --draft" "$CALL_LOG"
grep -q "release upload v2026.09.09 .*localization_bundle.zip" "$CALL_LOG"
grep -q "release download v2026.09.09 -p localization_bundle.zip" "$CALL_LOG"
grep -q "release upload v2026.09.09 .*remote_bundle_manifest.json" "$CALL_LOG"
grep -q "release edit v2026.09.09 --draft=false" "$CALL_LOG"
echo "  ✓ New tag publication sequence verified"

echo "Testing stable manifest pointer publication..."
grep -q 'POINTER_TAG="localization-latest"' .github/workflows/release-localization-bundle.yaml
grep -q 'gh release upload "$POINTER_TAG" "$MANIFEST_FILE" --clobber' .github/workflows/release-localization-bundle.yaml
echo "  ✓ Published bundles update the stable manifest pointer"

echo "Testing Scenario B: Existing published release must be blocked..."
set +e
run_workflow_publication "v2026.09.09" "true" "true" "false"
RET=$?
set -e
if [ "$RET" -eq 42 ]; then
  echo "  ✓ Correctly rejected overwriting published release"
else
  echo "  ✗ Failed to block overwriting published release (code $RET)"
  exit 1
fi

echo "Testing Scenario C: Existing draft release with publish=false..."
run_workflow_publication "v2026.09.09" "false" "true" "true"
if grep "release edit" "$CALL_LOG" > /dev/null 2>&1; then
  echo "  ✗ Should not publish when publish=false"
  exit 1
else
  echo "  ✓ Left in draft state when publish=false"
fi

echo "=== All Workflow Shell Tests PASSED ==="
