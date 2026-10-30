# Offline map packs

The app's **Settings → Offline maps** downloads a region's map pack. Inside the pack's area the map
then works with no internet (see `MapPackStyle` and `rememberMapBackground` in `ui/map/`).

## What a pack is

There are two [PMTiles](https://docs.protomaps.com/pmtiles/) files per region and version (the
version is the OpenStreetMap extract's date):

| File | What's in it | Israel, 2026-10 |
|---|---|---|
| `<region>-<version>-vector.pmtiles` | OpenMapTiles layers z0-14 (Planetiler), plus `outdoor_transportation` and `outdoor_waterway` (tracks z10, paths z11, tertiary roads z9, unclassified z10, streams and wadis z10, rivers z9, up to z13), `outdoor_poi` (camp sites with OpenMapTiles' poi attributes, z11-13), `trail` (marked hiking routes with `color` from `osmc:symbol`, z11-14) and `contour` (`ele`, `index` = every 100 m; 50 m from z12, 10 m at z13) | 119.6 MB |
| `<region>-<version>-dem.pmtiles` | Mapterhorn terrain (Terrarium WebP, 512 px), z0-11 | 31.4 MB |

`maps.json` lists them for the app:

```json
{"schema": 1, "regions": [{"id": "israel", "version": "2026-10-29", "minAppVersionCode": 1,
  "area": [[[lon, lat], ...]],
  "files": [{"role": "vector", "name": "...", "bytes": 0, "sha256": "..."},
            {"role": "dem", "name": "...", "bytes": 0, "sha256": "..."}]}]}
```

- `area` is the extract's outline, about 2 km wider and simplified. The app uses the pack while the
  map's center is inside it.
- File names resolve against `maps.json`'s own address, so the files sit next to it.
- `schema` is the pack layout the app's style reads (`MapPackManifest.SCHEMA`). Change the layer
  names or attributes, and you have to raise it and ship an app that reads the new schema.
- `minAppVersionCode` holds a pack back from older apps.

**Zooms:** from z14 MapLibre reads the file's z14 tiles, and a layer that isn't in them disappears.
So a layer the app reads from the pack's own source goes to z14 (`trail`, in `trails.yml`). The app
reads `contour` and `outdoor_poi` from a second source on the same file that stops at z13
(`MapPackStyle`), so MapLibre stretches their z13 tiles instead: contours to z14 would be about 20 MB
more.

## Building

```bash
JAVA=<jdk 21>/bin/java PLANETILER_JAR=<planetiler 0.10.x jar> PMTILES=<go-pmtiles CLI> \
GEO_BIN=<env with gdal + tippecanoe>/bin \
  scripts/offline-maps/build-pack.sh <work-dir>
```

- **Tools:**
  - Planetiler, from github.com/onthegomap/planetiler releases.
  - go-pmtiles, from github.com/protomaps/go-pmtiles releases.
  - GDAL with its Python bindings, and tippecanoe. Both install in user space, e.g. `micromamba create -p <env> -c conda-forge gdal tippecanoe python`.
- **Defaults** are for Israel. For another region, set `REGION`, `GEOFABRIK` (Geofabrik's path) and `BBOX`.
- **Time:** about 6 minutes on an 8-core PC. The first run also downloads about 720 MB of Planetiler sources (Natural Earth, water polygons) into `<work-dir>/sources`.
- **Output:** the two files and `maps.json` end up in `<work-dir>/release/`. Keep them out of the repo.

## Publishing (the project master, with the owner's OK)

The app reads `https://github.com/EliBukin/steps/releases/download/maps/maps.json`
(`MAPS_MANIFEST_URL` in `app/build.gradle.kts`).

1. Create the repo's `maps` release once, as a **pre-release**, so it never shows as the app's latest release.
2. Upload the two new pack files.
3. Replace `maps.json` with the new one.
4. Delete the old pack files after a while: an app that read the old `maps.json` just before the swap may still be downloading them.

The release is public, and anyone may download it. The packs are published under the Open Database
License (they are derived from OpenStreetMap), with the credits in the main README.

## Trying a pack on the emulator (debug builds)

```bash
cd <work-dir>/release && python3 -m http.server 8000
adb -s emulator-5554 shell "run-as bukin.stepsgpsroute.debug sh -c 'echo http://10.0.2.2:8000/maps.json > files/debug-maps-manifest-url.txt'"
```

Debug builds read that file instead of the real address, and allow plain http to 10.0.2.2 only
(`app/src/debug`). Delete the file to go back to the real address.
