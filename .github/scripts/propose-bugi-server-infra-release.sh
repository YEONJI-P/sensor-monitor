#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <bugi-server-infra-checkout>" >&2
  exit 2
fi

infra_checkout=$1
: "${SOURCE_SHA:?SOURCE_SHA is required}"
: "${INFRA_REPOSITORY:?INFRA_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

if [[ ! $SOURCE_SHA =~ ^[0-9a-f]{40}$ ]]; then
  echo "SOURCE_SHA must be a lowercase 40-character Git SHA" >&2
  exit 2
fi

cd "$infra_checkout"

if [[ -n $(git status --porcelain) ]]; then
  echo "bugi-server-infra checkout must be clean before updating image pins" >&2
  exit 1
fi

python3 ops/update_sensor_monitor_pin.py \
  --repo-root . \
  --revision "$SOURCE_SHA"

if git diff --quiet; then
  echo "bugi-server-infra already pins Sensor Monitor $SOURCE_SHA"
  exit 0
fi

mapfile -t changed_paths < <(git diff --name-only)
if [[ ${#changed_paths[@]} -ne 1 || ${changed_paths[0]} != "docker-compose.yml" ]]; then
  echo "Sensor release PR must change only docker-compose.yml" >&2
  printf 'unexpected changed path: %s\n' "${changed_paths[@]}" >&2
  exit 1
fi

git diff --check

# prod release는 backend·explain 두 이미지만 같은 SHA로 교체한다.
# infra updater가 simulator나 다른 Compose 계약을 건드리면 PR을 만들지 않고 실패한다.
mapfile -t changed_lines < <(
  git diff --unified=0 -- docker-compose.yml \
    | grep -E '^[+-]' \
    | grep -Ev '^---|^\+\+\+' \
    || true
)

if [[ ${#changed_lines[@]} -ne 4 ]]; then
  echo "Sensor release must replace exactly two image pins (backend and explain)" >&2
  printf 'changed line: %s\n' "${changed_lines[@]}" >&2
  exit 1
fi

for line in "${changed_lines[@]}"; do
  if [[ ! $line =~ ^[-+][[:space:]]*image:[[:space:]]*ghcr\.io/[^/]+/sensor-monitor-(backend|explain):[0-9a-f]{40}[[:space:]]*$ ]]; then
    echo "Unexpected Sensor release diff line: $line" >&2
    exit 1
  fi
done

for service in backend explain; do
  if ! printf '%s\n' "${changed_lines[@]}" \
      | grep -Eq "^\\+[[:space:]]*image:[[:space:]]*ghcr\\.io/[^/]+/sensor-monitor-$service:$SOURCE_SHA[[:space:]]*$"; then
    echo "Sensor release is missing the $service pin for $SOURCE_SHA" >&2
    exit 1
  fi
done

branch="automation/sensor-monitor-$SOURCE_SHA"
existing_pr=$(gh pr list \
  --repo "$INFRA_REPOSITORY" \
  --state all \
  --head "$branch" \
  --json url \
  --jq '.[0].url // empty')
if [[ -n $existing_pr ]]; then
  echo "Sensor release PR already exists: $existing_pr"
  exit 0
fi

if git ls-remote --exit-code --heads origin "$branch" >/dev/null 2>&1; then
  echo "Remote branch already exists without a matching PR: $branch" >&2
  exit 1
fi

git switch -c "$branch"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add docker-compose.yml

mapfile -t staged_paths < <(git diff --cached --name-only)
if [[ ${#staged_paths[@]} -ne 1 || ${staged_paths[0]} != "docker-compose.yml" ]]; then
  echo "Sensor release commit must stage only docker-compose.yml" >&2
  exit 1
fi

git diff --cached --check
git commit -m "chore(deploy): Sensor 이미지 SHA 갱신"
git push --set-upstream origin "$branch"

short_sha=${SOURCE_SHA:0:7}
gh pr create \
  --repo "$INFRA_REPOSITORY" \
  --base main \
  --head "$branch" \
  --title "chore(deploy): Sensor $short_sha 이미지 갱신" \
  --body-file - <<EOF
Sensor Monitor main CI와 backend·explain 이미지 발행이 모두 성공한 뒤 자동으로 생성된 배포 제안입니다.

- source SHA: \`$SOURCE_SHA\`
- workflow run: $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID
- 변경 범위: \`docker-compose.yml\`의 Sensor backend·explain image pin 두 줄만
- 자동 적용: 병합 후 bugi-server-infra timer의 Sensor image-only fast-forward gate 대상

병합 전 확인:

- [ ] backend·explain GHCR 이미지가 모두 source SHA로 발행됨
- [ ] 두 image pin이 같은 40자리 SHA임
- [ ] 새 필수 env·실행 인자·network·nginx·DB 절차 변경이 이 PR에 섞이지 않음
- [ ] Flyway migration과 직전 이미지 rollback 호환성 검토

실행 인자나 다른 인프라 변경이 필요하면 별도 PR과 수동 적용 handover로 다룹니다. 이 PR은 자동 병합되지 않습니다.
EOF
