#!/usr/bin/env bash
set -euo pipefail
upstream_url=${UPSTREAM_URL:-https://github.com/axiel7/AniHyou-android.git}
upstream_branch=${UPSTREAM_BRANCH:-master}
identity_name='TouhouGO'
identity_email='93237754+TouhouGO@users.noreply.github.com'
if [ "$upstream_branch" != master ]; then
  echo 'Only the official master branch is supported.' >&2; exit 1
fi
if [ "$upstream_url" != https://github.com/axiel7/AniHyou-android.git ]; then
  if [[ "$upstream_url" != /* ]] || [ ! -d "$upstream_url" ]; then
    echo 'Only the official upstream URL or an absolute local test repository is supported.' >&2; exit 1
  fi
fi
report_dir=${UPSTREAM_REPORT_DIR:?UPSTREAM_REPORT_DIR is required}
output=${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}
summary=${GITHUB_STEP_SUMMARY:?GITHUB_STEP_SUMMARY is required}
mkdir -p "$report_dir"
[ -z "$(git status --porcelain --untracked-files=all)" ] || { echo 'Working tree must be clean.' >&2; exit 1; }
git fetch --no-tags origin refs/heads/master
base_sha=$(git rev-parse FETCH_HEAD)
git fetch --no-tags "$upstream_url" "refs/heads/$upstream_branch"
upstream_sha=$(git rev-parse FETCH_HEAD)
if ! EXPECTED_NAME="$identity_name" EXPECTED_EMAIL="$identity_email" python3 - "$base_sha" "$upstream_sha" <<'PY'
import os
import subprocess
import sys

data = subprocess.check_output(['git', 'log', '-z', '--format=%an%x00%ae%x00%cn%x00%ce',
                                sys.argv[1], '--not', sys.argv[2]])
fields = data.split(b'\0')
if fields[-1] == b'':
    fields.pop()
if len(fields) % 4:
    raise SystemExit('Unable to verify fork commit identities.')
name = os.environ['EXPECTED_NAME'].encode()
email = os.environ['EXPECTED_EMAIL'].encode()
bad_author = bad_committer = bad_commits = 0
for i in range(0, len(fields), 4):
    author = fields[i:i + 2] != [name, email]
    committer = fields[i + 2:i + 4] != [name, email]
    bad_author += author
    bad_committer += committer
    bad_commits += author or committer
if bad_commits:
    print(f'Fork-only identity check failed: {bad_commits} commits; '
          f'{bad_author} author mismatches; {bad_committer} committer mismatches.',
          file=sys.stderr)
    raise SystemExit(1)
PY
then
  exit 1
fi
branch="claude/upstream-sync-${upstream_sha:0:12}-${base_sha:0:12}-${GITHUB_RUN_ID:-local}-${GITHUB_RUN_ATTEMPT:-1}"
printf '# Official upstream integration\n\nFork master: `%s`\n\nOfficial master: `%s`\n\nReview branch: `%s`\n\n' "$base_sha" "$upstream_sha" "$branch" > "$report_dir/report.md"
cat "$report_dir/report.md" >> "$summary"
printf 'base_sha=%s\nupstream_sha=%s\nbranch=%s\n' "$base_sha" "$upstream_sha" "$branch" >> "$output"
if git merge-base --is-ancestor "$upstream_sha" "$base_sha"; then
  echo 'Official master is already included; no branch or PR needed.' | tee -a "$report_dir/report.md" >> "$summary"
  echo 'changed=false' >> "$output"
  exit 0
fi
git switch --create "$branch" "$base_sha"
if ! GIT_AUTHOR_NAME="$identity_name" GIT_AUTHOR_EMAIL="$identity_email" \
  GIT_COMMITTER_NAME="$identity_name" GIT_COMMITTER_EMAIL="$identity_email" \
  git -c core.hooksPath=/dev/null merge --no-ff --no-edit --no-stat -m '合并官方上游 master，提交人工审阅' "$upstream_sha" > "$report_dir/merge.log" 2>&1; then
  {
    printf 'Merge failed. No review branch was pushed; the checkout contains conflicts for inspection.\n\nUnmerged files:\n\n'
    git -c core.quotePath=true diff --name-only --diff-filter=U
  } | tee -a "$report_dir/report.md" >> "$summary"
  echo '::error::Official upstream merge failed. Review the target SHAs, unmerged files and merge.log artifact; resolve conflicts manually.' >&2
  exit 1
fi
if [ "$(git show -s --format='%an|%ae|%cn|%ce' HEAD)" != "$identity_name|$identity_email|$identity_name|$identity_email" ]; then
  echo 'Merge commit identity verification failed; no review branch was published.' >&2; exit 1
fi
printf 'Merge commit: `%s`\n' "$(git rev-parse HEAD)" | tee -a "$report_dir/report.md" >> "$summary"
echo 'changed=true' >> "$output"
