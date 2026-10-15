"""Tiny fake NodeODM that 'processes' any upload into the synthetic cone DSM."""
import io, json, sys, time, uuid, zipfile, tempfile
from pathlib import Path
from fastapi import FastAPI, Form, UploadFile, File
from fastapi.responses import Response
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_volume import make

app = FastAPI(); tasks = {}
@app.get("/info")
def info(): return {"version": "mock", "taskQueueCount": 0}
@app.post("/task/new/init")
def init(name: str = Form(...), options: str = Form("[]")):
    u = str(uuid.uuid4()); tasks[u] = {"name": name, "files": [], "t": None, "opts": json.loads(options)}; return {"uuid": u}
@app.post("/task/new/upload/{u}")
def up(u: str, images: UploadFile = File(...)):
    tasks[u]["files"].append(images.filename); return {"success": True}
@app.post("/task/new/commit/{u}")
def commit(u: str): tasks[u]["t"] = time.time(); return {"uuid": u}
@app.get("/task/{u}/info")
def tinfo(u: str):
    el = time.time() - tasks[u]["t"]; done = el > 3
    return {"uuid": u, "status": {"code": 40 if done else 20}, "progress": 100 if done else int(el / 3 * 100), "imagesCount": len(tasks[u]["files"])}
@app.get("/task/{u}/output")
def out(u: str, line: int = 0): return ["ok"]
@app.get("/task/{u}/download/{asset}")
def dl(u: str, asset: str):
    d = Path(tempfile.mkdtemp()); cams, _ = make(d / "dsm.tif")
    shots = {"type": "FeatureCollection", "features": [{"type": "Feature", "geometry": {"type": "Point", "coordinates": [lo, la, 250]}} for lo, la in cams]}
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.write(d / "dsm.tif", "odm_dem/dsm.tif"); z.writestr("odm_report/shots.geojson", json.dumps(shots))
    return Response(b.getvalue(), media_type="application/zip")
@app.post("/task/remove")
def rm(uuid: str = Form(...)): tasks.pop(uuid, None); return {"success": True}
