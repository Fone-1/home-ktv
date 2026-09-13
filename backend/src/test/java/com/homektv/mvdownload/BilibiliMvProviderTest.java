package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliMvProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BilibiliMvProvider provider = new BilibiliMvProvider(mapper);

    @Test
    void providerEnumMatches() {
        assertThat(provider.provider()).isEqualTo(MvProvider.BILIBILI);
    }

    @Test
    void parsesBilibiliSearchResultsAndStripsHtmlTags() throws Exception {
        String json = """
                [
                  {
                    "bvid": "BV1xx411c7mD",
                    "title": "【KTV高清伴奏】周杰伦 <em class=\\"keyword\\">晴天</em> 4K原版",
                    "author": "KTV音乐馆",
                    "pic": "//i0.hdslb.com/bfs/archive/pic.jpg",
                    "duration": "04:29"
                  }
                ]
                """;

        var jsonNode = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("parseVideos", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MvSearchItem> items = (List<MvSearchItem>) method.invoke(provider, jsonNode);

        assertThat(items).hasSize(1);
        MvSearchItem item = items.getFirst();
        assertThat(item.provider()).isEqualTo("BILIBILI");
        assertThat(item.externalId()).isEqualTo("BV1xx411c7mD");
        assertThat(item.title()).isEqualTo("【KTV高清伴奏】周杰伦 晴天 4K原版");
        assertThat(item.artist()).isEqualTo("KTV音乐馆");
        assertThat(item.coverUrl()).isEqualTo("https://i0.hdslb.com/bfs/archive/pic.jpg");
        assertThat(item.durationMs()).isEqualTo((4 * 60 + 29) * 1000);
    }

    @Test
    void searchReturnsEmptyForBlankKeyword() {
        assertThat(provider.search("", 20, Duration.ofSeconds(2))).isEmpty();
        assertThat(provider.search("   ", 20, Duration.ofSeconds(2))).isEmpty();
        assertThat(provider.search(null, 20, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void parsesDurationVariations() throws Exception {
        var method = BilibiliMvProvider.class.getDeclaredMethod("parseDuration", String.class);
        method.setAccessible(true);

        // 分秒格式 mm:ss
        int d1 = (int) method.invoke(provider, "03:45");
        assertThat(d1).isEqualTo((3 * 60 + 45) * 1000);

        // 时分秒格式 hh:mm:ss
        int d2 = (int) method.invoke(provider, "01:02:03");
        assertThat(d2).isEqualTo((3600 + 2 * 60 + 3) * 1000);

        // 纯数字秒数
        int d3 = (int) method.invoke(provider, "260");
        assertThat(d3).isEqualTo(260 * 1000);

        // 空字符串或异常
        int d4 = (int) method.invoke(provider, "");
        assertThat(d4).isEqualTo(0);
    }

    @Test
    void parsesEmptyOrNonArrayGracefully() throws Exception {
        var method = BilibiliMvProvider.class.getDeclaredMethod("parseVideos", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<MvSearchItem> items1 = (List<MvSearchItem>) method.invoke(provider, mapper.readTree("{}"));
        assertThat(items1).isEmpty();

        @SuppressWarnings("unchecked")
        List<MvSearchItem> items2 = (List<MvSearchItem>) method.invoke(provider, (Object) null);
        assertThat(items2).isEmpty();
    }

    @Test
    void testExtractDashStreamWithBackupUrls() throws Exception {
        String json = """
                {
                  "code": 0,
                  "data": {
                    "dash": {
                      "video": [
                        {
                          "id": 80,
                          "baseUrl": "https://cn-cd.bilivideo.com/video.m4s",
                          "backupUrl": ["https://backup.bilivideo.com/video.m4s"]
                        }
                      ],
                      "audio": [
                        {
                          "id": 30280,
                          "base_url": "https://cn-cd.bilivideo.com/audio.m4s"
                        }
                      ]
                    }
                  }
                }
                """;
        var root = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("extractDashStream", com.fasterxml.jackson.databind.JsonNode.class, java.util.Map.class);
        method.setAccessible(true);
        MvStreamInfo info = (MvStreamInfo) method.invoke(provider, root, java.util.Map.of("Referer", "https://www.bilibili.com/"));

        assertThat(info).isNotNull();
        assertThat(info.isDash()).isTrue();
        assertThat(info.videoUrl()).isEqualTo("https://cn-cd.bilivideo.com/video.m4s");
        assertThat(info.audioUrl()).isEqualTo("https://cn-cd.bilivideo.com/audio.m4s");
    }

    @Test
    void testExtractSingleStreamFromDurl() throws Exception {
        String json = """
                {
                  "code": 0,
                  "data": {
                    "durl": [
                      {
                        "order": 1,
                        "length": 15000000,
                        "url": "https://upos-sz-mirror08c.bilivideo.com/single.mp4"
                      }
                    ]
                  }
                }
                """;
        var root = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("extractSingleStream", com.fasterxml.jackson.databind.JsonNode.class, java.util.Map.class);
        method.setAccessible(true);
        MvStreamInfo info = (MvStreamInfo) method.invoke(provider, root, java.util.Map.of("Referer", "https://www.bilibili.com/"));

        assertThat(info).isNotNull();
        assertThat(info.isDash()).isFalse();
        assertThat(info.videoUrl()).isEqualTo("https://upos-sz-mirror08c.bilivideo.com/single.mp4");
    }

    @Test
    void testFirstUrlPrefersBackupWhenBaseMissing() throws Exception {
        String json = """
                [
                  {
                    "id": 80,
                    "baseUrl": "",
                    "backupUrl": ["https://backup.bilivideo.com/stream.m4s"]
                  }
                ]
                """;
        var array = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("firstUrl", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        String url = (String) method.invoke(provider, array);
        assertThat(url).isEqualTo("https://backup.bilivideo.com/stream.m4s");
    }

    @Test
    void testExtractJsonFromHtml() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                <script>window.__playinfo__={"code":0,"data":{"dash":{"video":[{"baseUrl":"https://video.mp4"}]}}};</script>
                </head>
                <body></body>
                </html>
                """;
        String json = BilibiliMvProvider.extractJsonFromHtml(html, "window.__playinfo__");
        assertThat(json).isNotNull();
        assertThat(json).startsWith("{\"code\":0");
        assertThat(json).endsWith("}}");

        // 不存在的变量返回 null
        assertThat(BilibiliMvProvider.extractJsonFromHtml(html, "window.__unknown__")).isNull();
       // 空串防御
       assertThat(BilibiliMvProvider.extractJsonFromHtml(null, "window.__playinfo__")).isNull();
   }

    @Test
    void testExtractDashStreamFromResultDash() throws Exception {
        String json = """
                {
                  "code": 0,
                  "result": {
                    "dash": {
                      "video": [
                        {
                          "id": 80,
                          "baseUrl": "https://pgc.bilivideo.com/video.m4s"
                        }
                      ],
                      "audio": [
                        {
                          "id": 30280,
                          "base_url": "https://pgc.bilivideo.com/audio.m4s"
                        }
                      ]
                    }
                  }
                }
                """;
        var root = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("extractDashStream", com.fasterxml.jackson.databind.JsonNode.class, java.util.Map.class);
        method.setAccessible(true);
        MvStreamInfo info = (MvStreamInfo) method.invoke(provider, root, java.util.Map.of("Referer", "https://www.bilibili.com/"));

        assertThat(info).isNotNull();
        assertThat(info.isDash()).isTrue();
        assertThat(info.videoUrl()).isEqualTo("https://pgc.bilivideo.com/video.m4s");
        assertThat(info.audioUrl()).isEqualTo("https://pgc.bilivideo.com/audio.m4s");
    }

    @Test
    void testExtractSingleStreamFromResultDurl() throws Exception {
        String json = """
                {
                  "code": 0,
                  "result": {
                    "durl": [
                      {
                        "order": 1,
                        "url": "https://pgc.bilivideo.com/single.mp4"
                      }
                    ]
                  }
                }
                """;
        var root = mapper.readTree(json);
        var method = BilibiliMvProvider.class.getDeclaredMethod("extractSingleStream", com.fasterxml.jackson.databind.JsonNode.class, java.util.Map.class);
        method.setAccessible(true);
        MvStreamInfo info = (MvStreamInfo) method.invoke(provider, root, java.util.Map.of("Referer", "https://www.bilibili.com/"));

        assertThat(info).isNotNull();
        assertThat(info.isDash()).isFalse();
        assertThat(info.videoUrl()).isEqualTo("https://pgc.bilivideo.com/single.mp4");
    }

    @Test
    void testBuildCdnHeadersIncludesTrailingSlashAndOrigin() throws Exception {
        var method = BilibiliMvProvider.class.getDeclaredMethod("buildCdnHeaders", String.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        var headers = (java.util.Map<String, String>) method.invoke(provider, "BV1f55i6zEtP", "buvid3=test;");

        assertThat(headers.get("Referer")).isEqualTo("https://www.bilibili.com/video/BV1f55i6zEtP/");
        assertThat(headers.get("Origin")).isEqualTo("https://www.bilibili.com");
        assertThat(headers.get("Cookie")).isEqualTo("buvid3=test;");
    }
    @Test
    void testParseExternalIdVariations() {
        var t1 = BilibiliMvProvider.parseExternalId("BV1xx411c7mD");
        assertThat(t1.bvid()).isEqualTo("BV1xx411c7mD");
        assertThat(t1.page()).isEqualTo(1);
        assertThat(t1.cid()).isEqualTo(0L);

        var t2 = BilibiliMvProvider.parseExternalId("BV1xx411c7mD?p=5");
        assertThat(t2.bvid()).isEqualTo("BV1xx411c7mD");
        assertThat(t2.page()).isEqualTo(5);
        assertThat(t2.cid()).isEqualTo(0L);

        var t3 = BilibiliMvProvider.parseExternalId("BV1xx411c7mD?p=3&cid=889900");
        assertThat(t3.bvid()).isEqualTo("BV1xx411c7mD");
        assertThat(t3.page()).isEqualTo(3);
        assertThat(t3.cid()).isEqualTo(889900L);

        var t4 = BilibiliMvProvider.parseExternalId("BV1xx411c7mD:4:776655");
        assertThat(t4.bvid()).isEqualTo("BV1xx411c7mD");
        assertThat(t4.page()).isEqualTo(4);
        assertThat(t4.cid()).isEqualTo(776655L);
    }
}
