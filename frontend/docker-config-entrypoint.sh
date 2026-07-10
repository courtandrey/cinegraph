#!/bin/sh
set -e

cat > /usr/share/nginx/html/config.js <<EOF
window.__APP_CONFIG__ = { gaMeasurementId: "${GA_MEASUREMENT_ID:-}", siteUrl: "${CINEGRAPH_HOST:-}" };
EOF

robots="/usr/share/nginx/html/robots.txt"
if [ -n "${CINEGRAPH_HOST:-}" ]; then
  host="${CINEGRAPH_HOST%/}"
  tags="<link rel=\"canonical\" href=\"${host}/\"><meta property=\"og:url\" content=\"${host}/\"><meta property=\"og:image\" content=\"${host}/og-cover.png\"><meta name=\"twitter:image\" content=\"${host}/og-cover.png\">"
  sed -i "s|</head>|${tags}</head>|" /usr/share/nginx/html/index.html
  [ -f "$robots" ] && sed -i "s|__CINEGRAPH_HOST__|${host}|g" "$robots"
elif [ -f "$robots" ]; then
  sed -i "/^Sitemap: /d" "$robots"
fi
