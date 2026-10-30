#!/usr/bin/env bash
# Assembles the GitHub Pages site (https://elibukin.github.io/steps/) into OUT_DIR:
#   index.html          the app's web page          (docs/site/index.html)
#   privacy-policy.html the privacy policy          (docs/privacy-policy.html)
#   icon-192.png        the page's icon             (docs/site/icon-192.png)
#   screenshots/*.webp  the screenshots the page shows (docs/screenshots/)
#   .nojekyll
# OUT_DIR is a gh-pages checkout or an empty folder for a local preview. See docs/RELEASING.md.
#
# Usage: scripts/build-site.sh OUT_DIR
set -euo pipefail

out=${1:?usage: scripts/build-site.sh OUT_DIR}
root=$(cd "$(dirname "$0")/.." && pwd)
site=$root/docs/site

# The page must stay self-contained: nothing it loads may come from another site.
if grep -Eo '(src|srcset)="[^"]*"|<link [^>]*href="[^"]*"' "$site/index.html" | grep -Eq '"(https?:)?//'; then
  echo "docs/site/index.html loads something from another site" >&2
  exit 1
fi

mkdir -p "$out/screenshots"
cp "$site/index.html" "$out/index.html"
cp "$root/docs/privacy-policy.html" "$out/privacy-policy.html"
cp "$site/icon-192.png" "$out/icon-192.png"
: > "$out/.nojekyll"

# Only the screenshots the page uses; drop ones it no longer uses.
used=$(grep -o 'src="screenshots/[^"]*"' "$site/index.html" | sed 's/^src="screenshots\///; s/"$//' | sort -u)
find "$out/screenshots" -type f -name '*.webp' | while read -r f; do
  grep -qxF "$(basename "$f")" <<<"$used" || rm "$f"
done
for name in $used; do
  cp "$root/docs/screenshots/$name" "$out/screenshots/$name"
done

echo "Site assembled in $out:"
(cd "$out" && find . -type f ! -path './.git/*' | sort)
