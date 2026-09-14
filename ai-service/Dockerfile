# syntax=docker.m.daocloud.io/docker/dockerfile:1.7
ARG BASE_REGISTRY=docker.m.daocloud.io
FROM ${BASE_REGISTRY}/library/python:3.10-slim

WORKDIR /app

# 安装基础系统依赖及 FFmpeg（用于非 WAV 音视频的解码与合流）
RUN sed -i 's#deb.debian.org#mirrors.aliyun.com#g' /etc/apt/sources.list.d/debian.sources 2>/dev/null || \
    sed -i 's#deb.debian.org#mirrors.aliyun.com#g' /etc/apt/sources.list 2>/dev/null || true \
    && apt-get update && apt-get install -y --no-install-recommends \
       ffmpeg \
       ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# 默认安装 CPU / 跨平台兼容版 onnxruntime；若开启 GPU 构建参数则安装 onnxruntime-gpu
ARG ONNX_PACKAGE=onnxruntime
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple \
    numpy>=1.24.0,<3.0.0 \
    ${ONNX_PACKAGE}

COPY . .

ENV PYTHONUNBUFFERED=1 \
    UVR_PORT=8900 \
    NVIDIA_VISIBLE_DEVICES=all \
    NVIDIA_DRIVER_CAPABILITIES=compute,utility

EXPOSE 8900

HEALTHCHECK --interval=10s --timeout=5s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8900/api/health')" || exit 1

ENTRYPOINT ["python", "server.py"]
