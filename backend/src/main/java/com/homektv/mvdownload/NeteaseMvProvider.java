package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.homektv.musicsource.NeteaseCrypto;
import com.homektv.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网易云音乐在线 MV 提供者：
 * 1. 使用 EAPI 协议搜索 MV (type=1004)
 * 2. 使用 WEAPI 协议换取 1080P/720P MP4 完整直链
 */
@Component
public class NeteaseMvProvider implements MvSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(NeteaseMvProvider.class);
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";
    private static final String REFERER = "https://music.163.com/";

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public NeteaseMvProvider(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public MvProvider provider() {
        return MvProvider.NETEASE;
    }

    @Override
    public List<MvSearchItem> search(String keyword, int limit, Duration timeout) {
        try {
            ObjectNode data = mapper.createObjectNode();
            data.put("s", keyword);
            data.put("type", 1004); // 1004: MV 检索
            data.put("limit", Math.min(limit, 30));
            data.put("offset", 0);
            data.put("total", true);
            ObjectNode header = data.putObject("header");
            header.put("os", "pc");
            header.put("appver", "3.1.0");
            header.put("requestId", String.valueOf(System.currentTimeMillis()));

            String path = "/api/cloudsearch/pc";
            String encryptedParams = NeteaseCrypto.eapi(path, compact(data));
            String formBody = "params=" + java.net.URLEncoder.encode(encryptedParams, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://interfacepc.music.163.com/eapi/cloudsearch/pc"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("网易云 MV 搜索返回 HTTP {}", response.statusCode());
                return List.of();
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode mvs = root.path("result").path("mvs");
            return parseMvs(mvs);
        } catch (Exception e) {
            log.warn("网易云 MV 搜索异常: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public MvStreamInfo resolveStream(String externalId, String desiredResolution, Duration timeout) {
        long mvId = parseMvId(externalId);
        // 1. 尝试 WEAPI 接口
        try {
            ObjectNode data = mapper.createObjectNode();
            data.put("id", mvId);
            data.put("r", parseResolutionRate(desiredResolution));
            data.put("csrf_token", "");

            NeteaseCrypto.WeapiPayload payload = NeteaseCrypto.weapi(compact(data));
            String formBody = "params=" + java.net.URLEncoder.encode(payload.params(), StandardCharsets.UTF_8)
                    + "&encSecKey=" + java.net.URLEncoder.encode(payload.encSecKey(), StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(URI.create("https://music.163.com/weapi/song/enhance/play/mv/url"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "os=pc; appver=3.1.0; osver=Microsoft-Windows-10-Professional-build-19045-64bit;")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String url = root.path("data").path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    return MvStreamInfo.single(url, Map.of("Referer", REFERER, "User-Agent", USER_AGENT));
                }
            }
        } catch (Exception e) {
            log.debug("WEAPI 换取 MV 地址失败，尝试备用接口: {}", e.getMessage());
        }

        // 2. 备用公开接口 /api/mv/detail
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://music.163.com/api/mv/detail?id=" + mvId + "&type=mp4"))
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                JsonNode brs = root.path("data").path("brs");
                String url = null;
                for (String r : List.of("1080", "720", "480", "240")) {
                    if (brs.has(r)) {
                        url = brs.path(r).asText(null);
                        if (url != null && !url.isBlank()) break;
                    }
                }
                if (url == null && brs.isObject()) {
                    var it = brs.elements();
                    while (it.hasNext()) {
                        String candidate = it.next().asText(null);
                        if (candidate != null && !candidate.isBlank()) {
                            url = candidate;
                            break;
                        }
                    }
                }
                if (url != null && !url.isBlank()) {
                    return MvStreamInfo.single(url, Map.of("Referer", REFERER, "User-Agent", USER_AGENT));
                }
            }
        } catch (Exception e) {
            log.warn("备用接口换取 MV 地址失败: {}", e.getMessage());
        }

        // 3. 备用接口 /api/mv/url
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://interface.music.163.com/api/mv/url?id=" + mvId + "&r=" + parseResolutionRate(desiredResolution)))
                    .header("Referer", REFERER)
                    .header("User-Agent", USER_AGENT)
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                String url = root.path("data").path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    return MvStreamInfo.single(url, Map.of("Referer", REFERER, "User-Agent", USER_AGENT));
                }
            }
        } catch (Exception e) {
            log.debug("第 3 备用接口换取 MV 地址失败: {}", e.getMessage());
        }

        throw new ApiException("MV_STREAM_NOT_FOUND", "未能获取网易云 MV 视频下载地址");
    }

    private List<MvSearchItem> parseMvs(JsonNode mvs) {
        List<MvSearchItem> result = new ArrayList<>();
        if (mvs == null || !mvs.isArray()) return result;

        for (JsonNode mv : mvs) {
            String id = mv.path("id").asText();
            String name = mv.path("name").asText("未知MV");
            String artist = mv.path("artistName").asText(null);
            if (artist == null && mv.has("artists")) {
                List<String> artists = new ArrayList<>();
                for (JsonNode a : mv.path("artists")) artists.add(a.path("name").asText());
                artist = String.join("/", artists);
            }
            if (artist == null || artist.isBlank()) artist = "未知歌手";

            String cover = mv.path("cover").asText(null);
            if (cover != null && cover.startsWith("http://")) {
                cover = cover.replace("http://", "https://");
            }
            int duration = mv.path("duration").asInt(0);

            result.add(new MvSearchItem(
                    MvProvider.NETEASE.name(),
                    id,
                    name,
                    artist,
                    duration,
                    cover,
                    "1080p",
                    "https://music.163.com/#/mv?id=" + id
            ));
        }
        return result;
    }

    private int parseResolutionRate(String resolution) {
        if (resolution == null) return 1080;
        if (resolution.contains("720")) return 720;
        if (resolution.contains("480")) return 480;
        return 1080;
    }

    private long parseMvId(String externalId) {
        try {
            return Long.parseLong(externalId.trim());
        } catch (Exception e) {
            throw new ApiException("EXTERNAL_TRACK_ID_INVALID", "网易云 MV ID 必须是数字：" + externalId);
        }
    }

    private String compact(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
