#!/usr/bin/env bash
set -euo pipefail

# ===== Config =====
BASE_URL="${BASE_URL:-http://localhost:8080}"   # override: BASE_URL=http://localhost:9090 ./rtb_geo_lab.sh
CAMPAIGN="${CAMPAIGN:-nike}"
VIZ1_OUT="${VIZ1_OUT:-viz-${CAMPAIGN}.svg}"
VIZ2_OUT="${VIZ2_OUT:-viz-${CAMPAIGN}-550m.svg}"

# Pretty print JSON if jq is present
pp_json() {
  if command -v jq >/dev/null 2>&1; then jq .; else cat; fi
}

# Curl helper that shows method + path and errors clearly
req() {
  local method="$1"; shift
  local url="$1"; shift
  echo
  echo "────────────────────────────────────────────────────────"
  echo "→ ${method} ${url}"
  echo "────────────────────────────────────────────────────────"
  if [[ "$method" == "GET" ]]; then
    curl -sS -f "${url}" "$@" | pp_json
  else
    curl -sS -f -X "${method}" "${url}" "$@" | pp_json
  fi
  echo
}

# Basic wait for app to respond on /
wait_for_app() {
  echo "Waiting for app at ${BASE_URL} ..."
  for i in {1..40}; do
    if curl -sSf "${BASE_URL}/" >/dev/null 2>&1; then
      echo "App is up ✔"
      return
    fi
    sleep 0.5
  done
  echo "App did not respond at ${BASE_URL} in time."
  exit 1
}

# Try to open files on macOS; otherwise just print path
open_file() {
  local file="$1"
  if command -v open >/dev/null 2>&1; then
    echo "Opening ${file} …"
    open "${file}" || true
  else
    echo "Saved: $(realpath "${file}")"
  fi
}

# ===== Run =====
wait_for_app

echo
echo "Step 1) Set tile size ~1km (dLat=0.01, dLon=0.01)"
req POST "${BASE_URL}/api/geo/tiler/rect?dLat=0.01&dLon=0.01"

echo "Step 2) Load demo polygon for campaign '${CAMPAIGN}' (coveragePercent=40)"
req POST "${BASE_URL}/api/geo/campaign/${CAMPAIGN}/load-demo?coveragePercent=40"

echo "Step 3) Visualize → saving SVG to ./${VIZ1_OUT}"
curl -sS -f "${BASE_URL}/api/geo/viz/${CAMPAIGN}" -o "${VIZ1_OUT}"
open_file "${VIZ1_OUT}"

echo
echo "Step 4) Map a point to a tile (lat=3.915, lon=11.548)"
req GET "${BASE_URL}/api/geo/tile-of?lat=3.915&lon=11.548"

echo "Step 5) Membership check for the same point (simulate serve-time)"
req GET "${BASE_URL}/api/geo/membership/${CAMPAIGN}?lat=3.915&lon=11.548"

echo
echo "Step 6) Tighten tiles to ~550m (dLat=0.005, dLon=0.005)"
req POST "${BASE_URL}/api/geo/tiler/rect?dLat=0.005&dLon=0.005"

echo "Recompute allowed tiles for '${CAMPAIGN}' (coveragePercent=40)"
req POST "${BASE_URL}/api/geo/campaign/${CAMPAIGN}/load-demo?coveragePercent=40"

echo "Re-visualize → saving SVG to ./${VIZ2_OUT}"
curl -sS -f "${BASE_URL}/api/geo/viz/${CAMPAIGN}" -o "${VIZ2_OUT}"
open_file "${VIZ2_OUT}"

echo
echo "Bonus) Denser sampling & bigger canvas (not saved, just preview JSON headers)"
DENSE_URL="${BASE_URL}/api/geo/viz/${CAMPAIGN}?randomPoints=1000&width=600&height=600"
echo "GET ${DENSE_URL}"
echo "Tip: Save it yourself if you want → curl \"${DENSE_URL}\" -o viz-dense.svg"

echo
echo "✅ Done. SVGs saved:"
echo " - ${VIZ1_OUT}  (≈1 km tiles)"
echo " - ${VIZ2_OUT}  (≈550 m tiles)"
