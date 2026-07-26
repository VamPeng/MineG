#!/usr/bin/env bash
set -euo pipefail

base_ref="${OPENAPI_BASE_REF:-origin/main}"
base_path="Service/api/openapi.yaml"
if ! git cat-file -e "$base_ref:$base_path" 2>/dev/null; then
  echo "OpenAPI base $base_ref:$base_path is unavailable; schema validation still runs."
  exit 0
fi

base_file="$(mktemp)"
git show "$base_ref:$base_path" >"$base_file"
go run github.com/oasdiff/oasdiff@v1.11.7 breaking "$base_file" api/openapi.yaml
