# -*- coding: utf-8 -*-
"""
WAV 读写与音频重采样工具模块（纯 NumPy + 标准库实现，零沉重第三方依赖）。
"""
import io
import os
import struct
import subprocess
import numpy as np

SR_TARGET = 44100


def _find_ffmpeg():
    """优先使用本地 runtime 路径中的 ffmpeg，其次探测系统 PATH。"""
    here = os.path.dirname(os.path.abspath(__file__))
    for cand in (
        os.path.join(here, 'runtime', 'ffmpeg.exe'),
        os.path.join(here, 'ffmpeg.exe'),
        os.path.join(here, 'runtime', 'ffmpeg'),
        os.path.join(here, 'ffmpeg')
    ):
        if os.path.exists(cand):
            return cand
    return 'ffmpeg'


def _read_wav_header(fp):
    """解析标准/可扩展 WAV 文件头，返回 (fmt_body, data_bytes)。"""
    def rd(n):
        b = fp.read(n)
        if len(b) != n:
            raise IOError("WAV 文件不完整")
        return b

    assert rd(4) == b'RIFF'
    rd(4)  # size
    assert rd(4) == b'WAVE'
    fmt = None
    data = None
    while True:
        chunk = rd(4)
        if len(chunk) < 4:
            break
        size = struct.unpack('<I', rd(4))[0]
        body = rd(size) if chunk != b'data' or size % 2 == 0 else rd(size + 1)[:-1]
        if chunk == b'fmt ':
            fmt = body
        elif chunk == b'data':
            data = body
            break
    if fmt is None or data is None:
        raise IOError("WAV 缺少必需的 fmt 或 data 块")
    return fmt, data


def _decode_fmt(fmt):
    code = struct.unpack('<H', fmt[0:2])[0]
    ch = struct.unpack('<H', fmt[2:4])[0]
    sr = struct.unpack('<I', fmt[4:8])[0]
    bits = struct.unpack('<H', fmt[14:16])[0]
    if code == 0xFFFE and len(fmt) >= 40:  # extensible
        sub = fmt[24:28]
        if sub == b'\x01\x00\x00\x00':
            code = 1
        elif sub == b'\x03\x00\x00\x00':
            code = 3
    return code, ch, sr, bits


def read_wav_stream(stream):
    """从文件对象或 BytesIO 读取 WAV，返回 (float32 [2, T], sr)。"""
    fmt, data = _read_wav_header(stream)
    code, ch, sr, bits = _decode_fmt(fmt)
    raw = np.frombuffer(data, dtype=np.uint8)

    if code == 3:  # IEEE float
        if bits == 32:
            a = raw.view(np.float32)
        elif bits == 64:
            a = raw.view(np.float64).astype(np.float32)
        else:
            raise IOError(f"不支持的 float 位深: {bits}")
    elif code == 1:  # PCM int
        if bits == 8:
            a = raw.astype(np.float32) / 128.0 - 1.0
        elif bits == 16:
            a = raw.view(np.int16).astype(np.float32) / 32768.0
        elif bits == 24:
            a = (raw[0::3].astype(np.int32) * 65536
                 + raw[1::3].astype(np.int32) * 256
                 + raw[2::3].astype(np.int32))
            a = a.astype(np.float32)
            a = np.where(a > 8388607, a - 16777216, a).astype(np.float32) / 8388608.0
        elif bits == 32:
            a = raw.view(np.int32).astype(np.float32) / 2147483648.0
        else:
            raise IOError(f"不支持的 PCM 位深: {bits}")
    else:
        raise IOError(f"不支持的 WAV 编码格式 code={code}")

    if ch == 1:
        a = np.stack([a, a])
    else:
        a = a.reshape(-1, ch).T
        a = a[:2]
    a = a.reshape(2, -1)
    return a.astype(np.float32), sr


def read_wav(path):
    """从文件路径读取 WAV。"""
    with open(path, 'rb') as f:
        return read_wav_stream(f)


def resample(x, sr_in, sr_out=SR_TARGET):
    """利用 FFT 频域插值对立体声音频重采样至目标采样率（默认 44.1kHz）。"""
    if sr_in == sr_out:
        return x
    n_out = int(round(x.shape[1] * sr_out / sr_in))
    n_in = x.shape[1]
    n = max(n_in, n_out)
    f = np.fft.rfft(x, n=n, axis=1)
    if n_out < n_in:
        f = f[:, :n_out // 2 + 1] * 2
    else:
        pad = np.zeros((f.shape[0], n_out // 2 + 1 - f.shape[1]), dtype=f.dtype)
        f = np.concatenate([f, pad], axis=1)
    y = np.fft.irfft(f, n=n_out, axis=1)
    scale = n_out / float(n_in)
    return (y * scale).astype(np.float32)


def decode_audio(path, sr_out=SR_TARGET):
    """通用音频解码：WAV 原生读取，非 WAV 调用 ffmpeg 无损解码转立体声 44.1kHz。"""
    ext = os.path.splitext(path)[1].lower()
    if ext == '.wav':
        x, sr = read_wav(path)
        return resample(x, sr, sr_out), sr_out
    tmp = path + '.tmp.wav'
    ffmpeg = _find_ffmpeg()
    try:
        r = subprocess.run(
            [ffmpeg, '-y', '-i', path,
             '-vn', '-ac', '2', '-ar', str(SR_TARGET),
             '-c:a', 'pcm_s16le', tmp],
            capture_output=True, timeout=1200)
        if r.returncode != 0:
            raise IOError(f"FFmpeg 转码音频失败: {r.stderr.decode('utf-8', 'ignore')}")
        return read_wav(tmp)[0], SR_TARGET
    finally:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except OSError: pass


def _write_header_to_stream(w, sr, ch, bits, is_float, nframes):
    byte_rate = sr * ch * bits // 8
    w.write(b'RIFF')
    w.write(struct.pack('<I', 36 + nframes * ch * bits // 8))
    w.write(b'WAVE')
    w.write(b'fmt ')
    code = 3 if is_float else 1
    w.write(struct.pack('<IHHIIHH', 16, code, ch, sr, byte_rate, ch * bits // 8, bits))
    w.write(b'data')
    w.write(struct.pack('<I', nframes * ch * bits // 8))


def write_wav(path, data, sr, bitdepth=16, is_float=False):
    """将立体声 float32 数据写入本地 WAV 文件。"""
    x = np.clip(data, -1.0, 1.0).astype(np.float32)
    n = x.shape[1]
    with open(path, 'wb') as w:
        _write_header_to_stream(w, sr, 2, bitdepth, is_float, n)
        if not is_float and bitdepth == 16:
            pcm = (x.T * 32767.0).astype(np.int16)
            w.write(pcm.tobytes())
        else:
            w.write(np.ascontiguousarray(x.T).tobytes())


def write_wav_bytes(data, sr, bitdepth=16, is_float=False):
    """将立体声 float32 数据编码为内存中的标准 WAV 字节流。"""
    buf = io.BytesIO()
    x = np.clip(data, -1.0, 1.0).astype(np.float32)
    n = x.shape[1]
    _write_header_to_stream(buf, sr, 2, bitdepth, is_float, n)
    if not is_float and bitdepth == 16:
        pcm = (x.T * 32767.0).astype(np.int16)
        buf.write(pcm.tobytes())
    else:
        buf.write(np.ascontiguousarray(x.T).tobytes())
    return buf.getvalue()
