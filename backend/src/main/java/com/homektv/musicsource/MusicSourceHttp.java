package com.homektv.musicsource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.config.SslContextHelper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class MusicSourceHttp {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(SslContextHelper.trustAllSslContext())
            .build();
    private final ObjectMapper mapper;
    private final MusicProvider provider;
    private final Set<String> allowedHosts;

    MusicSourceHttp(ObjectMapper mapper, MusicProvider provider, Set<String> allowedHosts) {
        this.mapper = mapper;
        this.provider = provider;
        this.allowedHosts = allowedHosts;
    }

    JsonNode get(String url, Map<String, ?> headers, Duration timeout) {
        return send(HttpRequest.newBuilder(checked(url)).GET(), headers, timeout);
    }

    JsonNode form(String url, Map<String, String> form, Map<String, ?> headers, Duration timeout) {
        String body = query(form);
        return send(HttpRequest.newBuilder(checked(url)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)), headers, timeout);
    }

    private JsonNode send(HttpRequest.Builder builder, Map<String, ?> headers, Duration timeout) {
        headers.forEach((key, value) -> builder.header(key, String.valueOf(value)));
        builder.timeout(timeout).header("Accept", "application/json");
        if (headers.keySet().stream().noneMatch(key -> "user-agent".equalsIgnoreCase(key))) {
            builder.header("User-Agent", "HomeKTV/0.1 metadata-only");
        }
        HttpRequest request = builder.build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new MusicSourceException(provider, "上游返回 HTTP " + response.statusCode());
            if (response.body().length() > 5_000_000) throw new MusicSourceException(provider, "上游响应过大");
            return mapper.readTree(response.body());
        } catch (MusicSourceException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new MusicSourceException(provider, "平台请求失败", ex);
        }
    }

    private URI checked(String url) {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !allowedHosts.contains(uri.getHost()))
            throw new MusicSourceException(provider, "请求地址不在平台白名单内");
        return uri;
    }

    static String query(Map<String, ?> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> {
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    static Map<String, String> stringMap() { return new LinkedHashMap<>(); }
}
