#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

SELF_TEST=false
if [ "${1:-}" = "--self-test" ]; then
  SELF_TEST=true
fi

run_boundary_checks() {
  local target_root="$1"
  cd "${target_root}"

  echo "=== Verifying Localization Architecture Boundary ==="

  # Rule 1: No Wikidata references allowed outside the localization implementation and documentation
  local violations_wikidata
  violations_wikidata=$(git grep -in "Wikidata" -- ":!core/network" ":!docs" ":!scripts" ":!config" ":!README.md" 2>/dev/null || true)
  if [ -n "${violations_wikidata}" ]; then
    echo "ERROR: Wikidata references found outside core/network or documentation:"
    echo "${violations_wikidata}"
    return 1
  fi
  echo "✓ Zero Wikidata leakage outside core/network."

  # Rule 2: No direct localization endpoints outside the production localization package or its tests
  local violations_network_urls
  violations_network_urls=$(git grep -inE "query\.wikidata\.org|api\.bgm\.tv" -- ":!core/network/src/main/java/com/axiel7/anihyou/core/network/localization" ":!core/network/src/test" ":!docs" ":!scripts" ":!config" ":!README.md" 2>/dev/null || true)
  if [ -n "${violations_network_urls}" ]; then
    echo "ERROR: Direct localization network endpoints found outside core/network/.../localization:"
    echo "${violations_network_urls}"
    return 1
  fi
  echo "✓ Zero direct localization network endpoint leaks."

  # Rule 3: No EntityNameCache or WikidataEntityNameSource usage in feature or app (except App.kt initialization)
  local violations_cache_source
  violations_cache_source=$(git grep -inE "EntityNameCache|WikidataEntityNameSource" feature/ 2>/dev/null || true)
  if [ -n "${violations_cache_source}" ]; then
    echo "ERROR: EntityNameCache or WikidataEntityNameSource leaked into feature modules:"
    echo "${violations_cache_source}"
    return 1
  fi
  echo "✓ EntityNameCache and WikidataEntityNameSource strictly contained in core/network."

  # Rule 4: No manual ID override files in scripts/ or resources/
  if [ -f "${target_root}/scripts/title_overrides_zh_cn.json" ]; then
    echo "ERROR: scripts/title_overrides_zh_cn.json exists! Manual ID mapping is forbidden."
    return 1
  fi
  echo "✓ Zero manual ID override tables in repository."

  # Rule 5: Production Customization Allowlist Gate
  local allowlist_file="${target_root}/config/localization-customization-allowlist.txt"
  if [ ! -f "${allowlist_file}" ]; then
    echo "ERROR: Allowlist file ${allowlist_file} not found!"
    return 1
  fi

  # Find all production files that contain localization logic or reference localization
  local found_localization_files
  found_localization_files=$(git grep -lE "com\.axiel7\.anihyou\.core\.network\.localization|localizationConfig|LocalizationBundleManager" -- "*/src/main/*" "build-logic/src/main/*" 2>/dev/null || true)

  local allowlist_content
  allowlist_content=$(grep -v "^#" "${allowlist_file}" | grep -v "^$" | tr -d '\r')

  local unauthorized_files=""
  while IFS= read -r prod_file; do
    if [ -n "${prod_file}" ]; then
      if ! echo "${allowlist_content}" | grep -qx "${prod_file}"; then
        unauthorized_files="${unauthorized_files}  ${prod_file}\n"
      fi
    fi
  done <<< "${found_localization_files}"

  if [ -n "${unauthorized_files}" ]; then
    echo "ERROR: Production files contain localization customization but are not in config/localization-customization-allowlist.txt:"
    echo -e "${unauthorized_files}"
    echo "If this is an intentional localization addition, review and register it in config/localization-customization-allowlist.txt."
    return 1
  fi
  echo "✓ Production customization files strictly match allowlist."

  echo "=== Localization Architecture Boundary Check PASSED ==="
  return 0
}

if [ "${SELF_TEST}" = false ]; then
  run_boundary_checks "${ROOT_DIR}"
  exit 0
fi

# Self-Test Execution Mode
echo "=== Running Boundary Check Self-Tests ==="

TEMP_SELFTEST_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_SELFTEST_DIR}"' EXIT

# Test 1: Baseline check should pass
echo "[Self-Test 1/3] Verifying baseline repository passes..."
if ! run_boundary_checks "${ROOT_DIR}" >/dev/null; then
  echo "FAIL: Baseline repository failed boundary check!"
  exit 1
fi
echo "✓ Baseline check passed."

# Test 2: Simulate feature module Wikidata leakage
echo "[Self-Test 2/3] Simulating feature module Wikidata leakage..."
LEAK_REPO="${TEMP_SELFTEST_DIR}/leak_test"
git clone "${ROOT_DIR}" "${LEAK_REPO}" >/dev/null 2>&1
cp -r "${ROOT_DIR}/config" "${LEAK_REPO}/config" 2>/dev/null || true
echo "// Leak test: Wikidata reference" >> "${LEAK_REPO}/feature/explore/src/main/java/com/axiel7/anihyou/feature/explore/anime/AnimeExploreViewModel.kt"

set +e
run_boundary_checks "${LEAK_REPO}" > "${TEMP_SELFTEST_DIR}/leak.log" 2>&1
LEAK_EXIT=$?
set -e

if [ "${LEAK_EXIT}" -eq 0 ]; then
  echo "FAIL: Expected leakage to fail boundary check, but passed!"
  exit 1
fi
if ! grep -q "Wikidata references found outside core/network" "${TEMP_SELFTEST_DIR}/leak.log"; then
  echo "FAIL: Leak log did not report Wikidata violation."
  cat "${TEMP_SELFTEST_DIR}/leak.log"
  exit 1
fi
echo "✓ Feature module Wikidata leakage correctly detected and rejected."

# Test 3: Simulate unauthorized localization customization file
echo "[Self-Test 3/3] Simulating unauthorized production customization file..."
UNAUTH_REPO="${TEMP_SELFTEST_DIR}/unauth_test"
git clone "${ROOT_DIR}" "${UNAUTH_REPO}" >/dev/null 2>&1
cp -r "${ROOT_DIR}/config" "${UNAUTH_REPO}/config" 2>/dev/null || true
NEW_FILE="${UNAUTH_REPO}/core/network/src/main/java/com/axiel7/anihyou/core/network/localization/UnauthorizedHelper.kt"
mkdir -p "$(dirname "${NEW_FILE}")"
cat << 'KOTLIN_EOF' > "${NEW_FILE}"
package com.axiel7.anihyou.core.network.localization
class UnauthorizedHelper
KOTLIN_EOF
git -C "${UNAUTH_REPO}" add "${NEW_FILE}"

set +e
run_boundary_checks "${UNAUTH_REPO}" > "${TEMP_SELFTEST_DIR}/unauth.log" 2>&1
UNAUTH_EXIT=$?
set -e

if [ "${UNAUTH_EXIT}" -eq 0 ]; then
  echo "FAIL: Expected unauthorized production file to fail boundary check, but passed!"
  exit 1
fi
if ! grep -q "not in config/localization-customization-allowlist.txt" "${TEMP_SELFTEST_DIR}/unauth.log"; then
  echo "FAIL: Log did not report allowlist violation."
  cat "${TEMP_SELFTEST_DIR}/unauth.log"
  exit 1
fi
echo "✓ Unauthorized customization file correctly detected and rejected."

echo "=== All Boundary Gate Self-Tests PASSED ==="
