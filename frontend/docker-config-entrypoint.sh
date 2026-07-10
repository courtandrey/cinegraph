#!/bin/sh
set -e

html="/usr/share/nginx/html/index.html"
robots="/usr/share/nginx/html/robots.txt"

cat > /usr/share/nginx/html/config.js <<EOF
window.__APP_CONFIG__ = { gaMeasurementId: "${GA_MEASUREMENT_ID:-}", siteUrl: "${CINEGRAPH_HOST:-}" };
EOF

if [ -n "${CINEGRAPH_HOST:-}" ]; then
  host="${CINEGRAPH_HOST%/}"
  esc=$(printf '%s' "$host" | sed 's/[&|\\]/\\&/g')
  for f in "$html" "$robots"; do
    [ -f "$f" ] && sed -i "s|__CINEGRAPH_HOST__|${esc}|g" "$f"
  done
else
  for f in "$html" "$robots"; do
    [ -f "$f" ] && sed -i "/__CINEGRAPH_HOST__/d" "$f"
  done
fi
