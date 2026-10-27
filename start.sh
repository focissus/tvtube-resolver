#!/bin/sh
# chạy bộ sinh token ở nền rồi khởi động máy chủ chính
node /opt/bgutil/server/build/main.js --port 4416 --verbose > /tmp/pot.log 2>&1 &
sleep 3
exec python app.py
