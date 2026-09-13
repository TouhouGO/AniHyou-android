#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SYNC_SCRIPT="${ROOT_DIR}/scripts/sync-upstream.sh"

echo "=== Testing scripts/sync-upstream.sh ==="

if [ ! -f "${SYNC_SCRIPT}" ]; then
  echo "FAILURE: ${SYNC_SCRIPT} does not exist yet (RED phase expected)."
  exit 1
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "${TEMP_DIR}"' EXIT

setup_mock_repos() {
  local base_dir="$1"
  mkdir -p "${base_dir}"

  # 1. Upstream repo
  local upstream_dir="${base_dir}/upstream"
  mkdir -p "${upstream_dir}"
  git -C "${upstream_dir}" init -b main >/dev/null
  git -C "${upstream_dir}" config user.name "Upstream Author"
  git -C "${upstream_dir}" config user.email "upstream@example.com"
  echo "Initial upstream content" > "${upstream_dir}/README.md"
  cat << 'BASE_EOF' > "${upstream_dir}/multiline.txt"
Line 1
Line 2
Line 3
Line 4
Line 5
BASE_EOF
  git -C "${upstream_dir}" add .
  git -C "${upstream_dir}" commit -m "Initial upstream commit" >/dev/null

  # 2. Local clone
  local local_dir="${base_dir}/local"
  git clone "${upstream_dir}" "${local_dir}" >/dev/null
  git -C "${local_dir}" config user.name "Local Developer"
  git -C "${local_dir}" config user.email "developer@example.com"

  # Copy sync script and boundary verifier into mock repo
  mkdir -p "${local_dir}/scripts"
  cp "${SYNC_SCRIPT}" "${local_dir}/scripts/sync-upstream.sh"
  cp "${ROOT_DIR}/scripts/check-repository-hygiene.sh" "${local_dir}/scripts/check-repository-hygiene.sh"
  printf '.local-audit/\n' > "${local_dir}/.gitignore"
  chmod +x "${local_dir}/scripts/sync-upstream.sh"
  chmod +x "${local_dir}/scripts/check-repository-hygiene.sh"

  # Create a mock boundary script in local repo
  cat << 'MOCK_INNER_EOF' > "${local_dir}/scripts/verify-localization-boundary.sh"
#!/usr/bin/env bash
exit 0
MOCK_INNER_EOF
  chmod +x "${local_dir}/scripts/verify-localization-boundary.sh"

  git -C "${local_dir}" add scripts/ .gitignore
  git -C "${local_dir}" commit -m "Add scripts in local" >/dev/null
}

echo "[1/5] Testing dirty worktree rejection..."
MOCK_DIR="${TEMP_DIR}/mock1"
setup_mock_repos "${MOCK_DIR}"
LOCAL_REPO="${MOCK_DIR}/local"
echo "dirty change" >> "${LOCAL_REPO}/README.md"

set +e
"${LOCAL_REPO}/scripts/sync-upstream.sh" --skip-build > "${MOCK_DIR}/dirty.out" 2>&1
DIRTY_EXIT=$?
set -e

if [ "${DIRTY_EXIT}" -eq 0 ]; then
  echo "FAIL: Expected sync-upstream.sh to fail on dirty worktree, but got exit 0."
  cat "${MOCK_DIR}/dirty.out"
  exit 1
fi
if ! grep -qi "dirty\|uncommitted" "${MOCK_DIR}/dirty.out"; then
  echo "FAIL: Error output should mention dirty or uncommitted changes."
  cat "${MOCK_DIR}/dirty.out"
  exit 1
fi
echo "✓ Dirty worktree rejection passed."

echo "[2/5] Testing remote discovery (git config vs URL vs unknown)..."
MOCK_DIR="${TEMP_DIR}/mock2"
setup_mock_repos "${MOCK_DIR}"
LOCAL_REPO="${MOCK_DIR}/local"

# Case A: Unknown remote URL without anihyou.upstreamRemote
git -C "${LOCAL_REPO}" remote rename origin unmapped-remote
set +e
"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run > "${MOCK_DIR}/unknown.out" 2>&1
UNKNOWN_EXIT=$?
set -e
if [ "${UNKNOWN_EXIT}" -eq 0 ]; then
  echo "FAIL: Expected sync-upstream.sh to fail when upstream cannot be detected."
  exit 1
fi
echo "✓ Unknown remote failure passed."

# Case B: Setting anihyou.upstreamRemote config
git -C "${LOCAL_REPO}" config anihyou.upstreamRemote unmapped-remote
set +e
"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run > "${MOCK_DIR}/config_remote.out" 2>&1
CONFIG_EXIT=$?
set -e
if [ "${CONFIG_EXIT}" -ne 0 ]; then
  echo "FAIL: Expected sync-upstream.sh to succeed with anihyou.upstreamRemote configured."
  cat "${MOCK_DIR}/config_remote.out"
  exit 1
fi
echo "✓ anihyou.upstreamRemote config discovery passed."

# Case C: URL matching known upstream URL
git -C "${LOCAL_REPO}" config --unset anihyou.upstreamRemote
git -C "${LOCAL_REPO}" remote add official-repo "https://github.com/axiel7/AniHyou-android.git"
git -C "${LOCAL_REPO}" config remote.official-repo.fetch "+refs/heads/*:refs/remotes/official-repo/*"
git -C "${LOCAL_REPO}" update-ref refs/remotes/official-repo/main HEAD
set +e
"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run --remote official-repo --skip-fetch > "${MOCK_DIR}/official.out" 2>&1
OFFICIAL_EXIT=$?
set -e
if [ "${OFFICIAL_EXIT}" -ne 0 ]; then
  echo "FAIL: Expected explicit or URL matched remote to succeed."
  cat "${MOCK_DIR}/official.out"
  exit 1
fi
echo "✓ Known upstream URL matching passed."

echo "[3/5] Testing --dry-run does not alter refs or worktree..."
MOCK_DIR="${TEMP_DIR}/mock3"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

echo "Upstream feature" > "${UPSTREAM_REPO}/feature.txt"
git -C "${UPSTREAM_REPO}" add feature.txt
git -C "${UPSTREAM_REPO}" commit -m "Upstream new feature" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin
BEFORE_HEAD=$(git -C "${LOCAL_REPO}" rev-parse HEAD)

"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run --skip-build > "${MOCK_DIR}/dryrun.out"

AFTER_HEAD=$(git -C "${LOCAL_REPO}" rev-parse HEAD)
if [ "${BEFORE_HEAD}" != "${AFTER_HEAD}" ]; then
  echo "FAIL: --dry-run altered HEAD from ${BEFORE_HEAD} to ${AFTER_HEAD}!"
  exit 1
fi
BACKUP_REFS=$(git -C "${LOCAL_REPO}" for-each-ref "refs/codex/upstream-backup/*")
if [ -n "${BACKUP_REFS}" ]; then
  echo "FAIL: --dry-run created backup refs!"
  exit 1
fi
echo "✓ --dry-run immutability passed."

echo "[4/5] Testing clean merge, backup ref creation, and audit report generation..."
MOCK_DIR="${TEMP_DIR}/mock4"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

echo "Upstream clean file" > "${UPSTREAM_REPO}/clean.txt"
git -C "${UPSTREAM_REPO}" add clean.txt
git -C "${UPSTREAM_REPO}" commit -m "Upstream clean commit" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin
BEFORE_HEAD=$(git -C "${LOCAL_REPO}" rev-parse HEAD)

"${LOCAL_REPO}/scripts/sync-upstream.sh" --skip-build > "${MOCK_DIR}/merge.out"

BACKUP_REFS=$(git -C "${LOCAL_REPO}" for-each-ref --format="%(refname) %(objectname)" "refs/codex/upstream-backup/*")
if [ -z "${BACKUP_REFS}" ]; then
  echo "FAIL: No backup ref was created in refs/codex/upstream-backup/*"
  exit 1
fi
BACKUP_SHA=$(echo "${BACKUP_REFS}" | awk '{print $2}')
if [ "${BACKUP_SHA}" != "${BEFORE_HEAD}" ]; then
  echo "FAIL: Backup ref SHA ${BACKUP_SHA} != before HEAD ${BEFORE_HEAD}"
  exit 1
fi

if [ ! -f "${LOCAL_REPO}/clean.txt" ]; then
  echo "FAIL: clean.txt was not merged into local worktree!"
  exit 1
fi

REPORT_FILES=(${LOCAL_REPO}/.local-audit/*-upstream-sync-report.md)
if [ ! -f "${REPORT_FILES[0]}" ]; then
  echo "FAIL: Local-only upstream sync report was not generated!"
  exit 1
fi

REPORT_CONTENT=$(cat "${REPORT_FILES[0]}")
for expected_keyword in "前置 Commit" "上游 Commit" "合并后 Commit" "恢复命令"; do
  if ! echo "${REPORT_CONTENT}" | grep -q "${expected_keyword}"; then
    echo "FAIL: Report missing required section: ${expected_keyword}"
    exit 1
  fi
done
echo "✓ Clean merge, backup ref, and audit report generation passed."

echo "[5/5] Testing conflict retention and recovery instructions..."
MOCK_DIR="${TEMP_DIR}/mock5"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

echo "Conflict content A" > "${UPSTREAM_REPO}/conflict.txt"
git -C "${UPSTREAM_REPO}" add conflict.txt
git -C "${UPSTREAM_REPO}" commit -m "Upstream conflicting commit" >/dev/null

echo "Conflict content B" > "${LOCAL_REPO}/conflict.txt"
git -C "${LOCAL_REPO}" add conflict.txt
git -C "${LOCAL_REPO}" commit -m "Local conflicting commit" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin

set +e
"${LOCAL_REPO}/scripts/sync-upstream.sh" --skip-build > "${MOCK_DIR}/conflict.out" 2>&1
CONFLICT_EXIT=$?
set -e

if [ "${CONFLICT_EXIT}" -eq 0 ]; then
  echo "FAIL: Expected conflict to cause non-zero exit code!"
  exit 1
fi

if ! grep -qi "conflict" "${MOCK_DIR}/conflict.out"; then
  echo "FAIL: Conflict output did not mention conflicts."
  cat "${MOCK_DIR}/conflict.out"
  exit 1
fi

if ! grep -q "refs/codex/upstream-backup/" "${MOCK_DIR}/conflict.out"; then
  echo "FAIL: Conflict output did not provide recovery ref command."
  cat "${MOCK_DIR}/conflict.out"
  exit 1
fi
echo "✓ Conflict retention and recovery instructions passed."

echo "[6/8] Testing dry-run predicted conflict fixture (same-file same-region conflict)..."
MOCK_DIR="${TEMP_DIR}/mock6"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

echo "Line 1 - upstream" > "${UPSTREAM_REPO}/conflict_file.txt"
git -C "${UPSTREAM_REPO}" add conflict_file.txt
git -C "${UPSTREAM_REPO}" commit -m "Upstream conflicting edit" >/dev/null

echo "Line 1 - local" > "${LOCAL_REPO}/conflict_file.txt"
git -C "${LOCAL_REPO}" add conflict_file.txt
git -C "${LOCAL_REPO}" commit -m "Local conflicting edit" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin

"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run --skip-build > "${MOCK_DIR}/dryrun_conflict.out"

DRY_REPORT_FILES=(${LOCAL_REPO}/.local-audit/*-upstream-sync-report.md)
if [ ! -f "${DRY_REPORT_FILES[0]}" ]; then
  echo "FAIL: Expected dry-run report to be generated in mock6."
  exit 1
fi
DRY_REPORT_CONTENT=$(cat "${DRY_REPORT_FILES[0]}")

# Assert all 9 required sections
for section in "UPSTREAM_REMOTE" "UPSTREAM_REF" "CURRENT_COMMIT" "UPSTREAM_COMMIT" "COMMITS_TO_IMPORT" "BOTH_CHANGED" "PREDICTED_CONFLICT" "WORKTREE_STATUS" "DRY_RUN_RESULT"; do
  if ! echo "${DRY_REPORT_CONTENT}" | grep -q "## ${section}"; then
    echo "FAIL: Dry-run report missing section '## ${section}'"
    exit 1
  fi
done

# Assert conflict path is reported cleanly
if ! echo "${DRY_REPORT_CONTENT}" | grep -q "conflict_file.txt"; then
  echo "FAIL: Dry-run report does not list 'conflict_file.txt' under PREDICTED_CONFLICT"
  cat "${DRY_REPORT_FILES[0]}"
  exit 1
fi

# Assert no diff markers or noisy strings
for marker in "<<<<<<<" "=======" ">>>>>>>" "changed in both"; do
  if echo "${DRY_REPORT_CONTENT}" | grep -q "${marker}"; then
    echo "FAIL: Dry-run report contains invalid marker: ${marker}"
    exit 1
  fi
done
echo "✓ Dry-run predicted conflict fixture passed."

echo "[7/8] Testing dry-run clean 3-way merge fixture (both changed different lines)..."
MOCK_DIR="${TEMP_DIR}/mock7"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

# Upstream changes line 1
cat << 'UP_EOF' > "${UPSTREAM_REPO}/multiline.txt"
Line 1 - Upstream Modified
Line 2
Line 3
Line 4
Line 5
UP_EOF
git -C "${UPSTREAM_REPO}" commit -am "Upstream edit line 1" >/dev/null

# Local changes line 5
cat << 'LOC_EOF' > "${LOCAL_REPO}/multiline.txt"
Line 1
Line 2
Line 3
Line 4
Line 5 - Local Modified
LOC_EOF
git -C "${LOCAL_REPO}" commit -am "Local edit line 5" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin

"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run --skip-build > "${MOCK_DIR}/dryrun_clean_merge.out"

CLEAN_REPORT_FILES=(${LOCAL_REPO}/.local-audit/*-upstream-sync-report.md)
CLEAN_REPORT_CONTENT=$(cat "${CLEAN_REPORT_FILES[0]}")

# multiline.txt should be in BOTH_CHANGED
if ! echo "${CLEAN_REPORT_CONTENT}" | grep -A 2 "## BOTH_CHANGED" | grep -q "multiline.txt"; then
  echo "FAIL: multiline.txt should be in BOTH_CHANGED for 3-way clean merge."
  cat "${CLEAN_REPORT_FILES[0]}"
  exit 1
fi

# multiline.txt should NOT be in PREDICTED_CONFLICT
if echo "${CLEAN_REPORT_CONTENT}" | grep -A 2 "## PREDICTED_CONFLICT" | grep -q "multiline.txt"; then
  echo "FAIL: multiline.txt should NOT be in PREDICTED_CONFLICT when 3-way merge is clean!"
  cat "${CLEAN_REPORT_FILES[0]}"
  exit 1
fi

if ! echo "${CLEAN_REPORT_CONTENT}" | grep -A 2 "## PREDICTED_CONFLICT" | grep -q "NONE"; then
  echo "FAIL: Expected PREDICTED_CONFLICT to be NONE for clean 3-way merge."
  cat "${CLEAN_REPORT_FILES[0]}"
  exit 1
fi
echo "✓ Dry-run clean 3-way merge fixture passed."

echo "[8/8] Testing dry-run no-overlap fixture (unrelated files)..."
MOCK_DIR="${TEMP_DIR}/mock8"
setup_mock_repos "${MOCK_DIR}"
UPSTREAM_REPO="${MOCK_DIR}/upstream"
LOCAL_REPO="${MOCK_DIR}/local"

echo "upstream unique file" > "${UPSTREAM_REPO}/upstream_only.txt"
git -C "${UPSTREAM_REPO}" add upstream_only.txt
git -C "${UPSTREAM_REPO}" commit -m "Upstream only commit" >/dev/null

echo "local unique file" > "${LOCAL_REPO}/local_only.txt"
git -C "${LOCAL_REPO}" add local_only.txt
git -C "${LOCAL_REPO}" commit -m "Local only commit" >/dev/null

git -C "${LOCAL_REPO}" config anihyou.upstreamRemote origin

"${LOCAL_REPO}/scripts/sync-upstream.sh" --dry-run --skip-build > "${MOCK_DIR}/dryrun_no_overlap.out"

NO_OVERLAP_REPORT=(${LOCAL_REPO}/.local-audit/*-upstream-sync-report.md)
NO_OVERLAP_CONTENT=$(cat "${NO_OVERLAP_REPORT[0]}")

if ! echo "${NO_OVERLAP_CONTENT}" | grep -A 2 "## BOTH_CHANGED" | grep -q "NONE"; then
  echo "FAIL: Expected BOTH_CHANGED to be NONE for unrelated file edits."
  cat "${NO_OVERLAP_REPORT[0]}"
  exit 1
fi

if ! echo "${NO_OVERLAP_CONTENT}" | grep -A 2 "## PREDICTED_CONFLICT" | grep -q "NONE"; then
  echo "FAIL: Expected PREDICTED_CONFLICT to be NONE for unrelated file edits."
  cat "${NO_OVERLAP_REPORT[0]}"
  exit 1
fi
echo "✓ Dry-run no-overlap fixture passed."

echo "=== All sync-upstream.sh tests PASSED ==="
