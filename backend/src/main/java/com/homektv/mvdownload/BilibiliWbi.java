package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 哔哩哔哩 WBI (Web-Based Interface) 签名与 Cookie / 防风控凭证辅助组件：
 * 1. 负责获取并缓存 B 站前端指纹 Cookie (buvid3 / b_nut)，规避缺少 Cookie 导致的 HTTP 412 Precondition Failed 拦截；
 * 2. 负责获取并缓存 WBI 动态密钥 (img_key 与 sub_key)，经特定置换表生成 mixin_key；
 * 3. 负责对请求参数按字母排序、字符过滤、RFC3986 编码，并加盐计算 32 位 MD5 签名 (w_rid 与 wts)；
 * 4. 具备在异常拦截时的凭证重置与降级容错机制。
 */
@Component
public class BilibiliWbi {

    private static final Logger log = LoggerFactory.getLogger(BilibiliWbi.class);

    // B 站官方 Wbi 混淆密钥重排置换表 (长度 64，用于从 img_key + sub_key 中提取前 32 位 mixin_key)
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    // 默认备用 Wbi 密钥对（当无法连通导航接口或首次初始化前使用）
    private static final String DEFAULT_IMG_KEY = "653657f524a547ac981ded72ea1727cc";
    private static final String DEFAULT_SUB_KEY = "ee0eb170b1a147cb90a6ae822954d3e3";

    // 过滤参数值中 B 站官方指定的非法字符：! ' ( ) *
    private static final Pattern CHR_FILTER = Pattern.compile("[!'()*]");

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";

    private final ObjectMapper mapper;
    private final BilibiliAuthService authService;
    // 进程内稳定持久的客户端 uuid 指纹，避免每个请求变动触发 WAF 412
    private final String cachedUuid = UUID.randomUUID().toString();

    // 缓存的 mixinKey 及其过期时间 (毫秒)
    private volatile String cachedMixinKey;
    private volatile long mixinKeyExpireTime = 0L;

    // 缓存的 buvid3 及其过期时间 (毫秒)
    private volatile String cachedBuvid3;
    private volatile String cachedBuvid4;
    private volatile long buvidExpireTime = 0L;

    public BilibiliWbi(ObjectMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public BilibiliWbi(ObjectMapper mapper, @Autowired(required = false) BilibiliAuthService authService) {
        this.mapper = mapper;
        this.authService = authService;
        // 初始填充缺省 mixinKey
        this.cachedMixinKey = calculateMixinKey(DEFAULT_IMG_KEY, DEFAULT_SUB_KEY);
        this.cachedBuvid3 = generateSyntheticBuvid3();
    }

    /**
     * 获取可直接附着在请求头中的 Cookie 字符串 (包含 buvid3, b_nut 等核心追踪字段)
     */
    public String getCookieHeader() {
        String authCookie = (authService != null) ? authService.getAuthCookie() : null;
        if (authCookie != null && !authCookie.isBlank()) {
            Map<String, String> map = new LinkedHashMap<>();
            for (String part : authCookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank()) {
                    map.put(kv[0].trim(), kv[1].trim());
                }
            }
            if (!map.containsKey("buvid3")) {
                String buvid = this.cachedBuvid3;
                if (buvid == null || buvid.isBlank() || System.currentTimeMillis() > buvidExpireTime) {
                    buvid = generateSyntheticBuvid3();
                    this.cachedBuvid3 = buvid;
                    this.buvidExpireTime = System.currentTimeMillis() + Duration.ofHours(12).toMillis();
                }
                map.put("buvid3", buvid);
            }
           if (!map.containsKey("buvid4") && this.cachedBuvid4 != null && !this.cachedBuvid4.isBlank()) {
               map.put("buvid4", this.cachedBuvid4);
           }
            if (!map.containsKey("_uuid")) {
                map.put("_uuid", this.cachedUuid + "infoc");
            }
           StringBuilder sb = new StringBuilder();
            map.forEach((k, v) -> {
                if (!sb.isEmpty()) sb.append("; ");
                sb.append(k).append('=').append(v);
            });
            return sb.toString();
        }

        // 未登录游客态模式：携带标准设备与访客指纹
        String buvid = this.cachedBuvid3;
        if (buvid == null || buvid.isBlank() || System.currentTimeMillis() > buvidExpireTime) {
            buvid = generateSyntheticBuvid3();
            this.cachedBuvid3 = buvid;
            this.buvidExpireTime = System.currentTimeMillis() + Duration.ofHours(12).toMillis();
        }
        long nowSec = System.currentTimeMillis() / 1000;
        String b4Part = (this.cachedBuvid4 != null && !this.cachedBuvid4.isBlank()) ? "; buvid4=" + this.cachedBuvid4 : "";
        return "buvid3=" + buvid + "; b_nut=" + nowSec + "; _uuid=" + this.cachedUuid + "infoc" + b4Part + ";";
    }

    /**
     * 确保凭证与密钥就绪：当从未同步过官方密钥或密钥已过期时，自动拉取最新的 buvid 指纹与 WBI 动态密钥
     */
    public void ensureReady(HttpClient httpClient) {
        if (this.cachedMixinKey == null || System.currentTimeMillis() > this.mixinKeyExpireTime) {
            refreshBuvid(httpClient);
            refreshWbiKeys(httpClient);
        }
    }

    /**
     * 刷新 Cookie 凭证（优先向 B 站官方 /x/frontend/finger/spi 拉取真实指纹，失败则回退至合成指纹）
     */
    public synchronized void refreshBuvid(HttpClient httpClient) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bilibili.com/x/frontend/finger/spi"))
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

           HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
           if (response.statusCode() == 200) {
                List<String> setCookies = response.headers().allValues("Set-Cookie");
                for (String sc : setCookies) {
                    if (sc.startsWith("buvid3=")) {
                        String val = sc.split(";")[0].substring("buvid3=".length());
                        if (!val.isBlank()) this.cachedBuvid3 = val;
                    } else if (sc.startsWith("buvid4=")) {
                        String val = sc.split(";")[0].substring("buvid4=".length());
                        if (!val.isBlank()) this.cachedBuvid4 = val;
                    }
                }
               JsonNode root = mapper.readTree(response.body());
                if (root.path("code").asInt(-1) == 0) {
                    String b3 = root.path("data").path("b_3").asText(null);
                    if (b3 != null && !b3.isBlank()) {
                        this.cachedBuvid3 = b3;
                        this.buvidExpireTime = System.currentTimeMillis() + Duration.ofHours(24).toMillis();
                        log.info("成功同步 B 站官方 buvid3 指纹");
                    }
                    String b4 = root.path("data").path("b_4").asText(null);
                    if (b4 != null && !b4.isBlank()) {
                        this.cachedBuvid4 = b4;
                    }
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("向 B 站请求官方 buvid3 失败，使用本地合成指纹: {}", e.getMessage());
        }

        // 仅在无有效指纹时回退生成合法格式合成 buvid3
        if (this.cachedBuvid3 == null || this.cachedBuvid3.isBlank()) {
            this.cachedBuvid3 = generateSyntheticBuvid3();
            this.buvidExpireTime = System.currentTimeMillis() + Duration.ofHours(12).toMillis();
        }
    }

    /**
     * 刷新 WBI 动态密钥（从 /x/web-interface/nav 提取最新的 img_key 与 sub_key）
     */
    public synchronized void refreshWbiKeys(HttpClient httpClient) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", getCookieHeader())
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode wbiImg = root.path("data").path("wbi_img");
                String imgUrl = wbiImg.path("img_url").asText("");
                String subUrl = wbiImg.path("sub_url").asText("");

                String imgKey = extractKeyFromUrl(imgUrl);
                String subKey = extractKeyFromUrl(subUrl);

                if (imgKey != null && subKey != null) {
                    this.cachedMixinKey = calculateMixinKey(imgKey, subKey);
                    this.mixinKeyExpireTime = System.currentTimeMillis() + Duration.ofHours(12).toMillis();
                    log.info("成功同步 B 站 WBI 动态密钥并生成最新 mixin_key");
                    return;
                }
            }
        } catch (Exception e) {
            log.debug("从 B 站同步最新 WBI 密钥失败，维持当前/缺省 mixin_key: {}", e.getMessage());
        }

        // 若未设置或过期，使用默认保底密钥
        if (this.cachedMixinKey == null) {
            this.cachedMixinKey = calculateMixinKey(DEFAULT_IMG_KEY, DEFAULT_SUB_KEY);
        }
        this.mixinKeyExpireTime = System.currentTimeMillis() + Duration.ofHours(2).toMillis();
    }

    /**
     * 对请求参数字典进行 WBI 签名，自动追加秒级时间戳 wts 与哈希摘要 w_rid
     *
     * @param params 原始请求参数
     * @return 完整的 URL query 字符串（已做 RFC3986 编码，可直接拼在 URL 问号后）
     */
    public String signQuery(Map<String, String> params) {
        return signQuery(params, getOrInitMixinKey());
    }

    /**
     * 提供纯函数签名重载，便于单测验证算法一致性
     */
    public static String signQuery(Map<String, String> params, String mixinKey) {
        Map<String, String> sortedParams = new TreeMap<>(params);
        // 若调用方未显式传入 wts，则默认使用当前秒级时间戳
        if (!sortedParams.containsKey("wts")) {
            sortedParams.put("wts", String.valueOf(System.currentTimeMillis() / 1000));
        }

        StringBuilder queryBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (entry.getValue() == null) continue;
            if (!queryBuilder.isEmpty()) {
                queryBuilder.append('&');
            }
            String filteredValue = CHR_FILTER.matcher(entry.getValue()).replaceAll("");
            queryBuilder.append(encodeRfc3986(entry.getKey()))
                    .append('=')
                    .append(encodeRfc3986(filteredValue));
        }

        String rawQuery = queryBuilder.toString();
        String toSign = rawQuery + mixinKey;
        String wRid = md5Hex(toSign);

        return rawQuery + "&w_rid=" + wRid;
    }

    /**
     * 当遇到 HTTP 412 或业务 -412 拦截时强制失效缓存并刷新
     */
    public synchronized void invalidate() {
        this.cachedBuvid3 = generateSyntheticBuvid3();
        this.cachedBuvid4 = null;
        this.buvidExpireTime = 0L;
        this.mixinKeyExpireTime = 0L;
    }

    /**
     * 根据置换表重排 img_key 与 sub_key 计算 32 位 mixin_key
     */
    public static String calculateMixinKey(String imgKey, String subKey) {
        String rawKey = (imgKey == null ? "" : imgKey) + (subKey == null ? "" : subKey);
        if (rawKey.length() < 64) {
            throw new IllegalArgumentException("imgKey 与 subKey 拼接长度不足 64 位: " + rawKey.length());
        }
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append(rawKey.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return sb.toString();
    }

    private String getOrInitMixinKey() {
        String key = this.cachedMixinKey;
        if (key == null || key.isBlank() || System.currentTimeMillis() > mixinKeyExpireTime) {
            key = calculateMixinKey(DEFAULT_IMG_KEY, DEFAULT_SUB_KEY);
            this.cachedMixinKey = key;
        }
        return key;
    }

    /**
     * 从 B 站图片直链中提取 32 位 hex 文件名（不含拓展名）
     */
    private static String extractKeyFromUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            int lastSlash = url.lastIndexOf('/');
            int lastDot = url.lastIndexOf('.');
            if (lastSlash >= 0 && lastDot > lastSlash) {
                return url.substring(lastSlash + 1, lastDot);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 按照 RFC 3986 规范对 URL 成分进行编码（空格编码为 %20，非 +）
     */
    public static String encodeRfc3986(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    /**
     * 本地生成符合 B 站规范格式的 buvid3 指纹
     */
    public static String generateSyntheticBuvid3() {
        String uuid = UUID.randomUUID().toString();
        int random5 = ThreadLocalRandom.current().nextInt(10000, 99999);
        return uuid + random5 + "infoc";
    }

    /**
     * 计算字符串的 32 位小写 MD5 十六进制摘要
     */
    public static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 摘要算法不可用", e);
        }
    }
}
