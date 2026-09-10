package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NeteaseMvProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NeteaseMvProvider provider = new NeteaseMvProvider(mapper);

    @Test
    void providerEnumMatches() {
        assertThat(provider.provider()).isEqualTo(MvProvider.NETEASE);
    }

    @Test
    void parsesNeteaseMvSearchResults() throws Exception {
        String json = """
                [
                  {
                    "id": 14458921,
                    "name": "起风了 (官方MV)",
                    "artistName": "买辣椒也用券",
                    "cover": "http://p1.music.126.net/cover.jpg",
                    "duration": 312000
                  }
                ]
                """;

        var jsonNode = mapper.readTree(json);
        // 利用反射或包级方法测试解析逻辑
        var method = NeteaseMvProvider.class.getDeclaredMethod("parseMvs", com.fasterxml.jackson.databind.JsonNode.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<MvSearchItem> items = (List<MvSearchItem>) method.invoke(provider, jsonNode);

        assertThat(items).hasSize(1);
        MvSearchItem item = items.getFirst();
        assertThat(item.provider()).isEqualTo("NETEASE");
        assertThat(item.externalId()).isEqualTo("14458921");
        assertThat(item.title()).isEqualTo("起风了 (官方MV)");
        assertThat(item.artist()).isEqualTo("买辣椒也用券");
        assertThat(item.coverUrl()).isEqualTo("https://p1.music.126.net/cover.jpg");
        assertThat(item.durationMs()).isEqualTo(312000);
    }
}
