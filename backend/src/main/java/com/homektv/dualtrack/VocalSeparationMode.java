package com.homektv.dualtrack;

/**
 * 人声分离引擎模式枚举。
 *
 * Vocal separation engine modes.
 */
public enum VocalSeparationMode {
    /** FFmpeg 内置分频带声学反相中置消音（零额外依赖，秒级完成）。 */
    DSP,
    /** 本地深度学习模型推理（如 UVR-MDX-Net ONNX）。 */
    LOCAL_AI,
    /** 远程深度学习 AI 分离微服务（如 Meta Demucs / UVR5 HTTP API）。 */
    REMOTE_AI;

    /**
     * 解析模式字符串，默认回退为 DSP。
     *
     * @param modeStr 模式标识字符串
     * @return 匹配的模式
     */
    public static VocalSeparationMode parse(String modeStr) {
        if (modeStr == null || modeStr.isBlank()) {
            return DSP;
        }
        try {
            return valueOf(modeStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DSP;
        }
    }
}
