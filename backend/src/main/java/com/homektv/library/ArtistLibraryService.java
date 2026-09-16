package com.homektv.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.ai.AiConfigService;
import com.homektv.ai.OpenAiCompatibleClient;
import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * 歌手库服务：按歌手名称聚合歌曲，提供性别 AI 建议和人工复核应用。
 * 同名歌手不会自动合并，复核时展示代表歌曲帮助管理员判断。
 */
@Service
public class ArtistLibraryService {
    private static final Set<String> GENDERS = Set.of("男歌手", "女歌手", "组合", "未知");

    private final SongRepository songs;
    private final AiConfigService aiConfig;
    private final OpenAiCompatibleClient aiClient;
    private final ObjectMapper mapper;

    public ArtistLibraryService(SongRepository songs, AiConfigService aiConfig,
                                OpenAiCompatibleClient aiClient, ObjectMapper mapper) {
        this.songs = songs;
        this.aiConfig = aiConfig;
        this.aiClient = aiClient;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> list(String keyword, String gender, Boolean reviewed, int limit) {
        return page(keyword, gender, reviewed, 0, Math.max(1, Math.min(limit, 5000))).getContent();
    }

    /**
     * 管理后台歌手列表分页。优先走 SQL GROUP BY 聚合；单元测试没有原生查询时回落到内存分组。
     */
    public Page<Map<String, Object>> page(String keyword, String gender, Boolean reviewed, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        String query = keyword == null ? "" : keyword.trim();
        String genderFilter = gender == null ? "" : gender.trim();
        // 复核状态以字符串传入，避免原生查询绑定可空布尔参数时的类型推断问题
        String reviewedFilter = reviewed == null ? "" : reviewed.toString();
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<Object[]> aggregated = songs.pageAdminArtists(query, genderFilter, reviewedFilter, pageable);
        if (aggregated != null) {
            // 原生查询已执行：空页表示没有匹配歌手，不能再全表回落
            List<Map<String, Object>> content = aggregated.getContent().stream()
                    .map(this::artistFromAggregate)
                    .toList();
            return new PageImpl<>(content, pageable, aggregated.getTotalElements());
        }
        List<Map<String, Object>> all = memoryList(query, genderFilter, reviewed);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new PageImpl<>(all.subList(from, to), pageable, all.size());
    }

    /** 对一个歌手的代表歌曲进行 AI 分析；没有 AI 时返回可人工填写的未知建议。 */
    public Map<String, Object> analyze(String artist) {
        List<Song> matches = songsFor(artist);
        if (matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        return analyze(artist, matches);
    }

    private Map<String, Object> analyze(String artist, List<Song> matches) {
        List<Song> samples = representativeSongs(matches);
        if (!aiConfig.isConfigured()) return suggestion("未知", 0, "LOCAL", "未配置 AI，无法可靠推断歌手类型，请人工复核", samples);
        try {
            String sampleJson = mapper.writeValueAsString(samples.stream().map(song -> Map.of(
                    "title", song.getTitle(), "artist", song.getArtist(), "language", song.getLanguage(),
                    "vocalForm", song.getVocalForm(), "tags", song.getTags() == null ? List.of() : Arrays.asList(song.getTags()))).toList());
            JsonNode result = aiClient.completeJsonPromptOnly("BULK",
                    "你是 KTV 歌手资料审核助手。根据同名歌手的代表歌曲判断歌手类型。只返回 JSON：gender（男歌手、女歌手、组合、未知之一）、confidence（0到1）、reason。证据不足必须返回未知，不要猜测。",
                    "歌手名称：" + artist + "\n代表歌曲：" + sampleJson, 700);
            String value = result.path("gender").asText(result.path("artistGender").asText("未知"));
            if (!GENDERS.contains(value)) value = "未知";
            double confidence = Math.max(0, Math.min(1, result.path("confidence").asDouble(0)));
            return suggestion(value, confidence, "AI", result.path("reason").asText("请人工确认"), samples);
        } catch (RuntimeException | java.io.IOException failure) {
            return suggestion("未知", 0, "LOCAL", "AI 调用失败，请人工复核：" + safeMessage(failure), samples);
        }
    }

    /** 批量分析歌手，只返回建议，不自动写回歌曲。 */
    public List<Map<String, Object>> analyzeBatch(Collection<String> artists) {
        if (artists == null) return List.of();
        List<String> names = artists.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(500)
                .toList();
        if (names.isEmpty()) return List.of();
        Map<String, List<Song>> grouped = validSongs().stream().collect(Collectors.groupingBy(
                song -> normalizeArtist(song.getArtist()), LinkedHashMap::new, Collectors.toList()));
        AiConfigService.ResolvedConfig config = aiConfig.resolve();
        int configuredConcurrency = config == null ? 1 : config.bulkConcurrency();
        int concurrency = Math.max(1, Math.min(configuredConcurrency, names.size()));
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            List<CompletableFuture<Map<String, Object>>> futures = names.stream()
                    .map(artist -> CompletableFuture.supplyAsync(
                            () -> analyzeBatchItem(artist, grouped.get(normalizeArtist(artist))), executor))
                    .toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private Map<String, Object> analyzeBatchItem(String artist, List<Song> matches) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artist", artist);
        try {
            if (matches == null || matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
            result.putAll(analyze(artist, matches));
        } catch (RuntimeException failure) {
            result.putAll(suggestion("未知", 0, "LOCAL", "分析失败，请人工复核：" + safeMessage(failure), List.of()));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> apply(String artist, String gender) {
        if (!GENDERS.contains(gender)) throw new ApiException("INVALID_ARTIST_GENDER", "歌手类型无效");
        List<Song> matches = songsFor(artist);
        if (matches.isEmpty()) throw new ApiException("ARTIST_NOT_FOUND", "歌手不存在");
        matches.forEach(song -> {
            song.setArtistGender(gender);
            song.lockMetadata("artistGender");
        });
        songs.saveAll(matches);
        return Map.of("artist", artist, "gender", gender, "updated", matches.size());
    }

    private List<Map<String, Object>> memoryList(String keyword, String gender, Boolean reviewed) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Song>> grouped = validSongs().stream()
                .collect(Collectors.groupingBy(song -> song.getArtist() == null || song.getArtist().isBlank() ? "未知歌手" : song.getArtist().trim(),
                        LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .filter(entry -> query.isBlank() || entry.getKey().toLowerCase(Locale.ROOT).contains(query))
                .map(entry -> artistValue(entry.getKey(), entry.getValue()))
                .filter(value -> gender == null || gender.isBlank() || gender.equals(value.get("gender")))
                .filter(value -> reviewed == null || reviewed == ((Boolean) value.get("reviewed")))
                .sorted(Comparator.comparingInt((Map<String, Object> value) -> (Integer) value.get("songCount"))
                        .reversed().thenComparing(value -> (String) value.get("name")))
                .toList();
    }

    private Map<String, Object> artistFromAggregate(Object[] row) {
        String name = row[0] == null ? "未知歌手" : row[0].toString();
        int songCount = row[1] instanceof Number number ? number.intValue() : 0;
        String gender = row[2] == null || row[2].toString().isBlank() ? "未知" : row[2].toString();
        boolean reviewed = Boolean.TRUE.equals(row[3]);
        List<Map<String, Object>> samples = representativeSongs(songsFor(name)).stream().map(this::songValue).toList();
        return Map.of("name", name, "gender", gender, "reviewed", reviewed, "songCount", songCount, "songs", samples);
    }

    private List<Song> validSongs() {
        List<Song> ok = songs.findByStatus("ok");
        if (ok != null && !ok.isEmpty()) return ok;
        return songs.findAll().stream().filter(song -> "ok".equals(song.getStatus())).toList();
    }

    private List<Song> songsFor(String artist) {
        if (artist == null || artist.isBlank()) return List.of();
        String normalized = normalizeArtist(artist);
        List<Song> matched = songs.findByStatusAndArtistIgnoreCase("ok", artist.trim());
        if (matched != null && !matched.isEmpty()) {
            return matched.stream().filter(song -> normalized.equals(normalizeArtist(song.getArtist()))).toList();
        }
        return validSongs().stream().filter(song -> normalized.equals(normalizeArtist(song.getArtist()))).toList();
    }

    private static String normalizeArtist(String artist) {
        return artist == null ? "" : artist.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> artistValue(String name, List<Song> values) {
        String gender = dominantGender(values);
        boolean reviewed = !"未知".equals(gender) && values.stream()
                .allMatch(song -> gender.equals(song.getArtistGender()) && song.isMetadataLocked("artistGender"));
        return Map.of("name", name, "gender", gender, "reviewed", reviewed,
                "songCount", values.size(), "songs", representativeSongs(values).stream().map(this::songValue).toList());
    }

    private String dominantGender(List<Song> values) {
        return values.stream().map(Song::getArtistGender).filter(GENDERS::contains).filter(value -> !"未知".equals(value))
                .collect(Collectors.groupingBy(value -> value, Collectors.counting())).entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private List<Song> representativeSongs(List<Song> values) {
        return values.stream().sorted(Comparator.comparingInt(Song::getPlayCount).reversed().thenComparing(Song::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))).limit(5).toList();
    }

    private Map<String, Object> songValue(Song song) {
        return Map.of("id", song.getId(), "title", song.getTitle(), "artist", song.getArtist(),
                "language", song.getLanguage(), "mediaType", song.getMediaType(), "coverUrl",
                song.getCoverPath() == null ? "" : "/api/cover/" + song.getId());
    }

    private Map<String, Object> suggestion(String gender, double confidence, String source, String reason, List<Song> samples) {
        return Map.of("gender", gender, "confidence", confidence, "source", source, "reason", reason,
                "songs", samples.stream().map(this::songValue).toList());
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }
}
