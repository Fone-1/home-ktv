package com.homektv.musicsource;

import com.homektv.domain.Song;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalTrackMatcherTest {

    private ExternalTrackMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new ExternalTrackMatcher();
    }

    @Test
    void matchesCleanTrackFromMessyKtvTitleAndUploaderArtist() {
        // 用户实际场景 1：下载或入库歌名为 "002.周杰伦-晴天 - 1"，本地歌手为 UP主 "超级爱下雨天"
        Song song = new Song();
        song.setTitle("002.周杰伦-晴天 - 1");
        song.setArtist("超级爱下雨天");
        song.setDurationMs(269_760);

        ExternalTrack correctTrack = new ExternalTrack(
                MusicProvider.KUGOU, "hash1", "晴天", List.of("周杰伦"),
                "叶惠美", 269_000, "2003-07-31", List.of(), null, "AVAILABLE", null);

        double score = matcher.score(song, correctTrack);
        // 置信度应接近 1.0 (>= 0.96)，足以触发 0.95 自动写入阈值
        assertThat(score).isGreaterThanOrEqualTo(0.96);

        // 验证生成的候选搜索词包含 "周杰伦 晴天"
        List<String> queries = matcher.generateCandidateQueries(song);
        assertThat(queries).contains("周杰伦 晴天");
    }

    @Test
    void matchesCleanTrackFromMessyTitleSongTwo() {
        // 用户实际场景 2："111.萧敬腾-王妃 - 3"，本地歌手为 UP主 "超级爱下雨天"
        Song song = new Song();
        song.setTitle("111.萧敬腾-王妃 - 3");
        song.setArtist("超级爱下雨天");
        song.setDurationMs(221_803);

        ExternalTrack correctTrack = new ExternalTrack(
                MusicProvider.KUGOU, "hash2", "王妃", List.of("萧敬腾"),
                "王妃", 221_000, "2009-07-17", List.of(), null, "AVAILABLE", null);

        double score = matcher.score(song, correctTrack);
        assertThat(score).isGreaterThanOrEqualTo(0.96);

        List<String> queries = matcher.generateCandidateQueries(song);
        assertThat(queries).contains("萧敬腾 王妃");
    }

    @Test
    void rejectsBogusSuggestionEvenIfUploaderMatches() {
        // 用户原遇到的严重误匹配：酷狗因搜 "超级爱下雨天" 返回了由该用户上传的无关歌曲
        Song song = new Song();
        song.setTitle("002.周杰伦-晴天 - 1");
        song.setArtist("超级爱下雨天");
        song.setDurationMs(269_760);

        ExternalTrack bogusTrack = new ExternalTrack(
                MusicProvider.KUGOU, "bogus1", "014.海豚湾恋人-遗失的美好", List.of("超级爱下雨天"),
                null, 281_000, null, List.of(), null, "AVAILABLE", null);

        double score = matcher.score(song, bogusTrack);
        // 歌名完全不匹配时，防误判熔断机制应将置信度压低至 0.10 以下
        assertThat(score).isLessThan(0.10);
    }

    @Test
    void matchesSongWithMissingOrUnknownArtist() {
        // 本地歌曲歌手未知，但歌名与时长完全吻合
        Song song = new Song();
        song.setTitle("晴天");
        song.setArtist("未知歌手");
        song.setDurationMs(269_760);

        ExternalTrack track = new ExternalTrack(
                MusicProvider.KUGOU, "hash1", "晴天", List.of("周杰伦"),
                "叶惠美", 269_000, "2003-07-31", List.of(), null, "AVAILABLE", null);

        double score = matcher.score(song, track);
        // 允许用户补全缺失歌手，置信度应高于 0.90
        assertThat(score).isGreaterThanOrEqualTo(0.95);
    }

    @Test
    void matchesReversedTitleArtistPattern() {
        // 本地歌名为 "歌名 - 歌手"（例如 "晴天 - 周杰伦"）
        Song song = new Song();
        song.setTitle("晴天 - 周杰伦");
        song.setArtist("");
        song.setDurationMs(269_760);

        ExternalTrack track = new ExternalTrack(
                MusicProvider.KUGOU, "hash1", "晴天", List.of("周杰伦"),
                "叶惠美", 269_000, "2003-07-31", List.of(), null, "AVAILABLE", null);

        double score = matcher.score(song, track);
        assertThat(score).isGreaterThanOrEqualTo(0.96);
    }

    @Test
    void stripsKtvTagsAndIgnoresNoise() {
        assertThat(ExternalTrackMatcher.cleanTitleNoise("002.周杰伦-晴天 - 1")).isEqualTo("周杰伦-晴天");
        assertThat(ExternalTrackMatcher.cleanTitleNoise("一生所爱(MTV)-粤语-流行")).isEqualTo("一生所爱");
        assertThat(ExternalTrackMatcher.cleanTitleNoise("王妃 [1080P 高清 (Live)]")).isEqualTo("王妃");
    }
}
