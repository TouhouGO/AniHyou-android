#!/usr/bin/env bash
set -euo pipefail

checker="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/check-repository-hygiene.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "${fixture_dir}"' EXIT
git -C "${fixture_dir}" init -b main >/dev/null
git -C "${fixture_dir}" config user.name Fixture
git -C "${fixture_dir}" config user.email fixture@example.invalid

printf 'public\n' > "${fixture_dir}/README.md"
git -C "${fixture_dir}" add README.md
(cd "${fixture_dir}" && bash "${checker}") >/dev/null

mkdir -p "${fixture_dir}/docs/audit"
printf 'internal\n' > "${fixture_dir}/docs/audit/report.md"
git -C "${fixture_dir}" add -f docs/audit/report.md
if (cd "${fixture_dir}" && bash "${checker}") >/dev/null 2>&1; then
  echo "FAIL: tracked audit file was accepted" >&2
  exit 1
fi
git -C "${fixture_dir}" rm -q --cached docs/audit/report.md

printf 'private: /%s/example/project\n' Users > "${fixture_dir}/README.md"
git -C "${fixture_dir}" add README.md
if (cd "${fixture_dir}" && bash "${checker}") >/dev/null 2>&1; then
  echo "FAIL: machine-local path was accepted" >&2
  exit 1
fi
printf 'public\n' > "${fixture_dir}/README.md"
git -C "${fixture_dir}" add README.md
(cd "${fixture_dir}" && bash "${checker}") >/dev/null
echo "Repository hygiene tests passed."
