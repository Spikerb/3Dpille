"""Stockpile volume from a photogrammetry DSM.

Method
------
1. The pile boundary is the convex hull of the camera positions. During a
   walk-around capture the operator circles the pile on the ground, so the
   hull encloses the pile plus a margin of ground around its toe.
2. The base surface is a plane fitted (robustly) to DSM heights in a ring
   around that boundary - i.e. the ground the operator walked on.
3. Volume = integral of (DSM - base plane) inside the boundary. Small holes in
   the DSM (e.g. the top of a tall pile seen from below) are filled with the
   nearest valid height and reported as a coverage figure.

Net volume (fill - cut) is reported as the pile volume: random ground noise
inside the boundary roughly cancels out, whereas "fill only" would be biased
upwards.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import rasterio
from pyproj import Transformer
from rasterio.features import geometry_mask
from scipy import ndimage
from shapely.geometry import MultiPoint, Polygon


class VolumeError(RuntimeError):
    pass


@dataclass
class VolumeResult:
    volume_m3: float          # net volume above the base plane (main figure)
    fill_m3: float            # material above the base
    cut_m3: float             # depressions below the base
    footprint_m2: float       # area where the pile is > 10 cm above base
    boundary_area_m2: float   # area of the measurement boundary
    max_height_m: float       # highest point above base
    base_slope_pct: float     # slope of the fitted ground plane
    base_rmse_m: float        # how well the ground fits a plane (lower = better)
    coverage_pct: float       # % of boundary with real DSM data (rest filled)
    cell_size_m: float
    camera_count: int
    tonnes: float | None = None
    density_t_m3: float | None = None
    warnings: list[str] | None = None

    def to_dict(self) -> dict:
        return {k: (round(v, 3) if isinstance(v, float) else v) for k, v in asdict(self).items()}


def _pixel_centers(transform, rows: np.ndarray, cols: np.ndarray):
    xs = transform.c + (cols + 0.5) * transform.a + (rows + 0.5) * transform.b
    ys = transform.f + (cols + 0.5) * transform.d + (rows + 0.5) * transform.e
    return xs, ys


def _robust_plane(x: np.ndarray, y: np.ndarray, z: np.ndarray, iterations: int = 6):
    """Least-squares plane z = a*x + b*y + c with iterative outlier rejection.

    Points far *above* the plane (pile spill, vehicles, people) are rejected
    more aggressively than points below.
    """
    keep = np.ones(z.shape, dtype=bool)
    x0, y0 = x.mean(), y.mean()  # centre coordinates for numerical stability
    coef = np.array([0.0, 0.0, np.median(z)])
    for _ in range(iterations):
        if keep.sum() < 10:
            break
        A = np.column_stack([x[keep] - x0, y[keep] - y0, np.ones(keep.sum())])
        coef, *_ = np.linalg.lstsq(A, z[keep], rcond=None)
        r = z - (coef[0] * (x - x0) + coef[1] * (y - y0) + coef[2])
        mad = np.median(np.abs(r[keep] - np.median(r[keep]))) * 1.4826
        mad = max(mad, 0.02)
        new_keep = (r < 2.0 * mad) & (r > -3.0 * mad)
        if np.array_equal(new_keep, keep):
            break
        keep = new_keep
    r = z[keep] - (coef[0] * (x[keep] - x0) + coef[1] * (y[keep] - y0) + coef[2])
    rmse = float(np.sqrt(np.mean(r ** 2))) if r.size else float("nan")

    def plane(px, py):
        return coef[0] * (px - x0) + coef[1] * (py - y0) + coef[2]

    slope_pct = float(np.hypot(coef[0], coef[1]) * 100)
    return plane, rmse, slope_pct, int(keep.sum())


def load_camera_positions(shots_geojson: Path | None, exif_lonlat: Sequence[tuple[float, float]]):
    """Prefer ODM's reconstructed camera positions; fall back to photo EXIF GPS."""
    if shots_geojson and shots_geojson.exists():
        try:
            data = json.loads(shots_geojson.read_text())
            pts = [tuple(f["geometry"]["coordinates"][:2]) for f in data.get("features", [])
                   if f.get("geometry", {}).get("type") == "Point"]
            if len(pts) >= 3:
                return pts, "reconstructed"
        except Exception:
            pass
    return list(exif_lonlat), "exif"


def compute_volume(
    dsm_path: str | Path,
    cameras_lonlat: Iterable[tuple[float, float]],
    ring_width_m: float = 2.0,
    density_t_m3: float | None = None,
    preview_path: str | Path | None = None,
    title: str = "",
) -> VolumeResult:
    cams = [(float(lon), float(lat)) for lon, lat in cameras_lonlat]
    if len(cams) < 3:
        raise VolumeError("Need at least 3 camera positions with GPS to define the pile boundary.")

    with rasterio.open(dsm_path) as ds:
        band = ds.read(1, masked=True)
        transform, crs = ds.transform, ds.crs
    if crs is None or not crs.is_projected:
        raise VolumeError("DSM must be in a projected (metric) coordinate system.")

    z = band.filled(np.nan).astype("float64")
    valid = np.isfinite(z) & ~np.ma.getmaskarray(band)
    cell = abs(transform.a)
    cell_area = abs(transform.a * transform.e - transform.b * transform.d)

    to_crs = Transformer.from_crs("EPSG:4326", crs, always_xy=True)
    pts = [to_crs.transform(lon, lat) for lon, lat in cams]
    hull = MultiPoint(pts).convex_hull
    if not isinstance(hull, Polygon) or hull.area < 4:
        raise VolumeError("Camera positions don't enclose an area - walk all the way around the pile.")

    shape = z.shape
    inside = geometry_mask([hull], out_shape=shape, transform=transform, invert=True)
    ring_geom = hull.exterior.buffer(ring_width_m)
    ring = geometry_mask([ring_geom], out_shape=shape, transform=transform, invert=True) & valid

    warnings: list[str] = []
    if inside.sum() == 0:
        raise VolumeError("Pile boundary does not overlap the DSM.")
    if ring.sum() < 50:
        raise VolumeError("Too little ground reconstructed around the pile to fit a base surface.")

    rr, cc = np.nonzero(ring)
    rx, ry = _pixel_centers(transform, rr, cc)
    plane, rmse, slope_pct, n_base = _robust_plane(rx, ry, z[rr, cc])
    if rmse > 0.25:
        warnings.append(f"Ground around the pile is uneven (RMSE {rmse:.2f} m); base plane may be inaccurate.")
    if slope_pct > 15:
        warnings.append(f"Base plane slope is {slope_pct:.0f} % - check the ground really is that steep.")

    # Fill DSM holes inside the boundary with the nearest valid value.
    coverage = float((valid & inside).sum() / inside.sum() * 100)
    if coverage < 99.9:
        idx = ndimage.distance_transform_edt(~valid, return_distances=False, return_indices=True)
        z_filled = z[tuple(idx)]
    else:
        z_filled = z
    if coverage < 85:
        warnings.append(f"Only {coverage:.0f} % of the area was reconstructed; holes were filled by interpolation.")

    ir, ic = np.nonzero(inside)
    ix, iy = _pixel_centers(transform, ir, ic)
    dz = z_filled[ir, ic] - plane(ix, iy)

    fill = float(dz[dz > 0].sum() * cell_area)
    cut = float(-dz[dz < 0].sum() * cell_area)
    net = fill - cut
    footprint = float((dz > 0.10).sum() * cell_area)
    max_h = float(dz.max()) if dz.size else 0.0
    if cut > 0.15 * max(fill, 1e-9) and fill > 0:
        warnings.append("Large cut volume - the ground inside the boundary is lower than around it.")

    # Is the pile touching the boundary? Then part of it may be outside.
    edge = inside & ~ndimage.binary_erosion(inside, iterations=max(1, int(round(1.0 / cell))))
    er, ec = np.nonzero(edge)
    ex, ey = _pixel_centers(transform, er, ec)
    edge_dz = z_filled[er, ec] - plane(ex, ey)
    if edge_dz.size and np.percentile(edge_dz, 95) > 0.5:
        warnings.append("Pile reaches the boundary - walk further out from the toe of the pile next time.")

    result = VolumeResult(
        volume_m3=net, fill_m3=fill, cut_m3=cut, footprint_m2=footprint,
        boundary_area_m2=float(hull.area), max_height_m=max_h,
        base_slope_pct=slope_pct, base_rmse_m=rmse, coverage_pct=coverage,
        cell_size_m=cell, camera_count=len(cams),
        tonnes=(net * density_t_m3) if density_t_m3 else None,
        density_t_m3=density_t_m3, warnings=warnings,
    )

    if preview_path:
        _render_preview(preview_path, z_filled, plane, transform, inside, hull, ring_geom, pts, result, title)
    return result


def _render_preview(path, z, plane, transform, inside, hull, ring_geom, pts, res: VolumeResult, title: str):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    minx, miny, maxx, maxy = ring_geom.bounds
    inv = ~transform
    c0, r0 = inv * (minx, maxy)
    c1, r1 = inv * (maxx, miny)
    r0, r1 = max(0, int(r0)), min(z.shape[0], int(np.ceil(r1)))
    c0, c1 = max(0, int(c0)), min(z.shape[1], int(np.ceil(c1)))
    rows, cols = np.mgrid[r0:r1, c0:c1]
    xs, ys = _pixel_centers(transform, rows, cols)
    dz = z[r0:r1, c0:c1] - plane(xs, ys)
    dz = np.where(inside[r0:r1, c0:c1], dz, np.nan)

    fig, ax = plt.subplots(figsize=(8, 8), dpi=110)
    vmax = max(np.nanpercentile(dz, 99.5), 0.5) if np.isfinite(dz).any() else 1
    im = ax.imshow(dz, cmap="terrain", vmin=0, vmax=vmax,
                   extent=(xs[0, 0], xs[0, -1], ys[-1, 0], ys[0, 0]), origin="upper")
    if np.isfinite(dz).any():
        levels = np.linspace(0.5, vmax, 6)
        ax.contour(xs, ys, np.nan_to_num(dz, nan=0), levels=levels, colors="k", linewidths=0.4, alpha=0.5)
    hx, hy = hull.exterior.xy
    ax.plot(hx, hy, color="#d33", lw=1.5, label="Measurement boundary")
    px, py = zip(*pts)
    ax.scatter(px, py, s=8, c="#1565c0", label="Camera positions", zorder=3)
    ax.set_aspect("equal")
    ax.set_xticks([]); ax.set_yticks([])
    cb = fig.colorbar(im, ax=ax, fraction=0.04, pad=0.02)
    cb.set_label("Height above base (m)")
    ax.legend(loc="lower right", fontsize=8)
    txt = f"{res.volume_m3:,.1f} m³"
    if res.tonnes is not None:
        txt += f"  ·  {res.tonnes:,.1f} t"
    ax.set_title(f"{title}\n{txt}" if title else txt, fontsize=13)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)
