#!/usr/bin/env bash
# 독립 풀 데모를 한 번에 띄운다: 컨테이너 기동 → 헬스 대기 → (필요 시) 시드 → 합성 스트림.
# 로컬 데모 전용. 운영 배포는 bugi-server-infra가 담당한다.
set -euo pipefail
cd "$(dirname "$0")/.."

# 로컬 데모용 기본 비밀값. 이미 값이 있으면 건드리지 않는다(멱등).
KEY="${INGEST_API_KEY:-local-demo-ingest-key}"
JWT="${JWT_SECRET:-local-demo-jwt-secret-change-me-0123456789abcdef}"

ensure_kv() { # file key value — key 라인이 없을 때만 추가
  local file="$1" key="$2" value="$3"
  touch "$file"
  grep -q "^${key}=" "$file" || printf '%s=%s\n' "$key" "$value" >> "$file"
}
ensure_kv services/backend/.env   JWT_SECRET      "$JWT"
ensure_kv services/backend/.env   INGEST_API_KEY  "$KEY"
ensure_kv services/simulator/.env INGEST_API_KEY  "$KEY"

echo "[1/4] 컨테이너 빌드·기동..."
docker compose up --build -d

echo "[2/4] backend 헬스 대기..."
up=false
for _ in $(seq 1 40); do
  if curl -sf http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then up=true; break; fi
  sleep 3
done
[ "$up" = true ] || { echo "backend가 기동하지 않았습니다 — 'docker compose logs backend' 확인"; exit 1; }

echo "[3/4] 데모 데이터 확인·적재..."
users=$(docker compose exec -T postgres psql -U sensor_monitor -d sensor_monitor -tAc "SELECT count(*) FROM users" 2>/dev/null || echo 0)
if [ "${users:-0}" = "0" ]; then
  docker compose exec -T postgres psql -U sensor_monitor -d sensor_monitor < services/simulator/seed.sql
else
  echo "  시드 생략 (users ${users}건 이미 존재)"
fi

echo "[4/4] 합성 스트림 기동..."
# live 프로파일 서비스는 앞선 `up --build`가 빌드하지 않으므로 여기서 명시적으로 빌드한다.
docker compose --profile live up -d --build simulator-live

cat <<'EOF'

────────────────────────────────────────────────
데모 준비 완료 →  http://localhost:8080
  로그인:  SYSTEM / admin1234!   (전체 공장·콘솔)
           ENG-VIEW / view1234!  (샘플 뷰어)
  부팅 ~30초 후 첫 알람이 대시보드에 뜹니다.
정리:  make demo-down
────────────────────────────────────────────────
EOF
