# -*- coding: utf-8 -*-
"""http_multipart 流式解析器的本地自测（不依赖 onnxruntime / 模型）。

覆盖点：
1. 多字段 + 文件部分，文件正文跨块边界（强制小 chunk，验证分隔符跨越读取块时不丢失）；
2. 二进制文件正文包含类似分隔符前缀的字节；
3. 内存占用不随上传体积增长（用超大正文验证只保留小缓冲）。
"""
import io
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from http_multipart import MultipartStreamReader, boundary_of, stream_body_to_file, MultipartError


def build_body(boundary, fields, files):
    """构造 multipart 请求体。fields: [(name, value)]; files: [(name, filename, bytes)]"""
    out = io.BytesIO()
    for name, value in fields:
        out.write(b'--' + boundary + b'\r\n')
        out.write(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
        out.write(value if isinstance(value, bytes) else value.encode())
        out.write(b'\r\n')
    for name, filename, content in files:
        out.write(b'--' + boundary + b'\r\n')
        out.write(f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode())
        out.write(b'Content-Type: application/octet-stream\r\n\r\n')
        out.write(content)
        out.write(b'\r\n')
    out.write(b'--' + boundary + b'--\r\n')
    return out.getvalue()


def parse(body, chunk_size):
    """模拟服务端：用小 chunk 流式解析，返回 (fields, files)。"""
    boundary = boundary_of('multipart/form-data; boundary=test-boundary-123')
    reader = MultipartStreamReader(io.BytesIO(body), boundary, len(body), chunk_size=chunk_size)
    fields, files = {}, {}
    while True:
        part = reader.next_part()
        if part is None:
            break
        if part.filename:
            with tempfile.NamedTemporaryFile(delete=False) as sink:
                reader.stream_part_to(sink)
                path = sink.name
            with open(path, 'rb') as handle:
                files[part.name] = (part.filename, handle.read())
            os.unlink(path)
        else:
            fields[part.name] = reader.read_field().decode('utf-8')
    return fields, files


def test_basic_and_small_chunks():
    payload = bytes(range(256)) * 400  # 102400 字节，跨多个 chunk
    body = build_body(b'test-boundary-123', [('mode', 'add_track')], [('audio', 'input.wav', payload)])
    for chunk_size in (7, 16, 4096, 65536):
        fields, files = parse(body, chunk_size)
        assert fields == {'mode': 'add_track'}, fields
        assert files['audio'][0] == 'input.wav'
        assert files['audio'][1] == payload, f'chunk={chunk_size} 正文不一致'
    print('PASS test_basic_and_small_chunks (chunk 7/16/4096/65536)')


def test_body_containing_delimiter_prefix():
    # 正文里包含分隔符前缀片段，验证"只在超过 marker-1 时才落盘"的逻辑没有误判
    tricky = b'--test-boundary' + b'\r\n--test-boundar' + b'\r\n\r\n--' + b'X' * 100
    body = build_body(b'test-boundary-123', [('note', 'hi')], [('file', 'x.bin', tricky)])
    fields, files = parse(body, 5)
    assert files['file'][1] == tricky, '分隔符前缀被误判为分隔符'
    assert fields == {'note': 'hi'}
    print('PASS test_body_containing_delimiter_prefix')


def test_multiple_files_and_utf8_filename():
    a = b'A' * 300
    b = b'B' * 300
    body = build_body(b'test-boundary-123', [],
                      [('audio', '中文名.wav', a), ('other', 'second.bin', b)])
    for chunk_size in (3, 1000):
        _, files = parse(body, chunk_size)
        assert files['audio'] == ('中文名.wav', a)
        assert files['other'] == ('second.bin', b)
    print('PASS test_multiple_files_and_utf8_filename')


def test_raw_body_streaming_and_limit():
    data = b'R' * 50_000
    with tempfile.NamedTemporaryFile(delete=False) as sink:
        path = sink.name
    try:
        written = stream_body_to_file(io.BytesIO(data), len(data), path, max_bytes=100_000)
        assert written == len(data)
        with open(path, 'rb') as handle:
            assert handle.read() == data
        raised = False
        try:
            stream_body_to_file(io.BytesIO(data), len(data), path, max_bytes=1000)
        except MultipartError:
            raised = True
        assert raised, '超出上限未抛错'
    finally:
        os.unlink(path)
    print('PASS test_raw_body_streaming_and_limit')


def test_truncated_body_is_rejected():
    body = build_body(b'test-boundary-123', [], [('file', 'x.bin', b'Z' * 5000)])
    truncated = body[:3000]  # 砍掉结束 boundary
    raised = False
    try:
        parse(truncated, 4096)
    except MultipartError:
        raised = True
    assert raised, '不完整报文应被拒绝'
    print('PASS test_truncated_body_is_rejected')


def test_incomplete_body_does_not_silently_succeed():
    # 直接验证 reader 在缺少结束 boundary 时抛错
    boundary = b'bnd'
    body = b'--bnd\r\nContent-Disposition: form-data; name="f"; filename="a"\r\n\r\n' + b'Q' * 400
    reader = MultipartStreamReader(io.BytesIO(body), boundary, len(body), chunk_size=8)
    part = reader.next_part()
    assert part is not None and part.filename == 'a'
    raised = False
    try:
        reader.stream_part_to(io.BytesIO())
    except MultipartError:
        raised = True
    assert raised, '缺少结束 boundary 时应报错'
    print('PASS test_incomplete_body_does_not_silently_succeed')


def test_memory_stays_bounded_for_large_upload():
    # 8MB 正文，用 16KB chunk 解析；解析器内部缓冲不应接近正文大小
    payload = os.urandom(8 * 1024 * 1024)
    body = build_body(b'test-boundary-123', [], [('audio', 'big.wav', payload)])
    boundary = boundary_of('multipart/form-data; boundary=test-boundary-123')
    reader = MultipartStreamReader(io.BytesIO(body), boundary, len(body), chunk_size=16 * 1024)
    received = []
    total = 0
    while True:
        part = reader.next_part()
        if part is None:
            break
        sink = io.BytesIO()
        total += reader.stream_part_to(sink)
        received.append(sink.getvalue())
    assert total == len(payload)
    assert received[0] == payload
    buffered = max(len(reader._buffer), 0)
    assert buffered < 200_000, f'内部缓冲过大: {buffered}'
    print(f'PASS test_memory_stays_bounded_for_large_upload (内部缓冲峰值 {buffered} 字节 / 正文 8MB)')


if __name__ == '__main__':
    test_basic_and_small_chunks()
    test_body_containing_delimiter_prefix()
    test_multiple_files_and_utf8_filename()
    test_raw_body_streaming_and_limit()
    test_truncated_body_is_rejected()
    test_incomplete_body_does_not_silently_succeed()
    test_memory_stays_bounded_for_large_upload()
    print('\n全部 http_multipart 自测通过')
