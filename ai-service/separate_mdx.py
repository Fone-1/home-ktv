# -*- coding: utf-8 -*-
"""
UVR-MDX-NET-Inst_HQ_3 单模型伴奏分离推理引擎。
复刻 Ultimate Vocal Remover 的 MDX-Net demix 算法（纯 NumPy + ONNXRuntime，支持独显 CUDA / 核显 / CPU 自适应推理）。
"""
import os
import logging
import numpy as np
import onnxruntime as ort

log = logging.getLogger("ai_separator")

# ---------- 模型固定参数（从 UVR model_data.json 中 UVR-MDX-NET-Inst_HQ_3 读取）----------
N_FFT = 6144          # mdx_n_fft_scale_set
HOP = 1024            # 固定 hop
DIM_F = 3072          # mdx_dim_f_set（模型输入第二维）
DIM_T = 256           # mdx_dim_t_set = 8  -> 2**8
COMPENSATE = 1.022    # compensate
OVERLAP = 0.25        # overlap_mdx 默认
SAMPLE_RATE = 44100

TRIM = N_FFT // 2
CHUNK_SIZE = HOP * (DIM_T - 1)                 # 261120，一次喂给模型的时间样本数
GEN_SIZE = CHUNK_SIZE - 2 * TRIM               # 生成的有效样本数


def _hann_periodic(n):
    """复刻 torch.hann_window(periodic=True)"""
    if n % 2 == 0:
        return np.hanning(n + 1)[:-1].astype(np.float32)
    return np.hanning(n).astype(np.float32)


class STFT:
    """复刻 UVR lib_v5/tfc_tdf_v3.py 的 STFT（基于 torch.stft/istft 语义，纯 NumPy 实现）。"""

    def __init__(self, n_fft, hop_length, dim_f):
        self.n_fft = n_fft
        self.hop_length = hop_length
        self.dim_f = dim_f
        self.window = _hann_periodic(n_fft)

    def forward(self, x):
        """x: (2, T) float32 -> (1, 4, dim_f, n_frames)"""
        c, t = x.shape
        pad = self.n_fft // 2
        # torch.stft center=True, pad_mode='reflect'
        xp = np.pad(x, ((0, 0), (pad, pad)), mode='reflect')
        frames = _enframe(xp, self.n_fft, self.hop_length)     # (c, n, n_fft)
        frames = frames * self.window[None, None, :]
        spec = np.fft.rfft(frames, n=self.n_fft, axis=-1)      # (c, n, n_fft//2+1) complex
        # 拆成 (实部, 虚部) 两通道并换成 (频率, 时间) 轴: (c,2,freq,n)
        spec = np.transpose(spec, (0, 2, 1))                   # (c, freq, n)
        spec = np.stack([spec.real, spec.imag], axis=1)        # (c,2,freq,n)
        out = spec.reshape(1, c * 2, spec.shape[2], spec.shape[3])  # (1,4,freq,n)
        return out[..., :self.dim_f, :].astype(np.float32)

    def inverse(self, x):
        """x: (1, 4, dim_f, t) float32 -> (2, T)"""
        x = np.asarray(x, dtype=np.float64)
        pad = self.n_fft // 2
        n = self.n_fft // 2 + 1
        _, c, f, t = x.shape
        # 补回被截断的最高频
        f_pad = np.zeros((1, c, n - f, t), dtype=x.dtype)
        x = np.concatenate([x, f_pad], axis=-2)                # (1,4,n,t)
        x = x.reshape(1, c // 2, 2, n, t)                      # (1,2ch,2,f,t)
        comp = x[..., 0, :, :] + 1j * x[..., 1, :, :]          # (1,2,f,t)
        tframes = np.fft.irfft(comp, n=self.n_fft, axis=-2)    # (1,2,n_fft,t)
        tframes = np.transpose(tframes, (0, 1, 3, 2))          # (1,2,t,n_fft)
        tframes *= self.window[None, None, None, :]
        # overlap-add
        out_len = (t - 1) * self.hop_length + self.n_fft
        y = np.zeros((1, 2, out_len), dtype=np.float64)
        wsq = np.zeros((1, 2, out_len), dtype=np.float64)
        for i in range(t):
            s = i * self.hop_length
            y[:, :, s:s + self.n_fft] += tframes[:, :, i, :]
            wsq[:, :, s:s + self.n_fft] += self.window * self.window
        y = y / np.maximum(wsq, 1e-12)
        # torch.istft center=True, length=None: 返回 (n_frames-1)*hop，去掉两端 padding
        return y[:, :, pad:pad + (t - 1) * self.hop_length][0].astype(np.float32)  # (2, T)


def _enframe(x, frame_len, hop):
    """按 hop 滑窗切帧: (c, L) -> (c, n, frame_len)"""
    c, l = x.shape
    n = 1 + (l - frame_len) // hop
    strides = (x.strides[0], x.strides[1] * hop, x.strides[1])
    return np.lib.stride_tricks.as_strided(x, shape=(c, n, frame_len), strides=strides)


class MDXSeparator:
    """UVR-MDX-Net 伴奏分离推理器，自适应支持独显 CUDA、DirectML、OpenVINO 与 CPU。"""

    def __init__(self, model_path):
        available_providers = ort.get_available_providers()
        preferred = [
            'CUDAExecutionProvider',
            'DirectMLExecutionProvider',
            'OpenVINOExecutionProvider',
            'CPUExecutionProvider'
        ]
        providers = [p for p in preferred if p in available_providers]
        if not providers:
            providers = ['CPUExecutionProvider']

        sess_options = ort.SessionOptions()
        sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        log.info(f"正在加载 ONNX 伴奏模型: {model_path}, 候选执行提供器: {providers}")
        self.session = ort.InferenceSession(model_path, sess_options=sess_options, providers=providers)
        self.active_provider = self.session.get_providers()[0]
        log.info(f"模型加载成功，当前生效硬件加速执行器: {self.active_provider}")

        self.stft = STFT(N_FFT, HOP, DIM_F)
        self.input_name = self.session.get_inputs()[0].name

    def _model_run(self, spek):
        """spek: (1,4,dim_f,t) -> 同形状"""
        return self.session.run(None, {self.input_name: spek})[0]

    def run_model(self, mix_part):
        """mix_part: (2, chunk_size) -> (2, chunk_size) 模型输出波形"""
        spek = self.stft.forward(mix_part)
        spek[:, :, :3, :] *= 0  # 复刻 UVR: 清零最低 3 个频点
        # 经典 MDX-Net 反演去噪: spec_pred = -model(-spek)*0.5 + model(spek)*0.5
        spec_pred = -self._model_run(-spek) * 0.5 + self._model_run(spek) * 0.5
        return self.stft.inverse(spec_pred)

    def demix(self, mix, progress=None):
        """
        对输入立体声音频执行高质量伴奏提取。
        
        :param mix: (2, T) float32 输入音频
        :param progress: 可选进度回调函数 (current_chunk, total_chunks)
        :return: (2, T) 纯净伴奏波形
        """
        trim = TRIM
        chunk_size = CHUNK_SIZE
        gen_size = GEN_SIZE
        overlap = OVERLAP

        pad = gen_size + trim - (mix.shape[-1] % gen_size)
        mixture = np.concatenate(
            (np.zeros((2, trim), dtype='float32'),
             mix,
             np.zeros((2, pad), dtype='float32')), 1)

        step = int((1 - overlap) * chunk_size)
        result = np.zeros((1, 2, mixture.shape[-1]), dtype=np.float32)
        divider = np.zeros((1, 2, mixture.shape[-1]), dtype=np.float32)
        total_chunks = (mixture.shape[-1] + step - 1) // step
        chunk_index = 0

        for i in range(0, mixture.shape[-1], step):
            chunk_index += 1
            if progress:
                progress(chunk_index, total_chunks)
            start = i
            end = min(i + chunk_size, mixture.shape[-1])
            chunk_size_actual = end - start

            window = np.hanning(chunk_size_actual)
            window = np.tile(window[None, None, :], (1, 2, 1))

            mix_part_ = mixture[:, start:end]
            if end != i + chunk_size:
                pad_size = (i + chunk_size) - end
                mix_part_ = np.concatenate((mix_part_, np.zeros((2, pad_size), dtype='float32')), axis=-1)

            tar_waves = self.run_model(mix_part_)[None]   # (1,2,chunk)

            tar_waves[..., :chunk_size_actual] *= window
            divider[..., start:end] += window
            result[..., start:end] += tar_waves[..., :end - start]

        with np.errstate(invalid='ignore', divide='ignore'):
            tar_waves = result / np.maximum(divider, 1e-8)
        tar_waves = np.nan_to_num(tar_waves, nan=0.0, posinf=0.0, neginf=0.0)
        tar_waves = tar_waves[:, :, trim:-trim][:, :, :mix.shape[-1]]

        return (tar_waves[0] * COMPENSATE).astype(np.float32)
