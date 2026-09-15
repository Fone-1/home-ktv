# -*- coding: utf-8 -*-
"""UVR-MDX-NET-Inst_HQ_3 AI 伴奏分离独立微服务。

阶段三任务 3.4 后的服务形态：

* 上传体**流式**落盘（``http_multipart``），不再把整个 HTTP body 读进内存；
* 异步任务走**有界队列 + 固定 worker**（``job_queue``），队列满返回 503 明确错误；
* worker 数按实际 GPU/CPU 能力自动推导，可用 ``UVR_WORKERS`` 覆盖；
* 任务状态持久化到 ``jobs/tasks.json``，服务重启后把遗留任务明确标记为中断失败；
* 输入/中间/输出音频按保留期限自动清理，且不会误删仍在排队的任务文件；
* 模型线程安全仍由 ``_jobs_lock`` 保护（保持原设计，避免并发推理打爆显存）。

仍然提供同步流式接口 ``/api/separate``（后端双轨转换直接调用）与异步接口 ``/api/upload``（Web 控制台）。
"""
import logging
import os
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import http_multipart
import job_queue
from http_multipart import MultipartError, MultipartStreamReader, boundary_of, stream_body_to_file
from job_queue import BoundedJobQueue, JobFileCleaner, QueueFullError, TaskStore

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
log = logging.getLogger("uvr_service")

_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

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

#: 异步任务队列容量（超出即拒绝，避免无限堆积）
QUEUE_SIZE = int(os.environ.get('UVR_QUEUE_SIZE', '8'))
#: 作业文件保留期限（小时）与清理周期（秒）
RETENTION_HOURS = float(os.environ.get('UVR_RETENTION_HOURS', '6'))
CLEANUP_INTERVAL_SEC = int(os.environ.get('UVR_CLEANUP_INTERVAL_SEC', '1800'))
#: 同步接口并发上限（超过返回 503，避免请求堆积把显存打爆）
SYNC_CONCURRENCY = int(os.environ.get('UVR_SYNC_CONCURRENCY', '1'))

# 全局模型实例与推理锁（确保多并发时有序执行，避免显存/内存溢出）
_separator_lock = threading.Lock()
_separator = None
#: 同步分离接口的信号量：Model 只能串行推理，多余请求快速失败而不是无限等待
_sync_slots = threading.BoundedSemaphore(max(1, SYNC_CONCURRENCY))

# ---------------- 任务管理 ----------------
_tasks_lock = threading.Lock()
_tasks = {}
_jobs_lock = threading.Lock()
_task_store = TaskStore(os.path.join(WORKDIR, 'tasks.json'), lock=threading.Lock())
_job_queue = None
_cleaner = None


def get_separator():
    """延迟加载模型：缺少 onnxruntime/模型时也能启动 HTTP 层并给出明确错误。

    推理本身必须串行（同一个 ONNX 会话 + 显存），因此由 ``_jobs_lock`` 保护。
    """
    global _separator
    with _separator_lock:
        if _separator is None:
            if not MODEL_PATH or not os.path.exists(MODEL_PATH):
                raise FileNotFoundError(f"未找到 ONNX 伴奏模型文件，请检查路径: {MODEL_PATH}")
            from separate_mdx import MDXSeparator  # 延迟导入：无需模型依赖即可启动服务
            _separator = MDXSeparator(MODEL_PATH)
        return _separator


def model_ready():
    """返回 (是否就绪, 说明)。用于健康检查与启动日志。"""
    try:
        sep = get_separator()
        return True, str(sep.active_provider)
    except Exception as failure:  # 模型/依赖缺失不应让服务无法启动
        return False, str(failure)


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
    _persist_tasks()
    return task


def _persist_tasks():
    """把任务快照写入磁盘；仅在状态变化时调用，避免高频写盘。"""
    with _tasks_lock:
        snapshot = dict(_tasks)
    try:
        _task_store.save(snapshot)
    except OSError:
        log.warning('任务状态持久化失败（不影响本次任务执行）', exc_info=True)


def _update_task(task_id, **fields):
    """更新任务字段；status 变化时同步落盘。"""
    with _tasks_lock:
        task = _tasks.get(task_id)
        if task is None:
            return None
        task.update(fields)
    if 'status' in fields:
        _persist_tasks()
    return task


def _protected_job_paths():
    """仍在排队/执行的任务所拥有的文件，清理时应跳过。"""
    paths = set()
    with _tasks_lock:
        for task in _tasks.values():
            if task.get('status') in job_queue.INTERRUPTIBLE_STATES:
                for key in ('in_path', 'inst_path', 'voc_path', 'video_path', 'video_preview_path'):
                    value = task.get(key)
                    if value:
                        paths.add(value)
    return paths


class UVRRequestHandler(BaseHTTPRequestHandler):
    server_version = "HomeKTV-UVR-AI/1.1"

    def _cors(self):
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Authorization, Range')

    def _json(self, data, code=200):
        import json as _json
        body = _json.dumps(data, ensure_ascii=False).encode('utf-8')
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

    def _content_length(self):
        """取请求体长度；分块传输在流式解析下无法可靠处理，明确拒绝。"""
        if (self.headers.get('Transfer-Encoding') or '').lower().strip() == 'chunked':
            self._json({'ok': False, 'error': '不支持 chunked 传输，请提供 Content-Length'}, 411)
            return None
        raw = self.headers.get('Content-Length')
        try:
            length = int(raw or 0)
        except ValueError:
            self._json({'ok': False, 'error': 'Content-Length 非法'}, 400)
            return None
        if length <= 0:
            self._json({'ok': False, 'error': '请求体为空'}, 400)
            return None
        if length > MAX_UPLOAD:
            self._json({'ok': False, 'error': f'上传内容超过限制（>{MAX_UPLOAD // 1024 // 1024}MB）'}, 413)
            return None
        return length

    def _save_uploaded_audio(self, dest_path, content_length):
        """把请求中的音频流式写入 dest_path；支持 multipart（取 audio/file 字段）与裸 body。"""
        content_type = self.headers.get('Content-Type', '')
        if 'multipart/form-data' in content_type.lower():
            reader = MultipartStreamReader(self.rfile, boundary_of(content_type), content_length)
            while True:
                part = reader.next_part()
                if part is None:
                    break
                if part.filename and part.name in ('audio', 'file'):
                    with open(dest_path, 'wb') as sink:
                        reader.stream_part_to(sink, max_bytes=MAX_UPLOAD)
                    return True
                # 其余分段（含未知字段）同样需要排空，否则流位置会错乱
                reader.stream_part_to(_NullSink())
            return False
        stream_body_to_file(self.rfile, content_length, dest_path, max_bytes=MAX_UPLOAD)
        return True

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_GET(self):
        url = urlparse(self.path)
        p = url.path
        if p in ('/api/health', '/health'):
            ready, provider = model_ready()
            device = "GPU" if ("CUDA" in provider or "DirectML" in provider) else "CPU"
            payload = {
                "status": "UP" if ready else "DEGRADED",
                "service": "UVR-MDX-NET-Inst_HQ_3",
                "provider": provider,
                "device": device,
                "modelReady": ready,
                "queue": {
                    "depth": _job_queue.depth if _job_queue else 0,
                    "capacity": _job_queue.capacity if _job_queue else 0,
                    "workers": _job_queue.worker_count if _job_queue else 0,
                },
                "retentionHours": RETENTION_HOURS,
            }
            self._json(payload)
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
        elif p == '/api/tasks':
            with _tasks_lock:
                items = sorted(_tasks.values(), key=lambda item: item.get('created', 0), reverse=True)
            self._json({'ok': True, 'total': len(items), 'tasks': items[:50],
                        'queueDepth': _job_queue.depth if _job_queue else 0})
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
        """同步分离接口：流式接收音频 → AI 推理 → 流式返回 16bit 44.1kHz 伴奏 WAV。

        并发超出 ``UVR_SYNC_CONCURRENCY`` 时立即返回 503，避免请求在模型锁上无限排队。
        """
        content_length = self._content_length()
        if content_length is None:
            return
        if not _sync_slots.acquire(blocking=False):
            self.send_response(503)
            self._cors()
            self.send_header('Retry-After', '10')
            self._json_body({'ok': False, 'error': 'AI 分离服务繁忙，请稍后重试'})
            return

        started = time.time()
        input_path = os.path.join(WORKDIR, f'sync-{uuid.uuid4().hex[:12]}.upload')
        try:
            if not self._save_uploaded_audio(input_path, content_length):
                self._json({'ok': False, 'error': '未包含有效的 audio/file 字段'}, 400)
                return

            mix_path = input_path
            extracted = None
            if video_io.is_video_file(input_path):
                extracted = input_path + '.src.wav'
                video_io.extract_audio(input_path, extracted)
                mix_path = extracted

            mix, sr = _decode_audio(mix_path)
            mix_resampled = wav_io.resample(mix, sr, 44100)

            sep = get_separator()
            log.info("开始执行 UVR-MDX-Net 伴奏提取 (时长: %.2fs, 执行器: %s)...",
                     mix_resampled.shape[1] / 44100, sep.active_provider)

            with _jobs_lock:
                inst = sep.demix(mix_resampled)

            accomp_wav = wav_io.write_wav_bytes(inst, 44100, bitdepth=16, is_float=False)
            elapsed = time.time() - started
            log.info("伴奏分离完成，耗时: %.2fs, 输出伴奏大小: %d 字节", elapsed, len(accomp_wav))

            if extracted and os.path.exists(extracted):
                try: os.remove(extracted)
                except OSError: pass

            self.send_response(200)
            self._cors()
            self.send_header('Content-Type', 'audio/wav')
            self.send_header('Content-Length', str(len(accomp_wav)))
            self.send_header('X-Inference-Time-Sec', f"{elapsed:.2f}")
            self.send_header('X-Active-Provider', sep.active_provider)
            self.end_headers()
            # 分块写出，避免大响应一次性进入内核缓冲
            view = memoryview(accomp_wav)
            for offset in range(0, len(view), 65536):
                self.wfile.write(view[offset:offset + 65536])
        except MultipartError as failure:
            log.warning("上传报文非法: %s", failure)
            self._json({'ok': False, 'error': f'上传内容不合法: {failure}'}, 400)
        except Exception as e:
            log.error("直接伴奏分离处理失败: %s", e, exc_info=True)
            self._json({'ok': False, 'error': f"AI 分离异常: {_failure_reason(e)}"}, 500)
        finally:
            _sync_slots.release()
            try: os.remove(input_path)
            except OSError: pass

    def _handle_async_upload(self):
        """异步任务提交：流式落盘 → 有界队列排队；队列满返回 503。"""
        content_length = self._content_length()
        if content_length is None:
            return
        content_type = self.headers.get('Content-Type', '')
        if 'multipart/form-data' not in content_type.lower():
            self._json({'ok': False, 'error': '请使用 multipart/form-data 上传'}, 400)
            return
        if _job_queue is None:
            self._json({'ok': False, 'error': '任务队列未初始化'}, 503)
            return

        task = _make_task()
        in_path = None
        try:
            reader = MultipartStreamReader(self.rfile, boundary_of(content_type), content_length)
            filename = None
            while True:
                part = reader.next_part()
                if part is None:
                    break
                if part.filename and part.name in ('file', 'audio') and in_path is None:
                    filename = os.path.basename(part.filename or 'input.audio')
                    ext = os.path.splitext(filename)[1] or '.wav'
                    in_path = os.path.join(WORKDIR, task['id'] + ext)
                    with open(in_path, 'wb') as sink:
                        reader.stream_part_to(sink, max_bytes=MAX_UPLOAD)
                    _update_task(task['id'], in_path=in_path)
                else:
                    reader.stream_part_to(_NullSink())
        except MultipartError as failure:
            log.warning("异步上传报文非法: %s", failure)
            _update_task(task['id'], status='error', phase='error', error=f'上传内容不合法: {failure}')
            self._json({'ok': False, 'error': f'上传内容不合法: {failure}'}, 400)
            return
        except Exception as e:
            _update_task(task['id'], status='error', phase='error', error=_failure_reason(e))
            self._json({'ok': False, 'error': _failure_reason(e)}, 500)
            return

        if not in_path:
            _update_task(task['id'], status='error', phase='error', error='未收到文件')
            self._json({'ok': False, 'error': '未收到文件'}, 400)
            return

        task['origin'] = os.path.splitext(os.path.basename(in_path))[0]
        task['is_video'] = video_io.is_video_file(in_path)
        try:
            _job_queue.submit(task['id'])
        except QueueFullError as failure:
            _update_task(task['id'], status='error', phase='error', error=str(failure))
            self.send_response(503)
            self._cors()
            self.send_header('Retry-After', '10')
            self._json_body({'ok': False, 'error': str(failure),
                             'queueDepth': _job_queue.depth, 'queueCapacity': _job_queue.capacity})
            return
        _update_task(task['id'], status='pending', phase='queued')
        self._json({'ok': True, 'id': task['id'], 'queueDepth': _job_queue.depth})

    def _json_body(self, data):
        """在已发送响应头之后补写 JSON 正文。"""
        import json as _json
        body = _json.dumps(data, ensure_ascii=False).encode('utf-8')
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class _NullSink:
    """丢弃写入的 sink，用于排空不关心的 multipart 分段。"""

    def write(self, data):
        return len(data)


def _failure_reason(failure):
    """把异常转成对用户可见的失败原因。

    有些底层库（例如 wav_io 的裸 assert）抛出的异常消息为空字符串，
    直接 str() 会得到空原因，界面上就成了"失败了但不知道为什么"。
    这里统一回退到异常类型名。
    """
    message = str(failure).strip()
    return message if message else failure.__class__.__name__


def _decode_audio(path):
    """解码音频并给出可读的错误信息（区分"不是有效音频"和"读取失败"）。"""
    try:
        return wav_io.decode_audio(path)
    except Exception as failure:
        raise ValueError(f'音频解码失败（文件不是有效的音频/视频，或编码不受支持）：{_failure_reason(failure)}')


def _run_async_task(task_id):
    """worker 执行体：抽取音频 → 推理 → 落盘伴奏/人声 → 更新任务状态。"""
    started = time.time()
    with _tasks_lock:
        task = _tasks.get(task_id)
    if task is None:
        return
    in_path = task.get('in_path')
    if not in_path or not os.path.exists(in_path):
        _update_task(task_id, status='error', phase='error', error='输入文件丢失（可能已过期清理）')
        return

    _update_task(task_id, status='pending', phase='extracting', pct=5)
    src_wav = None
    try:
        is_video = task.get('is_video')
        mix_path = in_path
        if is_video:
            src_wav = os.path.join(WORKDIR, task_id + '_src.wav')
            video_io.extract_audio(in_path, src_wav)
            mix_path = src_wav

        mix, sr = _decode_audio(mix_path)
        sep = get_separator()

        _update_task(task_id, status='pending', phase='separating', pct=30)
        with _jobs_lock:
            inst = sep.demix(mix)

        voc = mix - inst
        base = os.path.join(WORKDIR, task_id)
        inst_wav = base + '_inst.wav'
        voc_wav = base + '_voc.wav'
        wav_io.write_wav(inst_wav, inst, sr, bitdepth=16, is_float=False)
        wav_io.write_wav(voc_wav, voc, sr, bitdepth=16, is_float=False)

        if src_wav and os.path.exists(src_wav):
            try: os.remove(src_wav)
            except OSError: pass

        _update_task(task_id, status='done', phase='done', pct=100,
                     inst_path=inst_wav, voc_path=voc_wav,
                     elapsed=int(time.time() - started))
    except Exception as e:
        log.error("异步任务处理失败 tid=%s: %s", task_id, e, exc_info=True)
        _update_task(task_id, status='error', phase='error', error=_failure_reason(e),
                     elapsed=int(time.time() - started))
        if src_wav and os.path.exists(src_wav):
            try: os.remove(src_wav)
            except OSError: pass


def _restore_tasks():
    """启动时恢复任务快照，并把无法继续的遗留任务标记为中断失败。"""
    restored = _task_store.load()
    marked = _task_store.mark_interrupted(restored)
    with _tasks_lock:
        _tasks.update(restored)
    if marked:
        log.warning("已把 %d 个服务重启前遗留的 AI 任务标记为中断失败", marked)
        _persist_tasks()
    return len(restored)


def main():
    global _job_queue, _cleaner

    restored = _restore_tasks()
    ready, provider = model_ready()
    workers = job_queue.resolve_worker_count(os.environ.get('UVR_WORKERS'),
                                             gpu_available=ready and ("CUDA" in provider or "DirectML" in provider))
    _job_queue = BoundedJobQueue(workers=workers, maxsize=QUEUE_SIZE, handler=_run_async_task)
    _job_queue.start()

    _cleaner = JobFileCleaner(WORKDIR, retention_seconds=int(RETENTION_HOURS * 3600),
                              interval_seconds=CLEANUP_INTERVAL_SEC,
                              protected_paths=_protected_job_paths)
    _cleaner.start()

    server = ThreadingHTTPServer(('0.0.0.0', PORT), UVRRequestHandler)
    log.info("==================================================")
    log.info(" Home KTV - UVR-MDX-Net 伴奏分离 AI 微服务已启动")
    log.info(" 监听端口: http://0.0.0.0:%s", PORT)
    log.info(" 模型文件: %s", MODEL_PATH)
    log.info(" 硬件执行器: %s", provider if ready else f"未就绪（{provider}）")
    log.info(" worker 数: %s，异步队列容量: %s，同步并发上限: %s", workers, QUEUE_SIZE, SYNC_CONCURRENCY)
    log.info(" 作业文件保留: %s 小时，清理周期: %s 秒", RETENTION_HOURS, CLEANUP_INTERVAL_SEC)
    log.info(" 恢复历史任务: %s 条", restored)
    log.info("==================================================")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("正在停止微服务...")
        _cleaner.stop()
        _job_queue.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()
