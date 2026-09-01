#!/usr/bin/env bash
# Stage 0A dev infra HEALTH
BASE="$(cd "$(dirname "$0")/.." && pwd)"
LAN_IP="$(hostname -I | tr ' ' '\n' | grep -E '^(192|10|172)\.' | head -1)"
echo "== Go Backend Signaling :8080 =="
curl -s -o /dev/null -w "  http %{http_code}\n" "http://localhost:8080/api/sdp" || echo "  DOWN"
echo "LAN Go Backend SDP Endpoint: http://$LAN_IP:8080/api/sdp"
