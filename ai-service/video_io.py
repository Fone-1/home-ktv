# -*- coding: utf-8 -*-
"""
视频伴奏处理与 FFmpeg 合流重构模块。
"""
import os
import subprocess
import wav_io

VIDEO_EXTS = {'.mp4', '.avi', '.mkv', '.mov', '.wmv', '.flv',
              '.webm', '.m4v', '.ts', '.m2ts', '.3gp', '.ogv'}


def is_video_file(path):
    return os.path.splitext(path)[1].lower() in VIDEO_EXTS


def _ffmpeg():
    return wav_io._find_ffmpeg()


def extract_audio(video_path, output_audio_path):
    """从输入视频中无损抽取 44.1kHz / 立体声 / 16-bit PCM WAV 音轨。"""
    cmd = [_ffmpeg(), '-y', '-i', video_path,
           '-vn', '-acodec', 'pcm_s16le',
           '-ar', '44100', '-ac', '2', output_audio_path]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        err = r.stderr.decode('utf-8', 'ignore').strip()[-500:] if r.stderr else '未知错误'
        raise RuntimeError(f"视频音频提取失败: {err}")


def probe_audio(video_path):
    """检测视频首条音轨的编码格式与码率。"""
    ffmpeg = _ffmpeg()
    r = subprocess.run([ffmpeg, '-i', video_path], capture_output=True)
    output = (r.stderr or b'').decode('utf-8', 'ignore')
    codec = None
    bitrate = None
    for line in output.split('\n'):
        if 'Audio:' in line:
            audio_info = line.split('Audio:', 1)[1].strip()
            codec = audio_info.split(',')[0].strip().lower()
            for token in audio_info.split(','):
                tok = token.strip()
                if 'kb/s' in tok:
                    try:
                        bitrate = int(tok.replace('kb/s', '').strip())
                    except ValueError:
                        pass
            break
    return codec, bitrate


def merge_audio(video_path, audio_path, output_path, mode='add_track'):
    """
    将伴奏音频合流回原视频。
    mode='add_track': Track 0 保留原唱，Track 1 注入伴奏；
    mode='replace': 仅保留伴奏。
    视频流执行无损拷贝 -c:v copy。
    """
    orig_codec, orig_bitrate = probe_audio(video_path)
    audio_encoder = 'aac'
    audio_bitrate = f"{orig_bitrate}k" if (orig_bitrate and 96 <= orig_bitrate <= 320) else "192k"

    cmd = [_ffmpeg(), '-y', '-i', video_path, '-i', audio_path,
           '-map', '0:v:0', '-c:v', 'copy']
    if mode == 'replace':
        cmd += ['-map', '1:a:0', '-c:a', audio_encoder, '-b:a', audio_bitrate]
    else:
        cmd += ['-map', '0:a:0', '-map', '1:a:0',
                '-c:a:0', 'copy',
                '-c:a:1', audio_encoder, '-b:a:1', audio_bitrate]
    cmd += ['-shortest', output_path]

    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        err = r.stderr.decode('utf-8', 'ignore').strip()[-500:] if r.stderr else '未知错误'
        raise RuntimeError(f"音轨合并失败: {err}")
