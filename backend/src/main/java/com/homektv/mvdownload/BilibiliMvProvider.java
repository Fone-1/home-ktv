package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.SslContextHelper;
import com.homektv.web.ApiException;
import com.homektv.web.dto.BilibiliPartDto;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 哔哩哔哩在线 MV / 伴奏视频提供者：
 * 1. 搜索开放视频列表 (search_type=video)
 * 2. 换取分段 cid 并解析 DASH 音视频独立流
 */
@Component
public class BilibiliMvProvider implements MvSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BilibiliMvProvider.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String REFERER = "https://www.bilibili.com/";
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private final ObjectMapper mapper;
    private final BilibiliWbi wbi;
    private final HttpClient httpClient;

    public BilibiliMvProvider(ObjectMapper mapper) {
        this(mapper, new BilibiliWbi(mapper));
    }

    @Autowired
    public BilibiliMvProvider(ObjectMapper mapper, BilibiliWbi wbi) {
        this.mapper = mapper;
        this.wbi = wbi;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(SslContextHelper.trustAllSslContext())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public MvProvider provider() {
        return MvProvider.BILIBILI;
    }

   @Override
   public List<MvSearchItem> search(String keyword, int limit, Duration timeout) {
       String cleanKeyword = keyword == null ? "" : keyword.trim();
       if (cleanKeyword.isEmpty()) {
           return List.of();
       }

        // 搜索前确保 WBI 动态密钥与设备指纹处于就绪状态，防止冷启动初次搜索遭遇 412
        wbi.ensureReady(httpClient);

       // 1. 优先尝试现代 WBI 签名搜索 (/x/web-interface/wbi/search/type)
       List<MvSearchItem> items = searchWbi(cleanKeyword, limit, timeout);
        if (!items.isEmpty()) {
            return items;
        }

        // 2. 备用尝试：携带 Cookie 凭证的基础搜索接口 (/x/web-interface/search/type)
        return searchFallback(cleanKeyword, limit, timeout);
    }

    /**
     * 使用 B 站官方 WBI 鉴权与 Cookie 进行全网视频检索
     */
    private List<MvSearchItem> searchWbi(String keyword, int limit, Duration timeout) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");
        params.put("pagesize", String.valueOf(Math.min(limit, 30)));
        params.put("order", "totalrank");

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String signedQuery = wbi.signQuery(params);
                String url = "https://api.bilibili.com/x/web-interface/wbi/search/type?" + signedQuery;

               HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                       .header("Referer", REFERER)
                        .header("Origin", "https://www.bilibili.com")
                       .header("User-Agent", USER_AGENT)
                       .header("Cookie", wbi.getCookieHeader())
                        .header("Accept", "application/json, text/plain, */*")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .timeout(timeout)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 412) {
                    log.warn("B站 WBI 视频搜索返回 HTTP 412 (前置风控拦截)，第 {} 次尝试刷新指纹与凭证...", attempt);
                    wbi.invalidate();
                    wbi.refreshBuvid(httpClient);
                    wbi.refreshWbiKeys(httpClient);
                    continue;
                }

               if (response.statusCode() != 200) {
                   log.warn("B站 WBI 视频搜索返回 HTTP {}", response.statusCode());
                   break;
               }

                JsonNode root = safeReadJson(response, "B站 WBI 视频搜索");
                if (root == null) {
                    continue;
                }
               int code = root.path("code").asInt(0);
                if (code == -412) {
                    log.warn("B站 WBI 视频搜索业务拦截 code=-412: {}，尝试刷新凭证", root.path("message").asText());
                    wbi.invalidate();
                    wbi.refreshBuvid(httpClient);
                    wbi.refreshWbiKeys(httpClient);
                    continue;
                } else if (code != 0) {
                    log.warn("B站 WBI 视频搜索返回业务异常: code={}, message={}", code, root.path("message").asText());
                    break;
                }

                JsonNode list = root.path("data").path("result");
                List<MvSearchItem> results = parseVideos(list);
                if (!results.isEmpty()) {
                    return results;
                }
            } catch (Exception e) {
                log.warn("B站 WBI 视频搜索异常 (attempt={}): {}", attempt, e.getMessage());
            }
        }
        return List.of();
    }

    /**
     * 备用降级搜索：使用基础搜索接口但确保携带 Cookie 追踪特征
     */
    private List<MvSearchItem> searchFallback(String keyword, int limit, Duration timeout) {
        try {
            String url = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
                    + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&page=1&pagesize=" + Math.min(limit, 30);

           HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                   .header("Referer", REFERER)
                    .header("Origin", "https://www.bilibili.com")
                   .header("User-Agent", USER_AGENT)
                   .header("Cookie", wbi.getCookieHeader())
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
           if (response.statusCode() != 200) {
               log.warn("B站备用视频搜索返回 HTTP {}", response.statusCode());
               return List.of();
           }

            JsonNode root = safeReadJson(response, "B站备用视频搜索");
            if (root == null) {
                return List.of();
            }
           if (root.path("code").asInt(0) != 0) {
                log.warn("B站备用搜索返回业务异常: code={}, message={}", root.path("code").asInt(), root.path("message").asText());
                return List.of();
            }
            JsonNode list = root.path("data").path("result");
            return parseVideos(list);
        } catch (Exception e) {
            log.warn("B站备用视频搜索异常: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 外部传入标识符解析结果（支持纯 bvid、带分P参数 BVxxx?p=2 或 BVxxx?p=2&cid=123）
     */
    public record ParsedBiliTarget(String bvid, int page, long cid) {}

    /**
     * 解析外部标识符，提取 bvid、目标分集号 page 和目标 cid
     */
    public static ParsedBiliTarget parseExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) return new ParsedBiliTarget("", 1, 0L);
        String s = externalId.trim();
        String bvid = s;
        int page = 1;
        long cid = 0L;
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) {
            bvid = s.substring(0, qIdx).trim();
            String query = s.substring(qIdx + 1);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = pair.substring(0, eq).trim();
                    String v = pair.substring(eq + 1).trim();
                    if ("p".equalsIgnoreCase(k) || "page".equalsIgnoreCase(k)) {
                        try { page = Math.max(1, Integer.parseInt(v)); } catch (Exception ignored) {}
                    } else if ("cid".equalsIgnoreCase(k)) {
                        try { cid = Long.parseLong(v); } catch (Exception ignored) {}
                    }
                }
            }
        } else if (s.contains(":")) {
            String[] parts = s.split(":");
            bvid = parts[0].trim();
            if (parts.length > 1) {
                try { page = Math.max(1, Integer.parseInt(parts[1].trim())); } catch (Exception ignored) {}
            }
            if (parts.length > 2) {
                try { cid = Long.parseLong(parts[2].trim()); } catch (Exception ignored) {}
            }
        }
        return new ParsedBiliTarget(bvid, page, cid);
    }

    @Override
    public MvStreamInfo resolveStream(String externalId, String desiredResolution, Duration timeout) {
        ParsedBiliTarget target = parseExternalId(externalId);
        String bvid = target.bvid();
        // 确保 WBI 密钥与指纹处于可用就绪状态
        wbi.ensureReady(httpClient);

        // 策略 0: 如果未指定分P（默认第 1 集）且无显式 cid，优先尝试从视频网页 (SSR) 直接提取 window.__playinfo__
        if (target.page() <= 1 && target.cid() <= 0) {
            MvStreamInfo webStream = fetchPlayStreamFromWebPage(bvid, timeout);
            if (webStream != null) {
                return webStream;
            }
        }

        // 1. 获取目标分集 CID (已携带 cid 直接使用，否则依据目标分P page 进行匹配查找)
        long cid = target.cid() > 0 ? target.cid() : resolveCid(bvid, target.page(), timeout);
        if (cid <= 0) {
            throw new ApiException("MV_STREAM_NOT_FOUND", "未能获取 B 站视频分段信息 (bvid=" + bvid + ", p=" + target.page() + ")");
        }

        // 2. API 接口多重策略获取指定 CID 的独立播放流 (DASH优先、单流备用、WBI自愈重试)
        List<String> failureReasons = new ArrayList<>();
        MvStreamInfo streamInfo = fetchPlayStreamWithRetry(bvid, cid, desiredResolution, timeout, failureReasons);
        if (streamInfo != null) {
            return streamInfo;
        }

        String detail = failureReasons.isEmpty() ? "" : " [" + String.join("; ", failureReasons) + "]";
        throw new ApiException("MV_STREAM_NOT_FOUND", "未能解析 B 站播放流地址: " + bvid + " (p=" + target.page() + ", cid=" + cid + ")" + detail);
    }

    /**
     * 获取 B 站视频全部可用分集/分P列表
     */
    public List<BilibiliPartDto> fetchParts(String rawExternalId, Duration timeout) {
        ParsedBiliTarget target = parseExternalId(rawExternalId);
        String bvid = target.bvid();
        if (bvid.isBlank()) return List.of();

        // 1. 优先调用轻量分P列表接口 /x/player/pagelist?bvid=
        try {
            String url = "https://api.bilibili.com/x/player/pagelist?bvid=" + bvid;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", wbi.getCookieHeader())
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = safeReadJson(response, "B站分P列表(" + bvid + ")");
            if (root != null && root.path("code").asInt(-1) == 0) {
                JsonNode data = root.path("data");
                if (data.isArray() && !data.isEmpty()) {
                    List<BilibiliPartDto> parts = new ArrayList<>();
                    for (JsonNode node : data) {
                        int page = node.path("page").asInt(parts.size() + 1);
                        long cid = node.path("cid").asLong(0);
                        String part = node.path("part").asText("").trim();
                        if (part.isBlank()) part = "第 " + page + " 集";
                        int durationMs = node.path("duration").asInt(0) * 1000;
                        String firstFrame = node.path("first_frame").asText(null);
                        if (firstFrame != null && firstFrame.startsWith("//")) firstFrame = "https:" + firstFrame;
                        else if (firstFrame != null && firstFrame.startsWith("http://")) firstFrame = "https://" + firstFrame.substring(7);
                        parts.add(new BilibiliPartDto(page, cid, part, durationMs, firstFrame));
                    }
                    if (!parts.isEmpty()) return parts;
                }
            }
        } catch (Exception e) {
            log.warn("通过 pagelist 获取 B 站分P异常: {}", e.getMessage());
        }

        // 2. 备用通过视频详情接口 /x/web-interface/view
        try {
            String viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
            HttpRequest request = HttpRequest.newBuilder(URI.create(viewUrl))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", wbi.getCookieHeader())
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = safeReadJson(response, "B站视频详情分P(" + bvid + ")");
            if (root != null && root.path("code").asInt(-1) == 0) {
                JsonNode pages = root.path("data").path("pages");
                if (pages.isArray() && !pages.isEmpty()) {
                    List<BilibiliPartDto> parts = new ArrayList<>();
                    for (JsonNode node : pages) {
                        int page = node.path("page").asInt(parts.size() + 1);
                        long cid = node.path("cid").asLong(0);
                        String part = node.path("part").asText("").trim();
                        if (part.isBlank()) part = "第 " + page + " 集";
                        int durationMs = node.path("duration").asInt(0) * 1000;
                        String firstFrame = node.path("first_frame").asText(null);
                        if (firstFrame != null && firstFrame.startsWith("//")) firstFrame = "https:" + firstFrame;
                        else if (firstFrame != null && firstFrame.startsWith("http://")) firstFrame = "https://" + firstFrame.substring(7);
                        parts.add(new BilibiliPartDto(page, cid, part, durationMs, firstFrame));
                    }
                    return parts;
                }
            }
        } catch (Exception e) {
            log.warn("通过 view 获取 B 站分P异常: {}", e.getMessage());
        }

        return List.of();
    }

    /**
     * 从视频网页直接提取内嵌的 window.__playinfo__ (零网关风控拦截)
     */
    private MvStreamInfo fetchPlayStreamFromWebPage(String bvid, Duration timeout) {
        try {
            String pageUrl = "https://www.bilibili.com/video/" + bvid + "/";
            HttpRequest request = HttpRequest.newBuilder(URI.create(pageUrl))
                    .header("Referer", "https://www.bilibili.com/")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", wbi.getCookieHeader())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200 && response.body() != null) {
                String html = response.body();
                String jsonStr = extractJsonFromHtml(html, "window.__playinfo__");
                if (jsonStr != null) {
                    JsonNode root = mapper.readTree(jsonStr);
                    Map<String, String> headers = Map.of(
                            "Referer", pageUrl,
                            "Origin", "https://www.bilibili.com",
                            "User-Agent", USER_AGENT,
                            "Cookie", wbi.getCookieHeader()
                    );
                    MvStreamInfo dashStream = extractDashStream(root, headers);
                    if (dashStream != null) {
                        log.info("成功通过 B 站 Web 网页直出提取 DASH 播放流 ({})", bvid);
                        return dashStream;
                    }
                    MvStreamInfo singleStream = extractSingleStream(root, headers);
                    if (singleStream != null) {
                        log.info("成功通过 B 站 Web 网页直出提取单流播放地址 ({})", bvid);
                        return singleStream;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("从 B 站视频网页直出提取流信息跳过: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 解析视频 CID（包含多P兼容、状态码防御与 412 自愈）
     */
    private long resolveCid(String bvid, Duration timeout) {
        return resolveCid(bvid, 1, timeout);
    }

    /**
     * 解析视频目标分P的 CID（支持指定分集、状态码防御与 412 自愈）
     */
    private long resolveCid(String bvid, int targetPage, Duration timeout) {
        for (int attempt = 1; attempt <= 2; attempt++) {
           try {
               String viewUrl = "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid;
               HttpRequest request = HttpRequest.newBuilder(URI.create(viewUrl))
                        .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                        .header("Origin", "https://www.bilibili.com")
                       .header("User-Agent", USER_AGENT)
                       .header("Cookie", wbi.getCookieHeader())
                        .header("Accept", "application/json, text/plain, */*")
                        .timeout(timeout)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 412) {
                    log.warn("B站获取视频详情返回 HTTP 412 (前置拦截)，第 {} 次尝试刷新凭证...", attempt);
                    wbi.invalidate();
                    wbi.refreshBuvid(httpClient);
                    wbi.refreshWbiKeys(httpClient);
                    continue;
                }

                JsonNode root = safeReadJson(response, "B站视频详情(" + bvid + ")");
                if (root == null) {
                    continue;
                }
                int code = root.path("code").asInt(0);
                if (code == -412) {
                    log.warn("B站获取视频详情业务拦截 code=-412，第 {} 次尝试刷新凭证...", attempt);
                    wbi.invalidate();
                    wbi.refreshBuvid(httpClient);
                    wbi.refreshWbiKeys(httpClient);
                    continue;
                }
                if (code == 0) {
                    JsonNode pages = root.path("data").path("pages");
                    if (pages.isArray() && !pages.isEmpty()) {
                        for (JsonNode pNode : pages) {
                            if (pNode.path("page").asInt(1) == targetPage) {
                                long pageCid = pNode.path("cid").asLong(0);
                                if (pageCid > 0) return pageCid;
                            }
                        }
                        if (targetPage > 0 && targetPage <= pages.size()) {
                            long pageCid = pages.get(targetPage - 1).path("cid").asLong(0);
                            if (pageCid > 0) return pageCid;
                        }
                        long pageCid = pages.get(0).path("cid").asLong(0);
                        if (pageCid > 0) return pageCid;
                    }
                    long cid = root.path("data").path("cid").asLong(0);
                    if (cid > 0) return cid;
                } else {
                    log.warn("B站获取视频详情返回错误: code={}, msg={}", code, root.path("message").asText());
                }
           } catch (Exception e) {
               log.warn("获取 B 站视频 CID 异常 (bvid={}, attempt={}): {}", bvid, attempt, e.getMessage());
           }

            // 备用 CID 获取接口: /x/player/pagelist?bvid= (轻量且防风控)
            try {
                String pageListUrl = "https://api.bilibili.com/x/player/pagelist?bvid=" + bvid;
                HttpRequest plRequest = HttpRequest.newBuilder(URI.create(pageListUrl))
                        .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                        .header("Origin", "https://www.bilibili.com")
                        .header("User-Agent", USER_AGENT)
                        .header("Cookie", wbi.getCookieHeader())
                        .header("Accept", "application/json, text/plain, */*")
                        .timeout(timeout)
                        .GET()
                        .build();

                HttpResponse<String> plResponse = httpClient.send(plRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                JsonNode plRoot = safeReadJson(plResponse, "B站视频分P列表(" + bvid + ")");
                if (plRoot != null && plRoot.path("code").asInt(-1) == 0) {
                    JsonNode dataList = plRoot.path("data");
                    if (dataList.isArray() && !dataList.isEmpty()) {
                        for (JsonNode pNode : dataList) {
                            if (pNode.path("page").asInt(1) == targetPage) {
                                long cid = pNode.path("cid").asLong(0);
                                if (cid > 0) return cid;
                            }
                        }
                        if (targetPage > 0 && targetPage <= dataList.size()) {
                            long cid = dataList.get(targetPage - 1).path("cid").asLong(0);
                            if (cid > 0) return cid;
                        }
                        long cid = dataList.get(0).path("cid").asLong(0);
                        if (cid > 0) return cid;
                    }
                }
            } catch (Exception ignored) {}
       }
       return 0L;
    }

   /**
    * 多阶段尝试解析视频播放流地址
    */
   private MvStreamInfo fetchPlayStreamWithRetry(String bvid, long cid, String desiredResolution, Duration timeout, List<String> failureReasons) {
       int qn = parseQn(desiredResolution);

       for (int attempt = 1; attempt <= 2; attempt++) {
           String cookie = wbi.getCookieHeader();
            // 策略 A: 标准 WBI DASH 协议 (fnval=16 标准高清 DASH，相比 4048 大幅规避 412 风控)
            MvStreamInfo stream = tryWbiPlayurl(bvid, cid, qn, 16, cookie, timeout, failureReasons, attempt);
            if (stream != null) return stream;

            // 策略 B: 传统免 WBI 签名接口 (直接绕过 WBI 网关与风控拦截，fnval=16)
            stream = tryLegacyPlayurl(bvid, cid, qn, 16, cookie, timeout, failureReasons);
            if (stream != null) return stream;

            // 策略 C: HTML5 移动端直出接口 (直接获取 MP4 单流，零 WAF 拦截且无需合流)
            stream = tryHtml5MobilePlayurl(bvid, cid, cookie, timeout, failureReasons);
            if (stream != null) return stream;

            // 策略 D: 传统单流协议 (fnval=0, MP4/FLV 免合流)
            stream = tryLegacySingleStream(bvid, cid, qn, cookie, timeout, failureReasons);
            if (stream != null) return stream;

            // 策略 E: PGC / 番剧音乐区流媒体接口
            stream = tryPgcPlayurl(bvid, cid, qn, cookie, timeout, failureReasons);
            if (stream != null) return stream;

            // 策略 F: 全规格高阶 WBI DASH (fnval=4048 尝试 4K/HDR 独立流)
            stream = tryWbiPlayurl(bvid, cid, qn, 4048, cookie, timeout, failureReasons, attempt);
            if (stream != null) return stream;

            // 若本轮全部策略均未成功且还有下一轮尝试，则主动刷新凭证
            if (attempt < 2) {
                log.warn("B 站流媒体多策略第 {} 轮解析未命中，刷新指纹凭据准备重试 (bvid={})...", attempt, bvid);
                wbi.invalidate();
                wbi.refreshBuvid(httpClient);
                wbi.refreshWbiKeys(httpClient);
            }
        }
        return null;
    }

    /**
     * WBI 签名 playurl 接口
     */
    private MvStreamInfo tryWbiPlayurl(String bvid, long cid, int qn, int fnval, String cookie, Duration timeout, List<String> failureReasons, int attempt) {
        try {
            Map<String, String> playParams = new LinkedHashMap<>();
            playParams.put("bvid", bvid);
            playParams.put("cid", String.valueOf(cid));
            playParams.put("qn", String.valueOf(qn));
            playParams.put("fnval", String.valueOf(fnval));
            playParams.put("fnver", "0");
            playParams.put("fourk", "1");

            String signedQuery = wbi.signQuery(playParams);
            String playUrl = "https://api.bilibili.com/x/player/wbi/playurl?" + signedQuery;

            HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 412) {
                log.warn("B站 WBI 播放流接口(fnval={})返回 HTTP 412 (第 {} 次尝试)", fnval, attempt);
                failureReasons.add("WBI-DASH(fnval=" + fnval + ") HTTP 412 风控拦截");
                wbi.invalidate();
                return null;
            }

            JsonNode root = safeReadJson(response, "B站WBI播放流(fnval=" + fnval + ", " + bvid + ")");
            if (root != null) {
                int code = root.path("code").asInt(0);
                if (code == -412 || code == -403) {
                    log.warn("B站 WBI 播放流(fnval={})业务拦截: code={}, msg={}", fnval, code, root.path("message").asText());
                    failureReasons.add("WBI-DASH(fnval=" + fnval + ") 业务拦截 code=" + code);
                    wbi.invalidate();
                    return null;
                }
                if (code != 0) {
                    log.warn("B站 WBI 播放流(fnval={})返回错误: code={}, msg={}", fnval, code, root.path("message").asText());
                    failureReasons.add("WBI(fnval=" + fnval + ") code=" + code + " " + root.path("message").asText());
                    return null;
                }
                Map<String, String> cdnHeaders = buildCdnHeaders(bvid, cookie);
                MvStreamInfo dashStream = extractDashStream(root, cdnHeaders);
                if (dashStream != null) {
                    log.info("成功通过 B 站 WBI DASH(fnval={}) 解析播放流: {}", fnval, bvid);
                    return dashStream;
                }
                MvStreamInfo singleStream = extractSingleStream(root, cdnHeaders);
                if (singleStream != null) {
                    log.info("成功通过 B 站 WBI 单流(fnval={}) 解析播放流: {}", fnval, bvid);
                    return singleStream;
                }
            }
        } catch (Exception e) {
            log.warn("B站 WBI 播放流(fnval={})异常: {}", fnval, e.getMessage());
            failureReasons.add("WBI(fnval=" + fnval + ") 异常: " + e.getMessage());
        }
        return null;
    }

    /**
     * 传统免 WBI 签名 playurl 接口 (直接绕过 WBI 网关与风控，支持 DASH 与单流)
     */
    private MvStreamInfo tryLegacyPlayurl(String bvid, long cid, int qn, int fnval, String cookie, Duration timeout, List<String> failureReasons) {
        try {
            String playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                    + "&cid=" + cid
                    + "&qn=" + qn
                    + "&fnval=" + fnval
                    + "&fnver=0&fourk=1";

            HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = safeReadJson(response, "B站免签名播放流(fnval=" + fnval + ", " + bvid + ")");
                if (root != null && root.path("code").asInt(-1) == 0) {
                    Map<String, String> cdnHeaders = buildCdnHeaders(bvid, cookie);
                    MvStreamInfo dashStream = extractDashStream(root, cdnHeaders);
                    if (dashStream != null) {
                        log.info("成功通过 B 站免签名 DASH(fnval={}) 解析播放流: {}", fnval, bvid);
                        return dashStream;
                    }
                    MvStreamInfo singleStream = extractSingleStream(root, cdnHeaders);
                    if (singleStream != null) {
                        log.info("成功通过 B 站免签名单流(fnval={}) 解析播放流: {}", fnval, bvid);
                        return singleStream;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("B站免签名接口解析异常 (fnval={}): {}", fnval, e.getMessage());
        }
        return null;
    }

    /**
     * HTML5 移动端直出接口 (直接返回 MP4 单流，免 WAF 风控且无需合流)
     */
    private MvStreamInfo tryHtml5MobilePlayurl(String bvid, long cid, String cookie, Duration timeout, List<String> failureReasons) {
        try {
            String playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                    + "&cid=" + cid
                    + "&qn=80&platform=html5&high_quality=1";

            HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                    .header("Referer", "https://m.bilibili.com/video/" + bvid)
                    .header("Origin", "https://m.bilibili.com")
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1")
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = safeReadJson(response, "B站HTML5移动端播放流(" + bvid + ")");
                if (root != null && root.path("code").asInt(-1) == 0) {
                    Map<String, String> cdnHeaders = Map.of(
                            "Referer", "https://www.bilibili.com/",
                            "User-Agent", USER_AGENT,
                            "Cookie", cookie
                    );
                    MvStreamInfo singleStream = extractSingleStream(root, cdnHeaders);
                    if (singleStream != null) {
                        log.info("成功通过 B 站 HTML5 移动端接口解析 MP4 单流: {}", bvid);
                        return singleStream;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("B站 HTML5 移动端接口解析异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 传统单流协议接口 (fnval=0, MP4/FLV 免合流)
     */
    private MvStreamInfo tryLegacySingleStream(String bvid, long cid, int qn, String cookie, Duration timeout, List<String> failureReasons) {
        try {
            String playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                    + "&cid=" + cid
                    + "&qn=" + qn
                    + "&fnval=0&fnver=0";

            HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = safeReadJson(response, "B站传统单流(fnval=0, " + bvid + ")");
                if (root != null && root.path("code").asInt(-1) == 0) {
                    Map<String, String> cdnHeaders = buildCdnHeaders(bvid, cookie);
                    MvStreamInfo singleStream = extractSingleStream(root, cdnHeaders);
                    if (singleStream != null) {
                        log.info("成功通过 B 站传统单流协议 (fnval=0) 解析播放流: {}", bvid);
                        return singleStream;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("B站传统单流接口解析异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * PGC / 番剧音乐区流媒体接口 (/pgc/player/web/playurl)
     */
    private MvStreamInfo tryPgcPlayurl(String bvid, long cid, int qn, String cookie, Duration timeout, List<String> failureReasons) {
        try {
            String playUrl = "https://api.bilibili.com/pgc/player/web/playurl?bvid=" + bvid
                    + "&cid=" + cid
                    + "&qn=" + qn
                    + "&fnval=16&fnver=0&fourk=1";

            HttpRequest request = HttpRequest.newBuilder(URI.create(playUrl))
                    .header("Referer", "https://www.bilibili.com/video/" + bvid + "/")
                    .header("Origin", "https://www.bilibili.com")
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", cookie)
                    .header("Accept", "application/json, text/plain, */*")
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = safeReadJson(response, "B站PGC播放流(" + bvid + ")");
                if (root != null && root.path("code").asInt(-1) == 0) {
                    Map<String, String> cdnHeaders = buildCdnHeaders(bvid, cookie);
                    MvStreamInfo dashStream = extractDashStream(root, cdnHeaders);
                    if (dashStream != null) {
                        log.info("成功通过 B 站 PGC 接口解析 DASH 播放流: {}", bvid);
                        return dashStream;
                    }
                    MvStreamInfo singleStream = extractSingleStream(root, cdnHeaders);
                    if (singleStream != null) {
                        log.info("成功通过 B 站 PGC 接口解析单流播放流: {}", bvid);
                        return singleStream;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("B站 PGC 接口解析异常: {}", e.getMessage());
        }
        return null;
    }

    private Map<String, String> buildCdnHeaders(String bvid, String cookie) {
        return Map.of(
                "Referer", "https://www.bilibili.com/video/" + bvid + "/",
                "Origin", "https://www.bilibili.com",
                "User-Agent", USER_AGENT,
                "Cookie", cookie
        );
    }
    /**
     * 从 HTML 文本中精确提取指定 JavaScript 变量赋值的大括号闭合 JSON 对象
     */
    static String extractJsonFromHtml(String html, String variableName) {
        if (html == null || !html.contains(variableName)) return null;
        int varIdx = html.indexOf(variableName);
        int eqIdx = html.indexOf('=', varIdx + variableName.length());
        if (eqIdx < 0) return null;
        int braceStart = html.indexOf('{', eqIdx);
        if (braceStart < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = braceStart; i < html.length(); i++) {
            char c = html.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return html.substring(braceStart, i + 1);
                    }
                }
            }
        }
        return null;
    }
    /**
     * 从 JSON 根节点中提取 DASH 分离视频流与音频流地址
     */
    private MvStreamInfo extractDashStream(JsonNode root, Map<String, String> headers) {
        if (root == null) return null;
        JsonNode dash = root.path("data").path("dash");
       if (!dash.isObject() || dash.isEmpty()) {
           dash = root.path("dash");
       }
        if (!dash.isObject() || dash.isEmpty()) {
            dash = root.path("result").path("dash");
        }
       if (!dash.isObject()) return null;

        String videoUrl = firstUrl(dash.path("video"));
        String audioUrl = firstUrl(dash.path("audio"));
        if (videoUrl != null && audioUrl != null) {
            return MvStreamInfo.dash(videoUrl, audioUrl, headers);
        }
        return null;
    }

    /**
     * 从 JSON 根节点中提取传统单文件流 (durl 数组)
     */
    private MvStreamInfo extractSingleStream(JsonNode root, Map<String, String> headers) {
        if (root == null) return null;
        JsonNode durl = root.path("data").path("durl");
       if (!durl.isArray() || durl.isEmpty()) {
           durl = root.path("durl");
       }
        if (!durl.isArray() || durl.isEmpty()) {
            durl = root.path("result").path("durl");
        }
       if (durl.isArray() && !durl.isEmpty()) {
            for (JsonNode item : durl) {
                String url = item.path("url").asText(null);
                if (isValidStreamUrl(url)) {
                    return MvStreamInfo.single(url, headers);
                }
            }
        }
        return null;
    }

    /**
     * 安全读取响应体并解析为 JSON，杜绝 HTML 拦截页导致 Jackson 语法崩溃
     */
    private JsonNode safeReadJson(HttpResponse<String> response, String actionDesc) {
        if (response == null) return null;
        int status = response.statusCode();
        String body = response.body();
        if (body == null || body.isBlank()) {
            log.debug("{} 返回空响应体 (HTTP {})", actionDesc, status);
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("<")) {
            log.warn("{} 遇到 HTML 页面响应 (HTTP {}), 遭遇网关拦截或风控页面", actionDesc, status);
            return null;
        }
        try {
            return mapper.readTree(trimmed);
        } catch (Exception e) {
            log.warn("{} 响应内容解析 JSON 异常 (HTTP {}): {}", actionDesc, status, e.getMessage());
            return null;
        }
    }

    private List<MvSearchItem> parseVideos(JsonNode list) {
        List<MvSearchItem> items = new ArrayList<>();
        if (list == null || !list.isArray()) return items;

        for (JsonNode item : list) {
            String bvid = item.path("bvid").asText(null);
            if (bvid == null || bvid.isBlank()) continue;

            String rawTitle = item.path("title").asText("");
            String title = HTML_TAG.matcher(rawTitle).replaceAll("").trim();
            String author = item.path("author").asText("未知UP主");
            String pic = item.path("pic").asText(null);
            // 规范化封面图片协议：补充缺失的 https 协议头，或将 http 协议升级为 https 防止混合内容拦截
            if (pic != null && pic.startsWith("//")) {
                pic = "https:" + pic;
            } else if (pic != null && pic.startsWith("http://")) {
                pic = "https://" + pic.substring(7);
            }
            int durationMs = parseDuration(item.path("duration").asText("0"));

            items.add(new MvSearchItem(
                    MvProvider.BILIBILI.name(),
                    bvid,
                    title,
                    author,
                    durationMs,
                    pic,
                    "1080p",
                    "https://www.bilibili.com/video/" + bvid
            ));
        }
        return items;
    }

    private String firstUrl(JsonNode streamArray) {
        if (streamArray == null || !streamArray.isArray() || streamArray.isEmpty()) return null;
        for (JsonNode item : streamArray) {
            String url = item.path("baseUrl").asText(null);
            if (isValidStreamUrl(url)) return url;
            url = item.path("base_url").asText(null);
            if (isValidStreamUrl(url)) return url;

            JsonNode backup = item.path("backupUrl");
            if (backup.isArray() && !backup.isEmpty()) {
                String bUrl = backup.get(0).asText(null);
                if (isValidStreamUrl(bUrl)) return bUrl;
            }
            JsonNode backupSnake = item.path("backup_url");
            if (backupSnake.isArray() && !backupSnake.isEmpty()) {
                String bUrl = backupSnake.get(0).asText(null);
                if (isValidStreamUrl(bUrl)) return bUrl;
            }
        }
        return null;
    }

    private static boolean isValidStreamUrl(String url) {
        return url != null && !url.isBlank() && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private int parseDuration(String durStr) {
        if (durStr == null || durStr.isBlank()) return 0;
        try {
            String[] parts = durStr.split(":");
            if (parts.length == 2) {
                int min = Integer.parseInt(parts[0]);
                int sec = Integer.parseInt(parts[1]);
                return (min * 60 + sec) * 1000;
            } else if (parts.length == 3) {
                int hr = Integer.parseInt(parts[0]);
                int min = Integer.parseInt(parts[1]);
                int sec = Integer.parseInt(parts[2]);
                return (hr * 3600 + min * 60 + sec) * 1000;
            }
            return Integer.parseInt(durStr) * 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private int parseQn(String resolution) {
        if (resolution == null) return 80; // 1080P
        if (resolution.contains("720")) return 64; // 720P
        if (resolution.contains("480")) return 32; // 480P
        return 80;
    }
}
