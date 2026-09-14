# -*- coding: utf-8 -*-
"""
UVR-MDX-NET-Inst_HQ_3 AI 伴奏分离独立微服务。
支持与 Home KTV 后端无缝对接，提供同步流式分离接口 (/api/separate) 与 Web 交互控制台。
"""
import io
import json
import logging
import mimetypes
import os
import re
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

import numpy as np

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
log = logging.getLogger("uvr_service")

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from separate_mdx import MDXSeparator
import wav_io
import video_io

PORT = int(os.environ.get('UVR_PORT', os.environ.get('PORT', '8900')))
MODEL_NAME = 'UVR-MDX-NET-Inst_HQ_3'
MODEL_PATH = os.environ.get('UVR_MODEL_PATH')
if not MODEL_PATH or not os.path.exists(MODEL_PATH):
    for cand in [
        os.path.join(_HERE, 'models', MODEL_NAME + '.onnx'),
        os.path.join(_HERE, MODEL_NAME + '.onnx'),
        os.path.join(os.path.dirname(_HERE), 'models', MODEL_NAME + '.onnx')
    ]:
        if os.path.exists(cand):
            MODEL_PATH = cand
            break

WORKDIR = os.path.join(_HERE, 'jobs')
os.makedirs(WORKDIR, exist_ok=True)

MAX_UPLOAD = int(os.environ.get('UVR_MAX_MB', '500')) * 1024 * 1024
AUTH_TOKEN = os.environ.get('UVR_TOKEN', '').strip()

# 全局模型实例与推理锁（确保多并发时有序执行，避免显存/内存溢出）
_separator_lock = threading.Lock()
_separator = None


def get_separator():
    global _separator
    with _separator_lock:
        if _separator is None:
            if not MODEL_PATH or not os.path.exists(MODEL_PATH):
                raise FileNotFoundError(f"未找到 ONNX 伴奏模型文件，请检查路径: {MODEL_PATH}")
            _separator = MDXSeparator(MODEL_PATH)
        return _separator


# ---------------- 任务管理 ----------------
_tasks_lock = threading.Lock()
_tasks = {}
_jobs_lock = threading.Lock()


def _make_task():
    tid = uuid.uuid4().hex[:12]
    task = {
        'id': tid, 'status': 'queued', 'phase': 'queued', 'progress': 0,
        'total': 0, 'pct': 0, 'elapsed': 0, 'error': None,
        'created': time.time(),
        'in_path': None, 'inst_path': None, 'voc_path': None,
        'is_video': False, 'video_mode': 'add_track',
        'video_path': None, 'video_preview_path': None,
        'origin': 'output.wav'
    }
    with _tasks_lock:
        _tasks[tid] = task
    return task


# ---------------- multipart 解析 ----------------
def _decode_header_filename(line):
    m = re.search(r"filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]*)", line, re.I)
    if m:
        try:
            return unquote(m.group(1).strip())
        except Exception:
            return m.group(1).strip()
    m = re.search(r'filename\s*=\s*"([^"]*)"', line, re.I)
    if not m:
        return ''
    raw = m.group(1).replace('\\', '/').rsplit('/', 1)[-1]
    try:
        b = raw.encode('latin-1')
        return b.decode('utf-8')
    except Exception:
        return raw


def parse_multipart(content_type, body):
    m = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', content_type)
    if not m:
        raise ValueError('缺少 multipart boundary')
    boundary = (m.group(1) or m.group(2)).encode()
    result = {}
    for part in body.split(b'--' + boundary):
        part = part.strip(b'\r\n')
        if not part or part == b'--':
            continue
        if part.startswith(b'--'):
            break
        header_end = part.find(b'\r\n\r\n')
        if header_end < 0:
            continue
        raw_headers = part[:header_end].decode('latin-1')
        content = part[header_end + 4:]
        if content.endswith(b'\r\n'):
            content = content[:-2]
        name = filename = None
        for line in raw_headers.split('\r\n'):
            if line.lower().startswith('content-disposition'):
                nm = re.search(r'name="([^"]*)"', line)
                fn = re.search(r'filename\*\s*=|filename\s*=', line, re.I)
                if nm:
                    name = nm.group(1)
                if fn:
                    filename = _decode_header_filename(line)
        if name:
            result[name] = {'filename': filename, 'content': content}
    return result


class UVRRequestHandler(BaseHTTPRequestHandler):
    server_version = "HomeKTV-UVR-AI/1.0"

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization, Range')

    def _json(self, data, code=200):
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self._cors()
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _check_auth(self):
        if not AUTH_TOKEN:
            return True
        hdr = self.headers.get('Authorization', '')
        if hdr.startswith('Bearer ') and hdr[7:].strip() == AUTH_TOKEN:
            return True
        self._json({'ok': False, 'error': 'Unauthorized'}, 401)
        return False

    def _read_body(self):
        length = int(self.headers.get('Content-Length') or 0)
        if length <= 0:
            return b''
        if length > MAX_UPLOAD:
            raise ValueError(f"Payload too large (>{MAX_UPLOAD//1024//1024}MB)")
        return self.rfile.read(length)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        p = url.path
        if p in ('/api/health', '/health'):
            sep = None
            try:
                sep = get_separator()
                provider = sep.active_provider
            except Exception as e:
                provider = f"Uninitialized: {str(e)}"
            self._json({
                "status": "UP",
                "service": "UVR-MDX-NET-Inst_HQ_3",
                "provider": provider,
                "device": "GPU" if "CUDA" in str(provider) or "DirectML" in str(provider) else "CPU"
            })
        elif p in ('/', '/index.html'):
            index_path = os.path.join(_HERE, 'index.html')
            if os.path.exists(index_path):
                with open(index_path, 'rb') as f:
                    data = f.read()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            else:
                self._json({"message": "UVR-MDX-Net AI Separation Service is running"})
        elif p == '/api/task':
            qs = parse_qs(url.query)
            tid = (qs.get('id') or [''])[0]
            with _tasks_lock:
                t = _tasks.get(tid)
            if not t:
                self._json({'ok': False, 'error': 'Task not found'}, 404)
            else:
                self._json({'ok': True, 'task': t})
        elif p == '/api/download':
            qs = parse_qs(url.query)
            tid = (qs.get('id') or [''])[0]
            typ = (qs.get('type') or ['inst'])[0]
            with _tasks_lock:
                t = _tasks.get(tid)
            if not t or t.get('status') != 'done':
                self._json({'ok': False, 'error': 'Task not ready'}, 404)
                return
            target_path = t.get('inst_path') if typ == 'inst' else t.get('voc_path')
            if not target_path or not os.path.exists(target_path):
                self._json({'ok': False, 'error': 'File not found'}, 404)
                return
            size = os.path.getsize(target_path)
            self.send_response(200)
            self._cors()
            self.send_header('Content-Type', 'audio/wav')
            self.send_header('Content-Length', str(size))
            self.send_header('Content-Disposition', f'attachment; filename="{tid}_{typ}.wav"')
            self.end_headers()
            with open(target_path, 'rb') as f:
                while chunk := f.read(65536):
                    self.wfile.write(chunk)
        else:
            self.send_error(404, 'Not Found')

    def do_POST(self):
        url = urlparse(self.path)
        p = url.path
        if not self._check_auth():
            return

        if p == '/api/separate':
            self._handle_direct_separate()
        elif p == '/api/upload':
            self._handle_async_upload()
        else:
            self.send_error(404, 'Not Found')

    def _handle_direct_separate(self):
        """
        核心同步分离接口：接收待分离的原始音频（multipart 或 raw wav），
        直接流式返回剥离后的 16-bit 44.1kHz 高品质伴奏音频 WAV 数据。
        """
        started = time.time()
        try:
            body = self._read_body()
            ctype = self.headers.get('Content-Type', '')
            audio_bytes = None

            if 'multipart/form-data' in ctype.lower():
                fields = parse_multipart(ctype, body)
                item = fields.get('audio') or fields.get('file')
                if not item or not item.get('content'):
                    self._json({'ok': False, 'error': '未包含有效的 audio/file 字段'}, 400)
                    return
                audio_bytes = item['content']
            else:
                audio_bytes = body

            if not audio_bytes:
                self._json({'ok': False, 'error': '请求音频数据为空'}, 400)
                return

            # 解析音频并执行 AI 推理
            stream = io.BytesIO(audio_bytes)
            mix, sr = wav_io.read_wav_stream(stream)
            mix_resampled = wav_io.resample(mix, sr, 44100)

            sep = get_separator()
            log.info(f"开始执行 UVR-MDX-Net 伴奏提取 (时长: {mix_resampled.shape[1]/44100:.2f}s, 执行器: {sep.active_provider})...")
            
            with _jobs_lock:
                inst = sep.demix(mix_resampled)

            # 输出 16-bit 44.1kHz 立体声伴奏 WAV
            accomp_wav = wav_io.write_wav_bytes(inst, 44100, bitdepth=16, is_float=False)
            elapsed = time.time() - started
            log.info(f"伴奏分离完成，耗时: {elapsed:.2f}s, 输出伴奏大小: {len(accomp_wav)} 字节")

            self.send_response(200)
            self._cors()
            self.send_header('Content-Type', 'audio/wav')
            self.send_header('Content-Length', str(len(accomp_wav)))
            self.send_header('X-Inference-Time-Sec', f"{elapsed:.2f}")
            self.send_header('X-Active-Provider', sep.active_provider)
            self.end_headers()
            self.wfile.write(accomp_wav)

        except Exception as e:
            log.error(f"直接伴奏分离处理失败: {e}", exc_info=True)
            self._json({'ok': False, 'error': f"AI 分离异常: {str(e)}"}, 500)

    def _handle_async_upload(self):
        """异步任务提交接口（兼容 Web 界面排队）。"""
        try:
            body = self._read_body()
            ctype = self.headers.get('Content-Type', '')
            fields = parse_multipart(ctype, body)
            f = fields.get('file') or fields.get('audio')
            if not f or not f.get('content'):
                self._json({'ok': False, 'error': '未收到文件'}, 400)
                return

            filename = os.path.basename(f['filename'] or 'input.audio')
            task = _make_task()
            task['origin'] = os.path.splitext(filename)[0]
            ext = os.path.splitext(filename)[1] or '.wav'
            in_path = os.path.join(WORKDIR, task['id'] + ext)
            with open(in_path, 'wb') as out:
                out.write(f['content'])
            task['in_path'] = in_path
            task['is_video'] = video_io.is_video_file(in_path)
            task['status'] = 'pending'

            threading.Thread(target=_run_async_task, args=(task,), daemon=True).start()
            self._json({'ok': True, 'id': task['id']})
        except Exception as e:
            self._json({'ok': False, 'error': str(e)}, 500)


def _run_async_task(task):
    started = time.time()
    try:
        in_path = task['in_path']
        is_video = task['is_video']
        src_wav = os.path.join(WORKDIR, task['id'] + '_src.wav')
        if is_video:
            video_io.extract_audio(in_path, src_wav)
            mix_path = src_wav
        else:
            mix_path = in_path

        mix, sr = wav_io.decode_audio(mix_path)
        sep = get_separator()

        with _jobs_lock:
            inst = sep.demix(mix)

        voc = mix - inst
        base = os.path.join(WORKDIR, task['id'])
        inst_wav = base + '_inst.wav'
        voc_wav = base + '_voc.wav'
        wav_io.write_wav(inst_wav, inst, sr, bitdepth=16, is_float=False)
        wav_io.write_wav(voc_wav, voc, sr, bitdepth=16, is_float=False)

        if is_video and os.path.exists(src_wav):
            try: os.remove(src_wav)
            except OSError: pass

        with _tasks_lock:
            task['status'] = 'done'
            task['pct'] = 100
            task['inst_path'] = inst_wav
            task['voc_path'] = voc_wav
            task['elapsed'] = int(time.time() - started)
    except Exception as e:
        log.error(f"异步任务处理失败 tid={task['id']}: {e}", exc_info=True)
        with _tasks_lock:
            task['status'] = 'error'
            task['error'] = str(e)


def main():
    server = ThreadingHTTPServer(('0.0.0.0', PORT), UVRRequestHandler)
    log.info(f"==================================================")
    log.info(f" Home KTV - UVR-MDX-Net 伴奏分离 AI 微服务已启动")
    log.info(f" 监听端口: http://0.0.0.0:{PORT}")
    log.info(f" 模型文件: {MODEL_PATH}")
    try:
        sep = get_separator()
        log.info(f" 加载硬件加速执行器: {sep.active_provider}")
    except Exception as e:
        log.warn(f" 延迟加载模型: {e}")
    log.info(f"==================================================")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("正在停止微服务...")
        server.server_close()


if __name__ == '__main__':
    main()
