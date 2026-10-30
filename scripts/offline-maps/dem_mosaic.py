"""Decode Mapterhorn Terrarium WebP tiles (one zoom) from a local PMTiles into one EPSG:3857 GeoTIFF of elevations.
usage: dem_mosaic.py <pmtiles-cli> <archive.pmtiles> <zoom> <west,south,east,north> <out.tif>"""
import math, subprocess, sys, tempfile, os
import numpy as np
from osgeo import gdal

cli, archive, z, bbox, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
w, s, e, n = map(float, bbox.split(','))
def tx(lon): return int((lon + 180) / 360 * 2**z)
def ty(lat):
    r = math.radians(lat); return int((1 - math.log(math.tan(r) + 1 / math.cos(r)) / math.pi) / 2 * 2**z)
x0, x1, y0, y1 = tx(w), tx(e), ty(n), ty(s)
T = 512
arr = np.full(((y1 - y0 + 1) * T, (x1 - x0 + 1) * T), np.nan, dtype=np.float32)
tmp = tempfile.mkdtemp()
missing = 0
for x in range(x0, x1 + 1):
    for y in range(y0, y1 + 1):
        data = subprocess.run([cli, 'tile', archive, str(z), str(x), str(y)], capture_output=True).stdout
        if not data:
            missing += 1; continue
        p = os.path.join(tmp, 't.webp'); open(p, 'wb').write(data)
        rgb = gdal.Open(p).ReadAsArray().astype(np.float32)
        arr[(y - y0) * T:(y - y0 + 1) * T, (x - x0) * T:(x - x0 + 1) * T] = rgb[0] * 256 + rgb[1] + rgb[2] / 256 - 32768
size = 2 * math.pi * 6378137 / 2**z
ox, oy = -math.pi * 6378137 + x0 * size, math.pi * 6378137 - y0 * size
ds = gdal.GetDriverByName('GTiff').Create(out, arr.shape[1], arr.shape[0], 1, gdal.GDT_Float32, ['COMPRESS=DEFLATE', 'TILED=YES', 'BIGTIFF=YES'])
ds.SetGeoTransform((ox, size / T, 0, oy, 0, -size / T))
ds.SetProjection('EPSG:3857')
b = ds.GetRasterBand(1); b.SetNoDataValue(float('nan')); b.WriteArray(arr)
ds = None
print(f'tiles x {x0}-{x1} y {y0}-{y1}, missing {missing}, shape {arr.shape}, min {np.nanmin(arr):.1f} max {np.nanmax(arr):.1f}')
