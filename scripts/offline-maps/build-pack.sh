#!/usr/bin/env bash
# Builds one offline map pack (vector + DEM) and its maps.json. See README.md in this folder.
#
#   scripts/offline-maps/build-pack.sh <work-dir>
#
# Region settings (defaults: Israel):
#   REGION=israel GEOFABRIK=asia/israel-and-palestine BBOX=34.2,29.45,35.95,33.4
# Tools (all user space, nothing installed system-wide):
#   JAVA (21+), PLANETILER_JAR (Planetiler 0.10.x), PMTILES (go-pmtiles CLI),
#   GEO_BIN (a bin folder with gdal_contour, gdal_translate, gdal_edit, ogr2ogr, tile-join and a
#   python with GDAL's osgeo bindings - e.g. a micromamba env with gdal and tippecanoe).
# The work dir needs ~5 GB; the two pack files and maps.json end up in <work-dir>/release/.
set -euo pipefail

WORK=${1:?usage: build-pack.sh <work-dir>}
HERE=$(cd "$(dirname "$0")" && pwd)
REGION=${REGION:-israel}
GEOFABRIK=${GEOFABRIK:-asia/israel-and-palestine}
BBOX=${BBOX:-34.2,29.45,35.95,33.4}
JAVA=${JAVA:-java}
: "${PLANETILER_JAR:?set PLANETILER_JAR}" "${PMTILES:?set PMTILES}" "${GEO_BIN:?set GEO_BIN}"
export PROJ_DATA=${PROJ_DATA:-$GEO_BIN/../share/proj}

mkdir -p "$WORK/work" "$WORK/sources" "$WORK/release"
cd "$WORK"
step() { echo "== $(date +%T) $*"; }

step "OpenStreetMap extract and outline from Geofabrik"
curl -fsSLR -o work/region.osm.pbf "https://download.geofabrik.de/$GEOFABRIK-latest.osm.pbf"
curl -fsSL -o work/region.poly "https://download.geofabrik.de/$GEOFABRIK.poly"
# The extract's date (curl -R keeps the server's file time) is the pack's version.
VERSION=$(date -u -r work/region.osm.pbf +%Y-%m-%d)
NAME=$REGION-$VERSION

step "Base map: OpenMapTiles layers, z0-14 (Planetiler)"
# No made-up Latin names (Planetiler's transliteration of Hebrew and Arabic reads "mzph rmwn");
# a place without a real English or Latin name shows its own name, as on the online map.
"$JAVA" -Xmx8g -jar "$PLANETILER_JAR" --osm-path=work/region.osm.pbf --download --download-dir=sources \
  --transliterate=false --output=work/omt.pmtiles --force

step "Marked hiking trails: OSM route relations, trail-mark color from osmc:symbol"
cp "$GEO_BIN/../share/gdal/osmconf.ini" work/osmconf.ini
"$GEO_BIN/python" -I - work/osmconf.ini <<'EOF'
import sys
path = sys.argv[1]
s = open(path).read()
i = s.index('[multilinestrings]')
j = s.index('attributes=', i)
k = s.index('\n', j)
open(path, 'w').write(s[:j] + 'attributes=name,name:en,name:he,type,route,network,ref,osmc:symbol,colour' + s[k:])
EOF
OSM_CONFIG_FILE=work/osmconf.ini "$GEO_BIN/ogr2ogr" -f GPKG -overwrite work/routes.gpkg work/region.osm.pbf multilinestrings \
  -where "route IN ('hiking','foot','walking')" -nln trails
"$GEO_BIN/ogr2ogr" -f GPKG -overwrite work/trails.gpkg work/routes.gpkg -dialect SQLite -nln trails -sql "
  SELECT geom,
    CASE WHEN lower(substr(osmc_symbol, 1, instr(osmc_symbol || ':', ':') - 1))
           IN ('red','blue','green','black','orange','yellow','purple','white','brown')
         THEN lower(substr(osmc_symbol, 1, instr(osmc_symbol || ':', ':') - 1))
         ELSE coalesce(lower(colour), 'other') END AS color,
    network, ref, name, name_en, name_he
  FROM trails"

step "Outdoor layers: low-zoom tracks, paths, minor roads, wadis, and the trails (Planetiler YAML)"
"$JAVA" -Xmx8g -jar "$PLANETILER_JAR" generate-custom --schema="$HERE/outdoor.yml" \
  --osm_path=work/region.osm.pbf --trails_path=work/trails.gpkg --output=work/outdoor.pmtiles --force

step "Heights: Mapterhorn terrain tiles z0-12 for the area"
"$PMTILES" extract https://download.mapterhorn.com/planet.pmtiles work/dem-z12.pmtiles --bbox="$BBOX" --maxzoom=12
"$PMTILES" extract work/dem-z12.pmtiles "release/$NAME-dem.pmtiles" --maxzoom=11

step "Contour lines: 10 m from the z12 heights averaged to ~32 m, inside the extract's outline"
"$GEO_BIN/python" -I "$HERE/dem_mosaic.py" "$PMTILES" work/dem-z12.pmtiles 12 "$BBOX" work/dem-z12.tif
"$GEO_BIN/gdal_edit" -a_srs EPSG:3857 work/dem-z12.tif
"$GEO_BIN/gdal_translate" -q -r average -outsize 50% 50% -co COMPRESS=DEFLATE -co TILED=YES work/dem-z12.tif work/dem-32m.tif
rm -f work/contours-3857.gpkg work/contours.gpkg
"$GEO_BIN/gdal_contour" -q -a ele -i 10 -f GPKG -nln contour work/dem-32m.tif work/contours-3857.gpkg
"$GEO_BIN/python" -I - work/region.poly work/outline.geojson <<'EOF'
import json, sys
rings, ring = [], None
for line in [l.strip() for l in open(sys.argv[1]) if l.strip()][1:]:
    if line == 'END':
        if ring is not None:
            rings.append(ring)
        ring = None
    elif ring is None:
        ring = []
    else:
        ring.append([float(v) for v in line.split()[:2]])
json.dump({'type': 'FeatureCollection', 'features': [{'type': 'Feature', 'properties': {},
          'geometry': {'type': 'MultiPolygon', 'coordinates': [[r] for r in rings]}}]}, open(sys.argv[2], 'w'))
EOF
"$GEO_BIN/ogr2ogr" -f GPKG work/contours.gpkg work/contours-3857.gpkg -t_srs EPSG:4326 -clipdst work/outline.geojson \
  -nln contour -where "ele <> 0" -nlt MULTILINESTRING
"$JAVA" -Xmx8g -jar "$PLANETILER_JAR" generate-custom --schema="$HERE/contours.yml" \
  --contours_path=work/contours.gpkg --output=work/contours.pmtiles --force

step "One vector file: base map + outdoor layers + contours"
"$GEO_BIN/tile-join" -q -f --no-tile-size-limit -o "release/$NAME-vector.pmtiles" \
  work/omt.pmtiles work/outdoor.pmtiles work/contours.pmtiles

step "maps.json"
"$GEO_BIN/python" -I "$HERE/make_manifest.py" "$REGION" "$VERSION" work/region.poly \
  "release/$NAME-vector.pmtiles" "release/$NAME-dem.pmtiles" release/maps.json
ls -l release/
