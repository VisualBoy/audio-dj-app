#!/usr/bin/env bash
# Stage 0A dev infra UP — reproducible, PID-tracked.
set -euo pipefail
BASE="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$BASE/.run" "$BASE/logs"
LAN_IP="$(hostname -I | tr ' ' '\n' | grep -E '^(192|10|172)\.' | head -1)"

echo "Dev environment ready for custom Go backend on port 8080."
echo "Go Backend Signaling: http://$LAN_IP:8080/api/sdp"
echo "Web App Master: http://localhost:5173 (laptop) / http://$LAN_IP:5173 (phone)"
