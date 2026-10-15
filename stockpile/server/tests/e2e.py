"""End-to-end test against the fake NodeODM.

Run from server/:
  (cd tests && uvicorn mock_nodeodm:app --port 3001) &
  API_KEY=test NODEODM_URL=http://127.0.0.1:3001 DATA_DIR=/tmp/sp POLL_SECONDS=1 uvicorn stockpile_api.main:app --port 8081 &
  python tests/e2e.py
"""
import io, sys, time, requests
from PIL import Image
API = "http://127.0.0.1:8081/api"; H = {"X-API-Key": "test"}
assert requests.post(f"{API}/jobs", json={"name": "x"}).status_code == 401
job = requests.post(f"{API}/jobs", json={"name": "Gravel pile A", "density_t_m3": 1.6}, headers=H).json()
for i in range(12):
    im = Image.new("RGB", (64, 48), (i * 20, 100, 50)); ex = im.getexif()
    ex[0x8825] = {1: "N", 2: (49.0, 11.0, 42.0), 3: "E", 4: (16.0, 39.0, 50.0)}
    b = io.BytesIO(); im.save(b, "JPEG", exif=ex); b.seek(0)
    r = requests.post(f"{API}/jobs/{job['id']}/images", files={"file": (f"IMG_{i:03d}.jpg", b, "image/jpeg")}, headers=H); r.raise_for_status()
print("uploaded", r.json())
assert requests.post(f"{API}/jobs/{job['id']}/images", files={"file": ("../evil.jpg", b"x")}, headers=H).status_code == 400
print(requests.post(f"{API}/jobs/{job['id']}/start", headers=H).json()["status"])
for _ in range(60):
    j = requests.get(f"{API}/jobs/{job['id']}", headers=H).json(); print(j["status"], j["progress"], j["message"])
    if j["status"] in ("done", "failed"): break
    time.sleep(1)
print(j.get("result") or j.get("error"))
assert j["status"] == "done" and abs(j["result"]["volume_m3"] - 523.6) < 10
p = requests.get(f"{API}/jobs/{job['id']}/preview.png", headers=H); assert p.status_code == 200 and p.content[:4] == b"\x89PNG"
print("health", requests.get(f"{API}/health").json()); print("E2E PASS")
