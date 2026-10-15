"""Minimal client for the NodeODM REST API (https://github.com/OpenDroneMap/NodeODM)."""
from __future__ import annotations

import json
from pathlib import Path

import requests

# NodeODM task status codes
QUEUED, RUNNING, FAILED, COMPLETED, CANCELED = 10, 20, 30, 40, 50


class NodeODMError(RuntimeError):
    pass


class NodeODM:
    def __init__(self, url: str, token: str = "", timeout: float = 60):
        self.url = url.rstrip("/")
        self.token = token
        self.timeout = timeout

    def _params(self, extra: dict | None = None) -> dict:
        p = dict(extra or {})
        if self.token:
            p["token"] = self.token
        return p

    def _check(self, r: requests.Response) -> dict | list:
        try:
            data = r.json()
        except ValueError:
            raise NodeODMError(f"HTTP {r.status_code}: {r.text[:200]}")
        if r.status_code >= 400 or (isinstance(data, dict) and data.get("error")):
            raise NodeODMError(data.get("error") if isinstance(data, dict) else str(data))
        return data

    def info(self) -> dict:
        return self._check(requests.get(f"{self.url}/info", params=self._params(), timeout=10))

    def create_task(self, name: str, options: dict, files: list[Path]) -> str:
        opts = [{"name": k, "value": v} for k, v in options.items()]
        r = requests.post(f"{self.url}/task/new/init", params=self._params(),
                          data={"name": name, "options": json.dumps(opts)}, timeout=self.timeout)
        uuid = self._check(r)["uuid"]
        try:
            for f in files:  # one file per request keeps memory flat and survives big jobs
                with open(f, "rb") as fh:
                    r = requests.post(f"{self.url}/task/new/upload/{uuid}", params=self._params(),
                                      files={"images": (f.name, fh)}, timeout=600)
                self._check(r)
            self._check(requests.post(f"{self.url}/task/new/commit/{uuid}", params=self._params(),
                                      timeout=self.timeout))
        except Exception:
            self.remove(uuid)
            raise
        return uuid

    def task_info(self, uuid: str) -> dict:
        return self._check(requests.get(f"{self.url}/task/{uuid}/info", params=self._params(),
                                        timeout=self.timeout))

    def output(self, uuid: str, last_lines: int = 30) -> list[str]:
        try:
            return self._check(requests.get(f"{self.url}/task/{uuid}/output",
                                            params=self._params({"line": -last_lines}), timeout=self.timeout))
        except Exception:
            return []

    def download(self, uuid: str, asset: str, dest: Path) -> Path:
        with requests.get(f"{self.url}/task/{uuid}/download/{asset}", params=self._params(),
                          stream=True, timeout=600) as r:
            if r.status_code != 200:
                raise NodeODMError(f"Download of {asset} failed: HTTP {r.status_code}")
            with open(dest, "wb") as fh:
                for chunk in r.iter_content(1 << 20):
                    fh.write(chunk)
        return dest

    def remove(self, uuid: str) -> None:
        try:
            requests.post(f"{self.url}/task/remove", params=self._params(), data={"uuid": uuid}, timeout=30)
        except Exception:
            pass
