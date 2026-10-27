# tvtube-resolver

Máy chủ nhỏ cấp luồng video cho app **TVTube** trên TV Android cũ (KitKat, chip MStar).

Nó tìm video trên YouTube, tải luồng **H.264 720p 30fps + AAC**, ghép thành một tệp MP4
rồi phát lại. TV chỉ nhận một đường dẫn MP4 nên giải mã bằng phần cứng, không bị đen màn hình.

## API

| Đường dẫn | Việc |
|---|---|
| `GET /health` | kiểm tra sống |
| `GET /api/search?q=...&n=20` | tìm video |
| `GET /api/resolve?id=...` | chuẩn bị và trả về link MP4 |
| `GET /media/<id>.mp4` | tệp MP4, hỗ trợ Range |

## Triển khai

Chạy trên Render bằng `render.yaml` (Docker, gói free). Render sẽ ngủ sau 15 phút rảnh;
workflow trong `.github/workflows/keepalive.yml` tự đánh thức mỗi 10 phút để dịch vụ chạy liên tục.
