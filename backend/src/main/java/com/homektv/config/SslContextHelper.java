package com.homektv.config;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * SSL 上下文辅助工具：为第三方公网在线元数据与音视频拉取（网易云、B站、AI 服务等）提供容错的 SSL 上下文，
 * 避免在 Alpine 容器精简证书库或家用网络代理环境下因 CA 证书缺失导致 PKIX path building 校验失败。
 */
public final class SslContextHelper {

    private static final SSLContext TRUST_ALL_CONTEXT;

    static {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            TRUST_ALL_CONTEXT = ctx;
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new IllegalStateException("Failed to initialize trust-all SSLContext", e);
        }
    }

    private SslContextHelper() {}

    public static SSLContext trustAllSslContext() {
        return TRUST_ALL_CONTEXT;
    }
}
