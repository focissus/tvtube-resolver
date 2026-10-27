FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg nodejs npm git ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*

# Bộ sinh token chống bot của YouTube (bgutil) - không cần tài khoản Google
RUN git clone --depth 1 https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git /opt/bgutil \
 && cd /opt/bgutil/server \
 && npm install --no-audit --no-fund --loglevel=error \
 && npx tsc \
 && rm -rf /opt/bgutil/.git

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && pip install --no-cache-dir bgutil-ytdlp-pot-provider

COPY app.py start.sh ./
RUN chmod +x start.sh

ENV TVTUBE_DIR=/tmp/tvtube TVTUBE_PORT=10000
EXPOSE 10000
CMD ["./start.sh"]
