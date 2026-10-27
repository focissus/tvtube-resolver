"""TVTube resolver - chay tren Render (goi free).
TV khong can biet gi ngoai dia chi https cua dich vu nay."""
import os, re, time, threading
from pathlib import Path
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, JSONResponse
import yt_dlp

CACHE = Path(os.environ.get("TVTUBE_DIR", "/tmp/tvtube")) / "cache"
CACHE.mkdir(parents=True, exist_ok=True)
TARGET_H = int(os.environ.get("TVTUBE_HEIGHT", "720"))
TARGET_FPS = int(os.environ.get("TVTUBE_FPS", "30"))
MAX_GB = float(os.environ.get("TVTUBE_CACHE_GB", "4"))

FORMAT = (
    f"bv*[vcodec~='avc1'][height<={TARGET_H}][fps<={TARGET_FPS}]+ba[acodec~='mp4a']"
    f"/b[height<={TARGET_H}][ext=mp4][vcodec~='avc1']"
)
YDL = {"quiet": True, "no_warnings": True, "noplaylist": True, "socket_timeout": 25, "retries": 3}

# IP trung tâm dữ liệu hay bị YouTube nghi ngờ, nên thử lần lượt nhiều kiểu máy khách.
CLIENTS = [None, ["android_vr"], ["ios"], ["mweb"], ["web_safari"], ["tv"], ["android"]]

COOKIES = "/tmp/cookies.txt"
if os.environ.get("COOKIES_B64"):
    import base64
    Path(COOKIES).write_bytes(base64.b64decode(os.environ["COOKIES_B64"]))


def _opts(client):
    o = dict(YDL)
    args = {"youtubepot-bgutilhttp": {"base_url": ["http://127.0.0.1:4416"]}}
    if client:
        args["youtube"] = {"player_client": client}
    o["extractor_args"] = args
    if os.path.exists(COOKIES):
        o["cookiefile"] = COOKIES
    return o
_lock = threading.Lock()
app = FastAPI(title="TVTube resolver")


def safe(s: str) -> str:
    return re.sub(r"[^A-Za-z0-9_-]+", "_", s)[:50]


def cleanup():
    files = sorted(CACHE.glob("*.mp4"), key=lambda p: p.stat().st_mtime)
    total = sum(f.stat().st_size for f in files)
    while files and total > MAX_GB * 1024 ** 3:
        f = files.pop(0)
        try:
            total -= f.stat().st_size
            f.unlink()
        except OSError:
            pass


# Render gói free ngủ sau 15 phút không có truy cập. Tự gọi chính mình mỗi 5 phút để giữ thức.
SELF_URL = os.environ.get("RENDER_EXTERNAL_URL", "").rstrip("/")


def _keep_awake():
    import urllib.request, time as _t
    while SELF_URL:
        _t.sleep(300)
        try:
            urllib.request.urlopen(SELF_URL + "/health", timeout=30).read()
        except Exception:
            pass


if SELF_URL:
    threading.Thread(target=_keep_awake, daemon=True).start()


@app.get("/health")
def health():
    return {"ok": True, "cache_files": len(list(CACHE.glob("*.mp4")))}


@app.get("/api/search")
def search(q: str = Query(..., min_length=1), n: int = 20):
    opts = dict(YDL); opts.update({"extract_flat": "in_playlist", "skip_download": True})
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(f"ytsearch{n}:{q}", download=False)
    except Exception as e:
        raise HTTPException(502, f"Không tìm được: {str(e)[:120]}")
    items = []
    for e in info.get("entries", []):
        if not e:
            continue
        d = e.get("duration") or 0
        vid = e.get("id")
        items.append({"id": vid, "title": e.get("title") or "",
                      "author": e.get("uploader") or e.get("channel") or "",
                      "thumb": f"https://i.ytimg.com/vi/{vid}/mqdefault.jpg",
                      "duration": f"{int(d)//60}:{int(d)%60:02d}" if d else ""})
    return {"items": items}


@app.get("/api/resolve")
def resolve(request: Request, id: str):
    out = CACHE / (safe(id) + ".mp4")
    if not (out.exists() and out.stat().st_size > 0):
        with _lock:
            if not (out.exists() and out.stat().st_size > 0):
                tmp = CACHE / (safe(id) + ".part.mp4")
                last = ""
                for client in CLIENTS:
                    opts = _opts(client)
                    opts.update({"format": FORMAT, "merge_output_format": "mp4", "outtmpl": str(tmp),
                                 "postprocessor_args": {"remuxer": ["-movflags", "+faststart"]}})
                    try:
                        with yt_dlp.YoutubeDL(opts) as ydl:
                            ydl.download([f"https://www.youtube.com/watch?v={id}"])
                        if tmp.exists() and tmp.stat().st_size > 0:
                            break
                    except Exception as e:
                        last = str(e)[:120]
                        try:
                            if tmp.exists():
                                tmp.unlink()
                        except OSError:
                            pass
                else:
                    raise HTTPException(502, "Không lấy được luồng: " + last)
                if not tmp.exists():
                    raise HTTPException(502, "Không tải được luồng video")
                tmp.rename(out)
                cleanup()
    host = request.headers.get("host", request.url.netloc)
    return {"url": f"https://{host}/media/{out.name}", "quality": f"{TARGET_H}p", "mime": "video/mp4"}


@app.get("/api/debug")
def debug(id: str = "apONtL8FHQU"):
    """Cho biết kiểu kết nối nào tải được video này (dùng khi YouTube chặn IP)."""
    res = []
    for client in CLIENTS:
        try:
            opts = _opts(client)
            opts.update({"skip_download": True, "format": FORMAT})
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(f"https://www.youtube.com/watch?v={id}", download=False)
            fmts = info.get("requested_formats") or info.get("formats") or []
            res.append({"client": client or "default", "ok": True,
                        "formats": len(fmts), "height": info.get("height")})
        except Exception as e:
            res.append({"client": client or "default", "ok": False, "err": str(e)[:90]})
    return {"results": res}


@app.get("/media/{name}")
def media(name: str):
    p = CACHE / (safe(name.replace(".mp4", "")) + ".mp4")
    if not p.exists():
        raise HTTPException(404)
    return FileResponse(str(p), media_type="video/mp4", headers={"Accept-Ranges": "bytes"})


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("TVTUBE_PORT", "10000")))
