# Volitelný zpracovací server (fotogrammetrie)

Pro hromady větší, než zvládne skener v telefonu. Fotky z aplikace se zpracují v OpenDroneMap
a z výsledného modelu povrchu (DSM) se spočítá objem stejnou metodou jako v telefonu.

Požadavky: Linux s Dockerem, doporučeno 16 GB RAM, 4+ jádra, ~20 GB disku.

```bash
cp .env.example .env      # nastavte API_KEY na dlouhý náhodný řetězec
docker compose up -d --build
curl http://localhost:8080/api/health
```

V aplikaci: Nastavení → Zpracovací server → adresa `http://<IP serveru>:8080` a stejný API klíč.
Z mobilních dat zpřístupněte server jen přes HTTPS (reverzní proxy) nebo VPN (Tailscale/WireGuard).

API (hlavička `X-API-Key`): `POST /api/jobs`, `POST /api/jobs/{id}/images`, `POST /api/jobs/{id}/start`,
`GET /api/jobs/{id}`, `GET /api/jobs/{id}/preview.png`, `GET /api/jobs/{id}/dsm.tif`, `DELETE /api/jobs/{id}`.

Testy: `python tests/test_volume.py .` (syntetická hromada, chyba musí být pod 2 %).
