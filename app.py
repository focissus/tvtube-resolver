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
                opts = dict(YDL)
                opts.update({"format": FORMAT, "merge_output_format": "mp4", "outtmpl": str(tmp),
                             "postprocessor_args": {"remuxer": ["-movflags", "+faststart"]}})
                try:
                    with yt_dlp.YoutubeDL(opts) as ydl:
                        ydl.download([f"https://www.youtube.com/watch?v={id}"])
                except Exception as e:
                    raise HTTPException(502, f"Không lấy được luồng: {str(e)[:120]}")
                if not tmp.exists():
                    raise HTTPException(502, "Không tải được luồng video")
                tmp.rename(out)
                cleanup()
    host = request.headers.get("host", request.url.netloc)
    return {"url": f"https://{host}/media/{out.name}", "quality": f"{TARGET_H}p", "mime": "video/mp4"}


@app.get("/media/{name}")
def media(name: str):
    p = CACHE / (safe(name.replace(".mp4", "")) + ".mp4")
    if not p.exists():
        raise HTTPException(404)
    return FileResponse(str(p), media_type="video/mp4", headers={"Accept-Ranges": "bytes"})


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.environ.get("TVTUBE_PORT", "10000")))
