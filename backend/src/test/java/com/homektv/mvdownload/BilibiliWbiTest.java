package com.homektv.mvdownload;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BilibiliWbiTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BilibiliWbi wbi = new BilibiliWbi(mapper);

    @Test
    void testMixinKeyCalculation() {
        String imgKey = "653657f524a547ac981ded72ea1727cc";
        String subKey = "ee0eb170b1a147cb90a6ae822954d3e3";
        String mixinKey = BilibiliWbi.calculateMixinKey(imgKey, subKey);

        // 根据 B 站官方置换表映射结果
        assertThat(mixinKey).isEqualTo("cb13e22ecaac567e7170e4ad72a04717");
    }

    @Test
    void testSignQueryStandardVector() {
        String mixinKey = "cb13e22ecaac567e7170e4ad72a04717";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", "周杰伦 晴天 KTV");
        params.put("page", "1");
        params.put("pagesize", "20");
        params.put("search_type", "video");
        params.put("wts", "1683000000");

        String signed = BilibiliWbi.signQuery(params, mixinKey);

        // 期望按字典序排序编码且附加准确的 w_rid
        assertThat(signed).contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E6%99%B4%E5%A4%A9%20KTV");
        assertThat(signed).contains("wts=1683000000");
        assertThat(signed).contains("w_rid=f59d863798b92839287829deb725d8ee");
    }

    @Test
    void testFilterSpecialCharactersInParamValues() {
        String mixinKey = "cb13e22ecaac567e7170e4ad72a04717";
        Map<String, String> params = new LinkedHashMap<>();
        // 包含 B 站 WBI 规范中需过滤的字符：! ' ( ) *
        params.put("keyword", "周杰伦 (晴天)*!'");
        params.put("wts", "1683000000");

        String signed = BilibiliWbi.signQuery(params, mixinKey);

        // 过滤后值应当为 "周杰伦 晴天"
        assertThat(signed).contains("keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6%20%E6%99%B4%E5%A4%A9");
        assertThat(signed).doesNotContain("%21"); // !
        assertThat(signed).doesNotContain("%27"); // '
        assertThat(signed).doesNotContain("%28"); // (
        assertThat(signed).doesNotContain("%29"); // )
        assertThat(signed).doesNotContain("%2A"); // *
    }

    @Test
    void testCookieHeaderGeneration() {
        String cookie = wbi.getCookieHeader();
        assertThat(cookie).isNotBlank();
        assertThat(cookie).contains("buvid3=");
        assertThat(cookie).contains("infoc");
        assertThat(cookie).contains("b_nut=");
    }

    @Test
    void testEncodeRfc3986() {
        String encoded = BilibiliWbi.encodeRfc3986("a b*c~d");
        assertThat(encoded).isEqualTo("a%20b%2Ac~d");
    }
}
