# -*- coding: utf-8 -*-
"""有界任务队列、任务状态持久化与作业文件清理（阶段三任务 3.4）。

三个问题：
1. 原先每个上传请求都 ``threading.Thread(...).start()``，并发不受控，显存/内存会被打爆
   → :class:`BoundedJobQueue` 提供固定 worker 数 + 有界队列，队列满时抛 :class:`QueueFullError`；
2. 任务只在内存里，服务重启后用户看到的是永远"排队中"的僵尸任务
   → :class:`TaskStore` 把任务快照原子写入 JSON，重启时可明确标记中断；
3. 输入/中间/输出 WAV 永久堆积撑满磁盘
   → :class:`JobFileCleaner` 按保留期限回收，且不碰仍在排队的任务文件。

仅依赖标准库，便于脱离模型环境单独测试。
"""
import json
import os
import queue
import threading
import time

STATE_QUEUED = 'queued'
STATE_PENDING = 'pending'
STATE_DONE = 'done'
STATE_ERROR = 'error'

#: 服务重启后无法继续执行的状态
INTERRUPTIBLE_STATES = (STATE_QUEUED, STATE_PENDING, 'running')

DEFAULT_MAX_WORKERS = 8


class QueueFullError(Exception):
    """任务队列已满，调用方应返回明确的限流错误。"""


def resolve_worker_count(env_value, cpu_count=None, gpu_available=False, max_workers=DEFAULT_MAX_WORKERS):
    """决定 worker 数量。

    GPU 环境下模型推理本身需要独占显存，多 worker 只会互相等待，因此固定 1 个；
    CPU 环境下 worker 主要让音频解码/抽取与推理重叠，取核数一半并限制在 4 以内。
    可用 ``UVR_WORKERS`` 覆盖。
    """
    if env_value is not None and str(env_value).strip():
        try:
            requested = int(str(env_value).strip())
        except ValueError:
            requested = 0
        if requested >= 1:
            return min(requested, max_workers)
    if gpu_available:
        return 1
    detected = cpu_count or os.cpu_count() or 1
    return max(1, min(4, detected // 2))


class TaskStore:
    """任务状态的磁盘快照。

    只保存可 JSON 序列化的字段；写入采用「临时文件 + os.replace」保证原子性，
    避免服务在写入过程中被杀导致任务文件损坏。
    """

    def __init__(self, path, lock=None):
        self._path = path
        self._lock = lock or threading.Lock()

    def load(self):
        """读取上次的任务快照；文件缺失或损坏时返回空字典而不是让服务起不来。"""
        if not os.path.exists(self._path):
            return {}
        try:
            with open(self._path, 'r', encoding='utf-8') as handle:
                data = json.load(handle)
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def save(self, tasks):
        """原子写入任务快照。"""
        snapshot = {}
        for task_id, task in tasks.items():
            snapshot[task_id] = _serializable(task)
        payload = json.dumps(snapshot, ensure_ascii=False, indent=1)
        with self._lock:
            directory = os.path.dirname(self._path) or '.'
            os.makedirs(directory, exist_ok=True)
            tmp_path = self._path + '.tmp'
            with open(tmp_path, 'w', encoding='utf-8') as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_path, self._path)

    def mark_interrupted(self, tasks, reason='服务重启导致任务中断，请重新提交'):
        """把重启前遗留的排队/执行中任务标记为失败，避免界面出现永久"排队中"。

        返回被标记的数量。
        """
        marked = 0
        for task in tasks.values():
            if task.get('status') in INTERRUPTIBLE_STATES:
                task['status'] = STATE_ERROR
                task['phase'] = 'error'
                task['error'] = reason
                marked += 1
        return marked


def _serializable(task):
    """挑出可 JSON 序列化的字段，避免把 bytes/对象写进任务文件。"""
    out = {}
    for key, value in task.items():
        if isinstance(value, (str, int, float, bool)) or value is None:
            out[key] = value
        elif isinstance(value, (list, tuple)):
            out[key] = [item for item in value if isinstance(item, (str, int, float, bool, type(None)))]
    return out


class BoundedJobQueue:
    """固定 worker 数的有界任务队列。

    ``submit`` 在队列已满时立即抛 :class:`QueueFullError`，让 HTTP 层返回 503 + 明确错误，
    而不是无限接收任务把服务拖死。
    """

    def __init__(self, workers, maxsize, handler, name_prefix='uvr-worker'):
        self._workers = max(1, int(workers))
        self._queue = queue.Queue(maxsize=max(1, int(maxsize)))
        self._handler = handler
        self._threads = []
        self._name_prefix = name_prefix
        self._started = False

    @property
    def worker_count(self):
        return self._workers

    @property
    def capacity(self):
        return self._queue.maxsize

    @property
    def depth(self):
        """当前排队（尚未被 worker 取走）的任务数。"""
        return self._queue.qsize()

    def start(self):
        if self._started:
            return
        self._started = True
        for index in range(self._workers):
            thread = threading.Thread(target=self._work, name=f'{self._name_prefix}-{index}', daemon=True)
            thread.start()
            self._threads.append(thread)

    def submit(self, task_id):
        """入队；队列满时抛 QueueFullError。"""
        try:
            self._queue.put_nowait(task_id)
        except queue.Full:
            raise QueueFullError(
                f'任务队列已满（容量 {self._queue.maxsize}，worker {self._workers}），请稍后重试')

    def shutdown(self):
        self._started = False
        for _ in self._threads:
            try:
                self._queue.put_nowait(None)
            except queue.Full:
                pass

    def _work(self):
        while True:
            task_id = self._queue.get()
            try:
                if task_id is None:
                    return
                self._handler(task_id)
            except Exception:  # 单个任务异常不能杀死 worker
                import logging
                logging.getLogger('uvr_service').exception('任务执行异常 tid=%s', task_id)
            finally:
                self._queue.task_done()


class JobFileCleaner(threading.Thread):
    """按保留期限回收作业文件的后台线程。

    ``protected_paths`` 是一个可调用对象，返回当前仍在排队/执行的任务所拥有的路径集合，
    这些文件即使过期也不会被删除。
    """

    def __init__(self, workdir, retention_seconds, interval_seconds=1800, protected_paths=None):
        super().__init__(name='uvr-cleaner', daemon=True)
        self._workdir = workdir
        self._retention = max(60, int(retention_seconds))
        self._interval = max(60, int(interval_seconds))
        self._protected_paths = protected_paths or (lambda: set())
        self._stop = threading.Event()

    def stop(self):
        self._stop.set()

    def run(self):
        while not self._stop.wait(self._interval):
            try:
                self.clean_once()
            except Exception:
                import logging
                logging.getLogger('uvr_service').exception('作业文件清理失败')

    def clean_once(self):
        """执行一轮清理，返回删除的文件数。"""
        if not os.path.isdir(self._workdir):
            return 0
        cutoff = time.time() - self._retention
        protected = {os.path.abspath(path) for path in self._protected_paths()}
        removed = 0
        for name in os.listdir(self._workdir):
            path = os.path.join(self._workdir, name)
            if not os.path.isfile(path):
                continue
            if os.path.abspath(path) in protected:
                continue
            try:
                if os.path.getmtime(path) > cutoff:
                    continue
                os.remove(path)
                removed += 1
            except OSError:
                continue
        return removed
