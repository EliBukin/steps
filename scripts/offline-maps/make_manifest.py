"""Write maps.json, the list of offline map packs the app reads (see README.md).

usage: make_manifest.py <region-id> <version> <outline.poly> <vector.pmtiles> <dem.pmtiles> <out maps.json> [min-app-version-code]

Run with GDAL's Python (osgeo). The area is the extract's outline, widened by about 2 km and
simplified, so the app knows where the pack has data. Files are listed by name only: the app finds
them next to maps.json.
"""
import hashlib, json, os, sys
from osgeo import ogr

region, version, poly, vector, dem, out = sys.argv[1:7]
min_app = int(sys.argv[7]) if len(sys.argv) > 7 else 1


def read_poly(path):
    """Osmosis .poly: a name line, then sections of "lon lat" lines each ending in END; '!' marks a hole."""
    lines = [l.strip() for l in open(path) if l.strip()]
    geom = ogr.Geometry(ogr.wkbMultiPolygon)
    i = 1
    while i < len(lines) and lines[i] != 'END':
        hole = lines[i].startswith('!')
        ring = ogr.Geometry(ogr.wkbLinearRing)
        i += 1
        while lines[i] != 'END':
            x, y = map(float, lines[i].split()[:2])
            ring.AddPoint_2D(x, y)
            i += 1
        i += 1
        ring.CloseRings()
        if hole:
            geom.GetGeometryRef(geom.GetGeometryCount() - 1).AddGeometry(ring)
        else:
            polygon = ogr.Geometry(ogr.wkbPolygon)
            polygon.AddGeometry(ring)
            geom.AddGeometry(polygon)
    return geom


area = read_poly(poly).Buffer(0.02).SimplifyPreserveTopology(0.01)
rings = []
for g in ([area] if area.GetGeometryType() == ogr.wkbPolygon else [area.GetGeometryRef(k) for k in range(area.GetGeometryCount())]):
    for r in range(g.GetGeometryCount()):
        ring = g.GetGeometryRef(r)
        rings.append([[round(ring.GetX(p), 5), round(ring.GetY(p), 5)] for p in range(ring.GetPointCount())])


def entry(role, path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    return {'role': role, 'name': os.path.basename(path), 'bytes': os.path.getsize(path), 'sha256': digest.hexdigest()}


manifest = {
    'schema': 1,
    'regions': [{
        'id': region,
        'version': version,
        'minAppVersionCode': min_app,
        'area': rings,
        'files': [entry('vector', vector), entry('dem', dem)],
    }],
}
json.dump(manifest, open(out, 'w'), indent=1)
print(f'{out}: {region} {version}, {sum(len(r) for r in rings)} outline points, '
      f'{sum(f["bytes"] for f in manifest["regions"][0]["files"]) / 1e6:.1f} MB')
