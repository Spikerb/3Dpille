"""Stockpile volume API.

The Android app talks only to this service. It stores the photos, sends them to
NodeODM for 3D reconstruction, then computes the volume from the resulting DSM.

Environment variables
---------------------
API_KEY          shared secret the app sends in the X-API-Key header (strongly recommended)
NODEODM_URL      e.g. http://nodeodm:3000
NODEODM_TOKEN    NodeODM token, if NodeODM was started with --token
DATA_DIR         where jobs are stored (default ./data)
ODM_OPTIONS      JSON object overriding the default ODM processing options
POLL_SECONDS     how often to poll NodeODM (default 15)
"""
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import threading
import time
import uuid as uuidlib
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image
from pydantic import BaseModel, Field

from . import nodeodm as odm
from .volume import VolumeError, compute_volume, load_camera_positions

log = logging.getLogger("stockpile")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

API_KEY = os.environ.get("API_KEY", "")
DATA_DIR = Path(os.environ.get("DATA_DIR", "./data")).resolve()
JOBS_DIR = DATA_DIR / "jobs"
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "15"))

# Tuned for hand-held phone photos taken from the ground around a pile.
DEFAULT_ODM_OPTIONS = {
    "dsm": True,
    "dem-resolution": 5,          # cm / pixel
    "pc-quality": "high",
    "feature-quality": "high",
    "skip-orthophoto": True,      # an orthophoto from ground-level photos isn't useful
    "auto-boundary": True,
    "gps-accuracy": 5,            # phone GPS, metres
}
ODM_OPTIONS = {**DEFAULT_ODM_OPTIONS, **json.loads(os.environ.get("ODM_OPTIONS", "{}") or "{}")}

node = odm.NodeODM(os.environ.get("NODEODM_URL", "http://localhost:3000"), os.environ.get("NODEODM_TOKEN", ""))
_lock = threading.RLock()

ALLOWED_EXT = {".jpg", ".jpeg"}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,120}$")


# ---------------------------------------------------------------- storage
def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _job_dir(job_id: str) -> Path:
    if not re.fullmatch(r"[a-f0-9]{32}", job_id):
        raise HTTPException(404, "Job not found")
    return JOBS_DIR / job_id


def _load(job_id: str) -> dict:
    p = _job_dir(job_id) / "job.json"
    if not p.exists():
        raise HTTPException(404, "Job not found")
    return json.loads(p.read_text())


def _save(job: dict) -> None:
    job["updated_at"] = _now()
    d = JOBS_DIR / job["id"]
    tmp = d / "job.json.tmp"
    tmp.write_text(json.dumps(job, indent=2))
    tmp.replace(d / "job.json")


def _update(job_id: str, **fields) -> dict:
    with _lock:
        job = _load(job_id)
        job.update(fields)
        _save(job)
        return job


def _images(job_id: str) -> list[Path]:
    d = _job_dir(job_id) / "images"
    return sorted(p for p in d.iterdir() if p.suffix.lower() in ALLOWED_EXT) if d.exists() else []


def _exif_lonlat(path: Path) -> tuple[float, float] | None:
    try:
        with Image.open(path) as im:
            gps = im.getexif().get_ifd(0x8825)
        if not gps or 2 not in gps or 4 not in gps:
            return None

        def deg(v):
            d, m, s = (float(x) for x in v)
            return d + m / 60 + s / 3600

        lat, lon = deg(gps[2]), deg(gps[4])
        if gps.get(1, "N") in ("S", b"S"):
            lat = -lat
        if gps.get(3, "E") in ("W", b"W"):
            lon = -lon
        if lat == 0 and lon == 0:
            return None
        return lon, lat
    except Exception:
        return None


# ---------------------------------------------------------------- API
app = FastAPI(title="Stockpile Volume API", version="1.0")


def auth(x_api_key: str = Header(default="")):
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(401, "Invalid API key")


class NewJob(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    density_t_m3: float | None = Field(default=None, gt=0, lt=10)
    client_id: str | None = Field(default=None, max_length=64)


@app.get("/api/health")
def health():
    try:
        info = node.info()
        return {"ok": True, "nodeodm": {"version": info.get("version"), "queue": info.get("taskQueueCount")}}
    except Exception as e:
        return {"ok": False, "nodeodm_error": str(e)}


@app.post("/api/jobs", dependencies=[Depends(auth)])
def create_job(body: NewJob):
    job_id = uuidlib.uuid4().hex
    d = JOBS_DIR / job_id
    (d / "images").mkdir(parents=True)
    job = {
        "id": job_id, "name": body.name, "density_t_m3": body.density_t_m3, "client_id": body.client_id,
        "status": "created", "progress": 0, "message": "Waiting for photos", "image_count": 0,
        "created_at": _now(), "nodeodm_uuid": None, "result": None, "has_gcp": False,
    }
    _save(job)
    return job


@app.get("/api/jobs", dependencies=[Depends(auth)])
def list_jobs():
    jobs = []
    if JOBS_DIR.exists():
        for d in JOBS_DIR.iterdir():
            try:
                jobs.append(json.loads((d / "job.json").read_text()))
            except Exception:
                continue
    return sorted(jobs, key=lambda j: j["created_at"], reverse=True)


@app.get("/api/jobs/{job_id}", dependencies=[Depends(auth)])
def get_job(job_id: str):
    return _load(job_id)


@app.post("/api/jobs/{job_id}/images", dependencies=[Depends(auth)])
def upload_image(job_id: str, file: UploadFile = File(...)):
    job = _load(job_id)
    if job["status"] not in ("created", "failed"):
        raise HTTPException(409, "Job already started")
    name = Path(file.filename or "").name
    if not SAFE_NAME.fullmatch(name) or Path(name).suffix.lower() not in ALLOWED_EXT:
        raise HTTPException(400, "Only .jpg images with simple file names are accepted")
    dest = _job_dir(job_id) / "images" / name
    tmp = dest.with_suffix(".part")
    with open(tmp, "wb") as fh:
        shutil.copyfileobj(file.file, fh, 1 << 20)
    with open(tmp, "rb") as fh:
        if fh.read(3) != b"\xff\xd8\xff":
            tmp.unlink(missing_ok=True)
            raise HTTPException(400, "File is not a JPEG image")
    tmp.replace(dest)  # re-uploading the same name overwrites: safe for retries
    with _lock:
        job = _load(job_id)
        job["image_count"] = len(_images(job_id))
        _save(job)
    return {"image_count": job["image_count"]}


@app.post("/api/jobs/{job_id}/gcp", dependencies=[Depends(auth)])
def upload_gcp(job_id: str, file: UploadFile = File(...)):
    """Optional ODM ground control point file (gcp_list.txt) for survey-grade scale."""
    _load(job_id)
    (_job_dir(job_id) / "gcp_list.txt").write_bytes(file.file.read())
    return _update(job_id, has_gcp=True)


@app.post("/api/jobs/{job_id}/start", dependencies=[Depends(auth)])
def start_job(job_id: str):
    with _lock:
        job = _load(job_id)
        if job["status"] in ("queued", "submitting", "processing", "computing"):
            return job
        n = len(_images(job_id))
        if n < 10:
            raise HTTPException(400, f"Need at least 10 photos, got {n}")
        job.update(status="queued", progress=0, message="Queued for processing", image_count=n, error=None)
        _save(job)
        return job


@app.delete("/api/jobs/{job_id}", dependencies=[Depends(auth)])
def delete_job(job_id: str):
    job = _load(job_id)
    if job.get("nodeodm_uuid"):
        node.remove(job["nodeodm_uuid"])
    shutil.rmtree(_job_dir(job_id), ignore_errors=True)
    return {"deleted": job_id}


@app.get("/api/jobs/{job_id}/preview.png", dependencies=[Depends(auth)])
def preview(job_id: str):
    p = _job_dir(job_id) / "results" / "preview.png"
    if not p.exists():
        raise HTTPException(404, "No preview yet")
    return FileResponse(p, media_type="image/png")


@app.get("/api/jobs/{job_id}/dsm.tif", dependencies=[Depends(auth)])
def dsm(job_id: str):
    p = _job_dir(job_id) / "results" / "dsm.tif"
    if not p.exists():
        raise HTTPException(404, "No DSM yet")
    return FileResponse(p, media_type="image/tiff", filename=f"{job_id}_dsm.tif")


# ---------------------------------------------------------------- worker
def _submit(job: dict) -> None:
    jid = job["id"]
    _update(jid, status="submitting", message="Sending photos to the processing node", progress=0)
    files = _images(jid)
    gcp = _job_dir(jid) / "gcp_list.txt"
    if gcp.exists():
        files.append(gcp)
    uuid = node.create_task(f"{job['name']} [{jid[:8]}]", ODM_OPTIONS, files)
    _update(jid, status="processing", nodeodm_uuid=uuid, message="Reconstructing 3D model", progress=1)


def _finish(job: dict) -> None:
    jid = job["id"]
    _update(jid, status="computing", message="Computing volume", progress=99)
    res_dir = _job_dir(jid) / "results"
    res_dir.mkdir(exist_ok=True)
    zpath = node.download(job["nodeodm_uuid"], "all.zip", res_dir / "all.zip")
    shots = None
    with zipfile.ZipFile(zpath) as z:
        names = z.namelist()
        dsm_name = next((n for n in names if n.endswith("odm_dem/dsm.tif")), None)
        if not dsm_name:
            raise VolumeError("Processing finished but produced no surface model (DSM). "
                              "Usually the photos didn't overlap enough.")
        with z.open(dsm_name) as src, open(res_dir / "dsm.tif", "wb") as dst:
            shutil.copyfileobj(src, dst)
        shots_name = next((n for n in names if n.endswith("shots.geojson")), None)
        if shots_name:
            with z.open(shots_name) as src, open(res_dir / "shots.geojson", "wb") as dst:
                shutil.copyfileobj(src, dst)
            shots = res_dir / "shots.geojson"
    zpath.unlink(missing_ok=True)

    exif = [ll for ll in (_exif_lonlat(p) for p in _images(jid)) if ll]
    cams, source = load_camera_positions(shots, exif)
    result = compute_volume(res_dir / "dsm.tif", cams, density_t_m3=job.get("density_t_m3"),
                            preview_path=res_dir / "preview.png", title=job["name"])
    out = result.to_dict()
    out["camera_source"] = source
    _update(jid, status="done", progress=100, message="Done", result=out, finished_at=_now())
    node.remove(job["nodeodm_uuid"])


def _poll(job: dict) -> None:
    info = node.task_info(job["nodeodm_uuid"])
    code = info.get("status", {}).get("code")
    if code == odm.COMPLETED:
        _finish(job)
    elif code in (odm.FAILED, odm.CANCELED):
        tail = node.output(job["nodeodm_uuid"], 15)
        _update(job["id"], status="failed", message="3D reconstruction failed",
                error="\n".join(tail[-15:]) or info.get("status", {}).get("errorMessage", ""))
    else:
        prog = max(1, min(98, int(info.get("progress", 0))))
        msg = "Queued on processing node" if code == odm.QUEUED else "Reconstructing 3D model"
        if prog != job.get("progress") or msg != job.get("message"):
            _update(job["id"], progress=prog, message=msg)


def worker_loop(stop: threading.Event) -> None:
    # Jobs interrupted mid-submit by a restart go back to the queue.
    for j in list_jobs():
        if j["status"] == "submitting":
            _update(j["id"], status="queued")
        elif j["status"] == "computing":
            _update(j["id"], status="processing")
    while not stop.is_set():
        for job in list_jobs():
            try:
                if job["status"] == "queued":
                    _submit(job)
                elif job["status"] == "processing":
                    _poll(job)
            except (VolumeError, odm.NodeODMError) as e:
                log.warning("job %s: %s", job["id"], e)
                _update(job["id"], status="failed", message="Failed", error=str(e))
            except Exception as e:  # network blips etc.: keep the job, retry next round
                log.exception("job %s: transient error", job["id"])
                retries = job.get("retries", 0) + 1
                if retries > 20:
                    _update(job["id"], status="failed", message="Failed", error=str(e))
                    continue
                _update(job["id"], retries=retries)
                if job["status"] == "queued":       # failed while submitting
                    _update(job["id"], status="queued", message=f"Retrying: {e}")
                elif job["status"] == "processing":  # failed while polling/computing
                    _update(job["id"], status="processing")
        stop.wait(POLL_SECONDS)


_stop = threading.Event()


@app.on_event("startup")
def _start_worker():
    JOBS_DIR.mkdir(parents=True, exist_ok=True)
    threading.Thread(target=worker_loop, args=(_stop,), daemon=True, name="worker").start()


@app.on_event("shutdown")
def _stop_worker():
    _stop.set()
