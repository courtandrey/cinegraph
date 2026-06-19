#!/bin/sh
set -e

cat > /usr/share/nginx/html/config.js <<EOF
window.__APP_CONFIG__ = { gaMeasurementId: "${GA_MEASUREMENT_ID:-}" };
EOF
