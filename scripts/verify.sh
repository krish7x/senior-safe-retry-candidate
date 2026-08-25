#!/usr/bin/env bash
set -euo pipefail

assignment_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "Backend baseline"
(
  cd "$assignment_root/backend"
  mvn --batch-mode --no-transfer-progress clean verify
)

echo "Frontend baseline"
(
  cd "$assignment_root/frontend"
  npm ci
  npm test
  npm run build
)

echo "Compose configuration"
if [[ -f "$assignment_root/.env" ]]; then
  docker compose --project-directory "$assignment_root" config --quiet
else
  POSTGRES_PASSWORD=verification-only-not-a-real-secret \
    docker compose --project-directory "$assignment_root" config --quiet
fi

echo "Starter verification completed"
