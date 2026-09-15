package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongRepository;
import com.homektv.web.dto.SongDto;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CategoryBrowseService {
    private final SongRepository songRepository;

    public CategoryBrowseService(SongRepository songRepository) {
        this.songRepository = songRepository;
    }

    public List<Map<String, Object>> artists() {
        List<Object[]> rows = songRepository.aggregateBrowseArtists();
        if (rows != null && !rows.isEmpty()) {
            return rows.stream()
                    .map(row -> Map.<String, Object>of(
                            "name", stringOf(row[0]),
                            "initial", stringOf(row[1]),
                            "gender", row.length > 3 && !stringOf(row[3]).isBlank() ? stringOf(row[3]) : "未知",
                            "songCount", numberOf(row[2])))
                    .toList();
        }
        return fallbackArtists();
    }

    public List<Map<String, Object>> languages() {
        List<Object[]> rows = songRepository.aggregateLanguages();
        if (rows != null && !rows.isEmpty()) return toNamedCounts(rows);
        return counts(validSongs(), Song::getLanguage);
    }

    public List<Map<String, Object>> tags() {
        List<Object[]> rows = songRepository.aggregateBrowseTags();
        if (rows != null && !rows.isEmpty()) return toNamedCounts(rows);
        return fallbackTags();
    }

    public List<SongDto> songs(String artist, String artistGender, String language, String tag, String vocalForm, String sort, int limit) {
        return songs(artist, artistGender, language, tag, vocalForm, null, sort, limit);
    }

    public List<SongDto> songs(String artist, String artistGender, String language, String tag, String vocalForm, String mediaType, String sort, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        Comparator<Song> comparator = "title".equalsIgnoreCase(sort)
                ? Comparator.comparing(Song::getTitle, String.CASE_INSENSITIVE_ORDER)
                : "new".equalsIgnoreCase(sort)
                    ? Comparator.comparing(Song::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    : Comparator.comparingInt(Song::getPlayCount).reversed()
                            .thenComparing(Song::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                            .thenComparing(Song::getId, Comparator.nullsLast(Long::compareTo));
        List<Song> sqlMatches = songRepository.findBrowseSongs(
                blankToEmpty(artist), blankToEmpty(artistGender), blankToEmpty(language), blankToEmpty(mediaType));
        List<Song> indexed = songRepository.findByStatus("ok");
        List<Song> candidates;
        if (sqlMatches != null && (!sqlMatches.isEmpty() || (indexed != null && !indexed.isEmpty()))) {
            // 数据库已执行过滤：空列表表示没有匹配，不能再全表回落
            candidates = sqlMatches;
        } else {
            // 单元测试未 stub SQL 查询时回落到内存过滤，保证媒体类型与对唱优先级语义不变
            candidates = validSongs().stream()
                    .filter(song -> blank(artist) || song.getArtist().equalsIgnoreCase(artist))
                    .filter(song -> blank(artistGender) || artistGender.equalsIgnoreCase(song.getArtistGender()))
                    .filter(song -> blank(language) || song.getLanguage().equalsIgnoreCase(language))
                    .filter(song -> blank(mediaType) || (song.getMediaType() != null && song.getMediaType().trim().equalsIgnoreCase(mediaType.trim())))
                    .toList();
        }
        return candidates.stream()
                .filter(song -> blank(vocalForm) || vocalForm.trim().equalsIgnoreCase(song.resolveEffectiveVocalForm()))
                .filter(song -> blank(tag) || contains(song.getTags(), tag) || contains(song.getAiGenres(), tag) || contains(song.getAiThemes(), tag))
                .sorted(comparator)
                .limit(safeLimit)
                .map(SongDto::from)
                .toList();
    }

    private List<Song> validSongs() {
        List<Song> songs = songRepository.findByStatus("ok");
        if (songs != null && !songs.isEmpty()) return songs;
        return songRepository.findAll().stream().filter(song -> "ok".equals(song.getStatus())).toList();
    }

    private List<Map<String, Object>> fallbackArtists() {
        return validSongs().stream()
                .collect(Collectors.groupingBy(song -> blank(song.getArtist()) ? "未知歌手" : song.getArtist()))
                .entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<Song>>>comparingInt(entry -> entry.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> Map.<String, Object>of(
                        "name", entry.getKey(),
                        "initial", artistInitial(entry.getValue().get(0)),
                        "gender", dominantArtistGender(entry.getValue()),
                        "songCount", entry.getValue().size()))
                .toList();
    }

    private List<Map<String, Object>> fallbackTags() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Song song : validSongs()) {
            addTags(counts, song.getTags());
            addTags(counts, song.getAiGenres());
            addTags(counts, song.getAiThemes());
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(100)
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue()))
                .toList();
    }

    private List<Map<String, Object>> toNamedCounts(List<Object[]> rows) {
        return rows.stream()
                .map(row -> Map.<String, Object>of("name", stringOf(row[0]), "songCount", numberOf(row[1])))
                .toList();
    }

    private List<Map<String, Object>> counts(List<Song> songs, Function<Song, String> classifier) {
        return songs.stream().map(classifier).filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> Map.<String, Object>of("name", entry.getKey(), "songCount", entry.getValue())).toList();
    }

    private String artistInitial(Song song) {
        return song.getArtistInit() == null || song.getArtistInit().isBlank() ? "#" : song.getArtistInit().substring(0, 1).toUpperCase();
    }

    static String dominantArtistGender(List<Song> songs) {
        return songs.stream().map(Song::getArtistGender)
                .filter(value -> value != null && !value.isBlank() && !"未知".equals(value))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("未知");
    }

    private boolean contains(String[] values, String expected) {
        return values != null && Arrays.stream(values).anyMatch(expected::equalsIgnoreCase);
    }

    private void addTags(Map<String, Long> counts, String[] values) {
        if (values == null) return;
        Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .forEach(value -> counts.merge(value, 1L, Long::sum));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private String blankToEmpty(String value) { return blank(value) ? "" : value.trim(); }

    private static String stringOf(Object value) { return value == null ? "" : value.toString(); }

    private static long numberOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
