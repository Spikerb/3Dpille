"""Synthetic check: a cone pile on sloping, noisy ground with a hole in the DSM."""
import math, sys
from pathlib import Path
import numpy as np, rasterio
from rasterio.transform import from_origin
from pyproj import Transformer
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from stockpile_api.volume import compute_volume

def make(path, R=10.0, H=5.0, cell=0.05, slope=(0.03, -0.02), noise=0.03, hole=True):
    x0, y0 = 620000.0, 5450000.0          # UTM 33N, near Brno
    n = int(60 / cell)
    tr = from_origin(x0 - 30, y0 + 30, cell, cell)
    rows, cols = np.mgrid[0:n, 0:n]
    xs = x0 - 30 + (cols + .5) * cell; ys = y0 + 30 - (rows + .5) * cell
    dx, dy = xs - x0, ys - y0
    ground = 250 + slope[0] * dx + slope[1] * dy
    r = np.hypot(dx, dy)
    pile = np.clip(H * (1 - r / R), 0, None)
    rng = np.random.default_rng(1)
    z = (ground + pile + rng.normal(0, noise, ground.shape)).astype("float32")
    z[(r > 26)] = -9999                    # area not reconstructed
    if hole: z[(r < 1.5)] = -9999          # top of pile not seen
    with rasterio.open(path, "w", driver="GTiff", width=n, height=n, count=1, dtype="float32",
                       crs="EPSG:32633", transform=tr, nodata=-9999) as ds:
        ds.write(z, 1)
    inv = Transformer.from_crs("EPSG:32633", "EPSG:4326", always_xy=True)
    cams = [inv.transform(x0 + 14 * math.cos(a), y0 + 14 * math.sin(a)) for a in np.linspace(0, 2*math.pi, 60, endpoint=False)]
    return cams, math.pi * R * R * H / 3

if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    cams, truth = make(out / "dsm.tif")
    res = compute_volume(out / "dsm.tif", cams, density_t_m3=1.6, preview_path=out / "preview.png", title="Test cone")
    d = res.to_dict()
    err = (res.volume_m3 - truth) / truth * 100
    print(d); print(f"truth {truth:.1f} m3, measured {res.volume_m3:.1f} m3, error {err:+.2f} %")
    assert abs(err) < 2, err
    print("PASS")
