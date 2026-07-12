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

ga="${GA_MEASUREMENT_ID:-}"
if [ -n "$ga" ] && [ -f "$html" ]; then
  snippet="<script async src=\"https://www.googletagmanager.com/gtag/js?id=${ga}\"></script><script>window.dataLayer=window.dataLayer||[];function gtag(){dataLayer.push(arguments);}gtag('js',new Date());gtag('consent','default',{analytics_storage:'denied'});gtag('config','${ga}',{send_page_view:false});</script>"
  snippet_esc=$(printf '%s' "$snippet" | sed 's/[&|\\]/\\&/g')
  sed -i "s|</head>|${snippet_esc}</head>|" "$html"
fi
