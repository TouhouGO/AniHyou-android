#!/usr/bin/env bash
set -euo pipefail

root_dir="$(git rev-parse --show-toplevel)"
cd "${root_dir}"

failed=0
while IFS= read -r -d '' tracked_path; do
  case "${tracked_path}" in
    docs/audit/*|docs/superpowers/*|.codex/*|.claude/*|.cursor/*|AGENTS.md|CLAUDE.md|*/AGENTS.md|*/CLAUDE.md)
      echo "Disallowed tracked path: ${tracked_path}" >&2
      failed=1
      ;;
    .idea/*)
      if [[ "${tracked_path}" != ".idea/icon.svg" ]]; then
        echo "Disallowed tracked path: ${tracked_path}" >&2
        failed=1
      fi
      ;;
  esac
done < <(git ls-files -z)

# Match absolute user/machine paths, while ignoring package names such as
# `feature/home/...` that are legitimate repository content.
private_path_pattern='(^|[^[:alnum:]_])/(Users|home)/[A-Za-z0-9._-]+/|(^|[^[:alnum:]_])/var/folders/[A-Za-z0-9._-]+/'
if git grep --cached -nI -E "${private_path_pattern}" -- . >&2; then
  echo "Tracked content contains a machine-local absolute path." >&2
  failed=1
fi

if (( failed != 0 )); then
  exit 1
fi
echo "Repository hygiene check passed."
