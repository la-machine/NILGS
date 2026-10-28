# SNIGF/NILGS - Copyright (c) 2026 FLYT.
# Author: Pythagore.
"""Generates obviously artificial "imagery" for tests (ADR-029): coloured bands, a 100 m grid
and diagonal hatching, so nobody can mistake it for a photograph. It goes through the same
import pipeline as real imagery and its manifest says synthetic, which the interface shows.

usage: synthetic-imagery.py <output.tif> <west> <south> <east> <north> <metres-per-pixel>
"""
import sys

import numpy as np
from osgeo import gdal, osr

gdal.UseExceptions()


def main(output, west, south, east, north, resolution):
    west, south, east, north, resolution = map(float, (west, south, east, north, resolution))
    # Generated in UTM 32N, the zone of the demonstration parcels, then imported like any file.
    utm = osr.SpatialReference()
    utm.ImportFromEPSG(32632)
    wgs84 = osr.SpatialReference()
    wgs84.ImportFromEPSG(4326)
    wgs84.SetAxisMappingStrategy(osr.OAMS_TRADITIONAL_GIS_ORDER)
    to_utm = osr.CoordinateTransformation(wgs84, utm)
    corners = [to_utm.TransformPoint(x, y)[:2] for x in (west, east) for y in (south, north)]
    min_e, max_e = min(c[0] for c in corners), max(c[0] for c in corners)
    min_n, max_n = min(c[1] for c in corners), max(c[1] for c in corners)
    width, height = int((max_e - min_e) / resolution), int((max_n - min_n) / resolution)

    e = min_e + (np.arange(width) + 0.5) * resolution
    n = max_n - (np.arange(height) + 0.5) * resolution
    ee, nn = np.meshgrid(e, n)
    red = (90 + 80 * ((ee // 1000) % 2)).astype(np.uint8)
    green = (110 + 70 * ((nn // 1000) % 2)).astype(np.uint8)
    blue = np.full_like(red, 120)
    grid = ((ee % 100) < resolution * 1.5) | ((nn % 100) < resolution * 1.5)
    hatch = ((ee + nn) % 250) < resolution * 2
    for band, value in ((red, 245), (green, 245), (blue, 245)):
        band[grid] = value
    for band, value in ((red, 40), (green, 40), (blue, 40)):
        band[hatch & ~grid] = value

    tif = gdal.GetDriverByName("GTiff").Create(output, width, height, 3, gdal.GDT_Byte,
                                               options=["TILED=YES", "COMPRESS=DEFLATE"])
    tif.SetGeoTransform((min_e, resolution, 0, max_n, 0, -resolution))
    tif.SetProjection(utm.ExportToWkt())
    for i, band in enumerate((red, green, blue), start=1):
        tif.GetRasterBand(i).WriteArray(band)
    tif.SetMetadataItem("SNIGF_SYNTHETIC", "true")
    tif.FlushCache()
    print(f"{output}: {width} x {height} px at {resolution} m")


if __name__ == "__main__":
    if len(sys.argv) != 7:
        sys.exit(__doc__)
    main(*sys.argv[1:])
