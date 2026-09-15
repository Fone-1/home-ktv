# -*- coding: utf-8 -*-
"""流式 multipart/form-data 解析器（阶段三任务 3.4）。

原先的实现把整个 HTTP body 一次性读进内存再切分，上传几百 MB 视频时会占用等量内存甚至 OOM。
本模块改为从输入流分块读取：

* 文件部分通过 ``stream_part_to()`` 直接分块写入磁盘文件；
* 只在内存中保留“可能被截断的分隔符前缀”这一小段数据；
* 普通表单字段（小文本）仍读入内存，但受 ``MAX_FIELD_BYTES`` 限制。

只依赖标准库，便于脱离模型环境单独测试。
"""
import re

DEFAULT_CHUNK_SIZE = 64 * 1024
DEFAULT_MAX_HEADER_BYTES = 16 * 1024
DEFAULT_MAX_FIELD_BYTES = 64 * 1024


class MultipartError(ValueError):
    """multipart 报文格式非法或不完整。"""


def boundary_of(content_type):
    """从 Content-Type 中提取 boundary，缺失时抛 MultipartError。"""
    if not content_type:
        raise MultipartError('缺少 Content-Type')
    match = re.search(r'boundary=(?:"([^"]+)"|([^;]+))', content_type, re.I)
    if not match:
        raise MultipartError('缺少 multipart boundary')
    return (match.group(1) or match.group(2)).strip().encode('latin-1')


def decode_filename(line):
    """解析 Content-Disposition 中的文件名，兼容 RFC 5987 的 filename* 与 latin-1 中文名。"""
    match = re.search(r"filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;\r\n]*)", line, re.I)
    if match:
        from urllib.parse import unquote
        try:
            return unquote(match.group(1).strip())
        except Exception:
            return match.group(1).strip()
    match = re.search(r'filename\s*=\s*"([^"]*)"', line, re.I)
    if not match:
        return ''
    raw = match.group(1).replace('\\', '/').rsplit('/', 1)[-1]
    try:
        return raw.encode('latin-1').decode('utf-8')
    except Exception:
        return raw


def name_of(line):
    """解析 Content-Disposition 中的字段名。"""
    match = re.search(r'name="([^"]*)"', line)
    return match.group(1) if match else None


class MultipartStreamReader:
    """把 multipart body 作为流逐步解析。

    典型用法::

        reader = MultipartStreamReader(rfile, boundary, content_length)
        while True:
            part = reader.next_part()
            if part is None:
                break
            if part.filename:
                with open(path, 'wb') as sink:
                    reader.stream_part_to(sink)
            else:
                value = reader.read_field()
    """

    def __init__(self, stream, boundary, content_length,
                 chunk_size=DEFAULT_CHUNK_SIZE,
                 max_header_bytes=DEFAULT_MAX_HEADER_BYTES):
        self._stream = stream
        self._delim = b'--' + (boundary if isinstance(boundary, bytes) else boundary.encode('latin-1'))
        self._remaining = max(0, int(content_length or 0))
        self._chunk_size = chunk_size
        self._max_header_bytes = max_header_bytes
        self._buffer = b''
        self._finished = False
        self._awaiting_first = True

    # ------------------------------------------------------------ 内部读取

    def _pull(self):
        """再读一块数据；返回是否读到内容。"""
        if self._remaining <= 0:
            return False
        data = self._stream.read(min(self._chunk_size, self._remaining))
        if not data:
            self._remaining = 0
            return False
        self._remaining -= len(data)
        self._buffer += data
        return True

    def _ensure(self, size):
        """确保缓冲区至少有 size 字节，或已到流末尾。"""
        while len(self._buffer) < size and self._pull():
            pass
        return len(self._buffer) >= size

    def _discard_until(self, marker, max_bytes=None):
        """消费掉直到（含）marker 的内容。"""
        while True:
            index = self._buffer.find(marker)
            if index >= 0:
                self._buffer = self._buffer[index + len(marker):]
                return
            if max_bytes is not None and len(self._buffer) > max_bytes:
                raise MultipartError('multipart 头部长度超出上限')
            if not self._pull():
                self._buffer = b''
                raise MultipartError('multipart 请求不完整')

    def _read_until(self, marker, max_bytes=None):
        """读取直到 marker（marker 被消费，不作为结果返回）。"""
        while True:
            index = self._buffer.find(marker)
            if index >= 0:
                result = self._buffer[:index]
                self._buffer = self._buffer[index + len(marker):]
                return result
            if max_bytes is not None and len(self._buffer) > max_bytes:
                raise MultipartError('multipart 字段长度超出上限')
            if not self._pull():
                result = self._buffer
                self._buffer = b''
                return result

    # ------------------------------------------------------------ 对外接口

    def next_part(self):
        """前进到下一个 part 的头部。

        返回 ``Part(name, filename, headers)``；没有更多 part 时返回 ``None``。
        """
        if self._finished:
            return None
        if self._awaiting_first:
            # 跳过前导内容，定位第一个 boundary
            if not self._ensure(len(self._delim)):
                self._finished = True
                return None
            index = self._buffer.find(self._delim)
            if index < 0:
                self._finished = True
                return None
            self._buffer = self._buffer[index + len(self._delim):]
            self._awaiting_first = False
        # boundary 之后若是 "--" 则是结束标记
        if not self._ensure(2):
            self._finished = True
            return None
        if self._buffer.startswith(b'--'):
            self._finished = True
            return None
        # 跳过 boundary 行剩余（含可能的传输填充空白）到 CRLF
        self._discard_until(b'\r\n', max_bytes=64)
        header_blob = self._read_until(b'\r\n\r\n', max_bytes=self._max_header_bytes).decode('latin-1')
        name = filename = None
        for line in header_blob.split('\r\n'):
            if line.lower().startswith('content-disposition'):
                name = name_of(line)
                filename = decode_filename(line) or None
        return Part(name=name, filename=filename, headers=header_blob)

    def read_field(self, max_bytes=DEFAULT_MAX_FIELD_BYTES):
        """把当前 part 作为小文本字段读入内存。"""
        buffer = bytearray()
        self.stream_part_to(buffer, max_bytes=max_bytes)
        return bytes(buffer)

    def stream_part_to(self, sink, max_bytes=None):
        """把当前 part 的正文分块写入 sink，返回写入字节数。

        ``sink`` 可以是 ``bytearray``（内存字段）或任何带 ``write()`` 的对象（文件）。
        实现要点：只有当缓冲区长度超过 ``len(marker) - 1`` 时才落盘，
        保证被分块截断的分隔符不会被错误写入。
        """
        marker = b'\r\n' + self._delim
        written = 0
        keep = len(marker) - 1
        while True:
            index = self._buffer.find(marker)
            if index >= 0:
                data = self._buffer[:index]
                self._buffer = self._buffer[index + len(marker):]
                written += _write(sink, data)
                return written
            if len(self._buffer) > keep:
                data = self._buffer[:-keep]
                self._buffer = self._buffer[-keep:]
                written += _write(sink, data)
            if max_bytes is not None and written > max_bytes:
                raise MultipartError(f'上传内容超过限制（>{max_bytes} 字节）')
            if not self._pull():
                # 没有结束 boundary：报文不完整，不能当作正常结束
                written += _write(sink, self._buffer)
                self._buffer = b''
                raise MultipartError('multipart 请求不完整（缺少结束 boundary）')


class Part:
    """一个 multipart 分段。"""

    __slots__ = ('name', 'filename', 'headers')

    def __init__(self, name, filename, headers):
        self.name = name
        self.filename = filename
        self.headers = headers

    def __repr__(self):
        return f'Part(name={self.name!r}, filename={self.filename!r})'


def _write(sink, data):
    if not data:
        return 0
    if isinstance(sink, bytearray):
        sink.extend(data)
    else:
        sink.write(data)
    return len(data)


def stream_body_to_file(stream, content_length, dest_path, max_bytes=None,
                        chunk_size=DEFAULT_CHUNK_SIZE):
    """把原始（非 multipart）请求体流式写入文件，返回写入字节数。

    超过 ``max_bytes`` 时抛 MultipartError，避免磁盘被超大请求写满。
    """
    total = 0
    with open(dest_path, 'wb') as sink:
        while True:
            chunk = stream.read(chunk_size)
            if not chunk:
                break
            total += len(chunk)
            if max_bytes is not None and total > max_bytes:
                raise MultipartError(f'上传内容超过限制（>{max_bytes} 字节）')
            sink.write(chunk)
    return total
