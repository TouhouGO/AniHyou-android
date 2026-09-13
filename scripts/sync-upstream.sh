#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DRY_RUN=false
TARGET_REF=""
EXPLICIT_REMOTE=""
SKIP_BUILD=false
SKIP_FETCH=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=true
      shift
      ;;
    --ref)
      TARGET_REF="$2"
      shift 2
      ;;
    --remote)
      EXPLICIT_REMOTE="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-fetch)
      SKIP_FETCH=true
      shift
      ;;
    *)
      echo "Unknown option: $1"
      echo "Usage: $0 [--dry-run] [--remote <name>] [--ref <ref>] [--skip-build] [--skip-fetch]"
      exit 1
      ;;
  esac
done

cd "${ROOT_DIR}"

echo "=== AniHyou Upstream Synchronization ==="

if [ -f "${ROOT_DIR}/scripts/check-repository-hygiene.sh" ]; then
  "${ROOT_DIR}/scripts/check-repository-hygiene.sh"
fi

# 1. Check for dirty working tree
DIRTY_FILES=$(git status --porcelain || true)
if [ -n "${DIRTY_FILES}" ]; then
  if [ "${DRY_RUN}" = true ]; then
    echo "WARNING: Working tree is dirty. DRY-RUN mode will inspect changes and report without modifying any files."
  else
    echo "ERROR: Working tree is dirty or has uncommitted changes:"
    git status --short
    echo "Please commit or stash your changes before syncing upstream."
    exit 1
  fi
fi

# 2. Discover upstream remote
REMOTE=""
if [ -n "${EXPLICIT_REMOTE}" ]; then
  REMOTE="${EXPLICIT_REMOTE}"
else
  CONFIG_REMOTE=$(git config --get anihyou.upstreamRemote || true)
  if [ -n "${CONFIG_REMOTE}" ]; then
    REMOTE="${CONFIG_REMOTE}"
  else
    # Auto-match known upstream repository URLs
    MATCHING_REMOTES=()
    while IFS= read -r line; do
      if [ -n "${line}" ]; then
        r_name=$(echo "${line}" | awk '{print $1}')
        r_url=$(echo "${line}" | awk '{print $2}')
        if echo "${r_url}" | grep -qiE "axiel7/AniHyou-android(\.git)?"; then
          # Avoid duplicate entries
          if [[ ! " ${MATCHING_REMOTES[*]:-} " =~ " ${r_name} " ]]; then
            MATCHING_REMOTES+=("${r_name}")
          fi
        fi
      fi
    done < <(git remote -v | grep "(fetch)" || true)

    if [ ${#MATCHING_REMOTES[@]} -eq 1 ]; then
      REMOTE="${MATCHING_REMOTES[0]}"
      echo "Discovered upstream remote by URL: ${REMOTE}"
    elif [ ${#MATCHING_REMOTES[@]} -gt 1 ]; then
      echo "ERROR: Multiple remotes match the upstream repository URL (${MATCHING_REMOTES[*]})."
      echo "Please specify one using --remote <name> or configure it:"
      echo "  git config anihyou.upstreamRemote <name>"
      exit 1
    else
      echo "ERROR: Cannot detect upstream remote."
      echo "No remote found matching known upstream URL and 'anihyou.upstreamRemote' is not configured."
      echo "Please specify using --remote <name> or configure it:"
      echo "  git config anihyou.upstreamRemote <name>"
      exit 1
    fi
  fi
fi

# Verify remote exists
if ! git remote | grep -qx "${REMOTE}"; then
  echo "ERROR: Remote '${REMOTE}' does not exist in this repository."
  exit 1
fi

REMOTE_URL=$(git remote get-url "${REMOTE}" 2>/dev/null || echo "Unknown URL")

# 3. Fetch remote if not skipped
if [ "${SKIP_FETCH}" = false ]; then
  echo "Fetching remote '${REMOTE}' (${REMOTE_URL})..."
  git fetch "${REMOTE}"
fi

# 4. Resolve target ref
if [ -z "${TARGET_REF}" ]; then
  # Try remote HEAD symbolic ref
  SYM_REF=$(git symbolic-ref "refs/remotes/${REMOTE}/HEAD" 2>/dev/null || true)
  if [ -n "${SYM_REF}" ]; then
    TARGET_REF="${SYM_REF#refs/remotes/}"
  elif git rev-parse --verify --quiet "refs/remotes/${REMOTE}/main" >/dev/null; then
    TARGET_REF="${REMOTE}/main"
  elif git rev-parse --verify --quiet "refs/remotes/${REMOTE}/master" >/dev/null; then
    TARGET_REF="${REMOTE}/master"
  else
    echo "ERROR: Could not automatically determine default ref for '${REMOTE}'."
    echo "Please specify the ref explicitly via --ref <ref> (e.g. --ref ${REMOTE}/master)"
    exit 1
  fi
fi

# Validate target ref resolves
if ! git rev-parse --verify "${TARGET_REF}" >/dev/null 2>&1; then
  # Try prefixing remote name if omitted
  if git rev-parse --verify "${REMOTE}/${TARGET_REF}" >/dev/null 2>&1; then
    TARGET_REF="${REMOTE}/${TARGET_REF}"
  else
    echo "ERROR: Target ref '${TARGET_REF}' could not be resolved."
    exit 1
  fi
fi

UPSTREAM_SHA=$(git rev-parse "${TARGET_REF}")
BEFORE_HEAD=$(git rev-parse HEAD)
COMMITS_AHEAD=$(git rev-list --count HEAD.."${TARGET_REF}")

echo "Upstream Remote: ${REMOTE}"
echo "Remote URL:      ${REMOTE_URL}"
echo "Target Ref:      ${TARGET_REF} (${UPSTREAM_SHA:0:10})"
echo "Current HEAD:    ${BEFORE_HEAD:0:10}"
echo "Incoming Commits: ${COMMITS_AHEAD}"

TODAY=$(date +"%Y-%m-%d")

# 5. Handle --dry-run
if [ "${DRY_RUN}" = true ]; then
  echo "=== DRY-RUN MODE: No changes will be applied ==="

  # 1. Commits to import
  COMMITS_TO_IMPORT_LIST="NONE"
  if [ "${COMMITS_AHEAD}" -gt 0 ]; then
    COMMITS_TO_IMPORT_LIST=$(git log --oneline HEAD.."${TARGET_REF}")
  fi

  # 2. Both changed files
  MB=$(git merge-base HEAD "${TARGET_REF}" 2>/dev/null || true)
  BOTH_CHANGED_LIST="NONE"
  if [ -n "${MB}" ]; then
    OUR_DIFF=$(git diff --name-only "${MB}" HEAD 2>/dev/null || true)
    THEIR_DIFF=$(git diff --name-only "${MB}" "${TARGET_REF}" 2>/dev/null || true)
    if [ -n "${OUR_DIFF}" ] && [ -n "${THEIR_DIFF}" ]; then
      INTERSECTION=$(comm -12 <(echo "${OUR_DIFF}" | sort) <(echo "${THEIR_DIFF}" | sort) | grep -v '^$' || true)
      if [ -n "${INTERSECTION}" ]; then
        BOTH_CHANGED_LIST="${INTERSECTION}"
      fi
    fi
  fi

  # 3. Predicted conflicts (clean relative paths only, no diff markers)
  PREDICTED_CONFLICTS_LIST="NONE"
  if [ -n "${MB}" ]; then
    TREE_OUT=$(git merge-tree --write-tree --name-only HEAD "${TARGET_REF}" 2>&1 || true)
    CONFLICT_PATHS=$(echo "${TREE_OUT}" | sed -n '2,/^$/p' | grep -v '^[[:space:]]*$' | sort -u || true)
    if [ -z "${CONFLICT_PATHS}" ]; then
      CONFLICT_PATHS=$(echo "${TREE_OUT}" | grep "^CONFLICT" | sed -E 's/.*in (.*)/\1/' | sort -u | grep -v '^$' || true)
    fi
    if [ -n "${CONFLICT_PATHS}" ]; then
      PREDICTED_CONFLICTS_LIST="${CONFLICT_PATHS}"
    fi
  fi

  # 4. Worktree status
  WORKTREE_STATUS_LIST="CLEAN"
  if [ -n "${DIRTY_FILES}" ]; then
    WORKTREE_STATUS_LIST="$(git status --short)"
  fi

  # 5. Dry run result description
  DRY_RUN_RESULT_DESC="SUCCESS (Dry-run inspection completed cleanly. No modifications were made to working tree, refs, or local branches.)"

  REPORT_FILE="${ANIHYOU_AUDIT_DIR:-${ROOT_DIR}/.local-audit}/${TODAY}-upstream-sync-report.md"
  mkdir -p "$(dirname "${REPORT_FILE}")"

  cat << RPT_EOF > "${REPORT_FILE}"
# 上游代码同步审计报告 (DRY-RUN 预检)

- **执行模式**: DRY-RUN 预检模式（未修改任何本地分支、Refs 或工作树）
- **执行日期**: ${TODAY}
- **UPSTREAM_REMOTE**: ${REMOTE}
- **UPSTREAM_REF**: ${TARGET_REF}
- **CURRENT_COMMIT**: \`${BEFORE_HEAD}\`
- **UPSTREAM_COMMIT**: \`${UPSTREAM_SHA}\`
- **DRY_RUN_RESULT**: ${DRY_RUN_RESULT_DESC}

## UPSTREAM_REMOTE
${REMOTE} (${REMOTE_URL})

## UPSTREAM_REF
${TARGET_REF}

## CURRENT_COMMIT
${BEFORE_HEAD}

## UPSTREAM_COMMIT
${UPSTREAM_SHA}

## COMMITS_TO_IMPORT
${COMMITS_TO_IMPORT_LIST}

## BOTH_CHANGED
${BOTH_CHANGED_LIST}

## PREDICTED_CONFLICT
${PREDICTED_CONFLICTS_LIST}

## WORKTREE_STATUS
${WORKTREE_STATUS_LIST}

## DRY_RUN_RESULT
${DRY_RUN_RESULT_DESC}

## 恢复说明

由于处于 DRY-RUN 预检模式，未修改任何 Refs 或文件系统。如若后续执行正式同步，恢复命令将基于同步前生成的安全备份快照：
\`\`\`bash
git reset --hard refs/codex/upstream-backup/<timestamp>
\`\`\`
RPT_EOF

  echo "✓ DRY-RUN completed successfully."
  echo "Audit report written to: ${REPORT_FILE}"
  exit 0
fi

# 6. Create safety backup ref before modification
TIMESTAMP=$(date +"%Y%m%d%H%M%S")
BACKUP_REF="refs/codex/upstream-backup/${TIMESTAMP}"
git update-ref "${BACKUP_REF}" "${BEFORE_HEAD}"
echo "Created safety backup ref: ${BACKUP_REF} -> ${BEFORE_HEAD:0:10}"

# 7. Execute merge
echo "Merging ${TARGET_REF} into current HEAD..."
set +e
git merge --no-edit "${TARGET_REF}"
MERGE_EXIT=$?
set -e

if [ "${MERGE_EXIT}" -ne 0 ]; then
  echo ""
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  echo "ERROR: Merge conflict detected during upstream sync!"
  echo "Conflicted files:"
  git diff --name-only --diff-filter=U || true
  echo "--------------------------------------------------------------"
  echo "Recovery instructions:"
  echo "1. To abort this merge completely and return to pre-sync state:"
  echo "   git merge --abort"
  echo "   git reset --hard ${BACKUP_REF}"
  echo "2. To resolve conflicts manually:"
  echo "   Resolve the conflicting files, then run:"
  echo "   ./scripts/verify-localization-boundary.sh"
  echo "   ./scripts/check-repository-hygiene.sh"
  echo "   git commit"
  echo "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"
  exit "${MERGE_EXIT}"
fi

AFTER_HEAD=$(git rev-parse HEAD)
echo "Merge successful: ${AFTER_HEAD:0:10}"

# 8. Post-merge boundary check
echo "Running architecture boundary check..."
if [ -f "${ROOT_DIR}/scripts/verify-localization-boundary.sh" ]; then
  "${ROOT_DIR}/scripts/verify-localization-boundary.sh"
fi
if [ -f "${ROOT_DIR}/scripts/check-repository-hygiene.sh" ]; then
  "${ROOT_DIR}/scripts/check-repository-hygiene.sh"
fi

# 9. Post-merge builds if requested
BUILD_SUMMARY="Skipped via --skip-build"
if [ "${SKIP_BUILD}" = false ]; then
  echo "Running post-merge test suite and builds..."
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test :app:assembleFossDebug :app:assembleGmsDebug
  BUILD_SUMMARY="PASSED (unit tests, assembleFossDebug, assembleGmsDebug)"
fi

# 10. Generate Audit Report
REPORT_FILE="${ANIHYOU_AUDIT_DIR:-${ROOT_DIR}/.local-audit}/${TODAY}-upstream-sync-report.md"
mkdir -p "$(dirname "${REPORT_FILE}")"

cat << RPT_EOF > "${REPORT_FILE}"
# 上游代码同步审计报告

- **执行日期**: ${TODAY}
- **上游 Remote**: ${REMOTE}
- **上游 URL**: \`${REMOTE_URL}\`
- **目标 Ref**: ${TARGET_REF}
- **前置 Commit**: \`${BEFORE_HEAD}\`
- **上游 Commit**: \`${UPSTREAM_SHA}\`
- **合并后 Commit**: \`${AFTER_HEAD}\`
- **安全备份 Ref**: \`${BACKUP_REF}\`
- **构建与测试验证**: ${BUILD_SUMMARY}

## 引入的上游提交

\`\`\`text
$(git log --oneline "${BEFORE_HEAD}..${AFTER_HEAD}" || echo "No new commits")
\`\`\`

## 恢复命令

若需回滚至本次同步前的完全一致状态，执行：

\`\`\`bash
git reset --hard ${BACKUP_REF}
\`\`\`
RPT_EOF

echo "✓ Upstream sync completed successfully."
echo "Audit report written to: ${REPORT_FILE}"
