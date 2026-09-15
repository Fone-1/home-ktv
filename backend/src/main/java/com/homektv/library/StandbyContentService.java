package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.SongRepository;
import com.homektv.web.ApiException;
import com.homektv.web.dto.SongDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StandbyContentService {
    private final SettingService settingService;
    private final SongRepository songRepository;
    private final PlayHistoryRepository historyRepository;
    private final AssetWriter assetWriter;
    private final StandbyContentCache cache;

    public StandbyContentService(SettingService settingService, SongRepository songRepository,
                                 PlayHistoryRepository historyRepository, AssetWriter assetWriter,
                                 StandbyContentCache cache) {
        this.settingService = settingService;
        this.songRepository = songRepository;
        this.historyRepository = historyRepository;
        this.assetWriter = assetWriter;
        this.cache = cache;
    }

    public Map<String, Object> content() {
        // TV 端高频轮询：命中 15 秒短缓存时直接返回，歌曲入库/播放完成/设置变更会主动失效
        Map<String, Object> cached = cache.get();
        if (cached != null) return cached;
        Map<String, Object> result = buildContent();
        cache.put(result);
        return result;
    }

    private Map<String, Object> buildContent() {
        Map<String, Object> settings = settingService.getAll();
        String source = string(settings, "standby_source", "mixed");
        List<Song> songs = switch (source) {
            case "hot" -> hotSongs();
            case "new" -> newSongs();
            case "custom" -> customSongs(settings.get("standby_song_ids"));
            default -> mixedSongs();
        };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("welcomeText", string(settings, "standby_welcome", "今晚开唱"));
        result.put("subtitle", string(settings, "standby_subtitle", "手机点歌，电视欢唱\n一家人的客厅 KTV"));
        result.put("carouselEnabled", bool(settings, "standby_carousel", true));
        result.put("antiBurn", bool(settings, "anti_burn", true));
        result.put("intervalSeconds", integer(settings, "standby_interval_sec", 8, 3, 60));
        result.put("source", source);
        result.put("videoScaleMode", option(settings, "tv_video_scale_mode", Set.of("fit", "zoom", "fill"), "zoom"));
        result.put("logoUrl", settings.get("standby_logo_path") == null ? null : "/api/standby/logo");
        result.put("songs", songs.stream().map(SongDto::from).toList());
        return result;
    }

    @Transactional
    public String uploadLogo(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException("INVALID_IMAGE", "请选择 Logo 图片");
        if (file.getSize() > 5 * 1024 * 1024) throw new ApiException("IMAGE_TOO_LARGE", "Logo 不能超过 5MB");
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String ext = switch (type) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> throw new ApiException("INVALID_IMAGE", "仅支持 JPG、PNG 或 WebP 图片");
        };
        try {
            String path = assetWriter.writeStandbyLogo(file.getBytes(), ext);
            settingService.putAll(Map.of("standby_logo_path", path));
            return path;
        } catch (IOException e) {
            throw new ApiException("IMAGE_WRITE_FAILED", "Logo 保存失败");
        }
    }

    /** 热门歌曲：排行聚合只取 ID，再按排行顺序一次性批量加载歌曲实体，禁止逐条 findById。 */
    private List<Song> hotSongs() {
        List<Object[]> rows = historyRepository.ranking(OffsetDateTime.now().minusDays(3650), 20);
        List<Long> ids = rows.stream().map(row -> ((Number) row[0]).longValue()).toList();
        if (ids.isEmpty()) return List.of();
        Map<Long, Song> loaded = songRepository.findAllById(ids).stream()
                .filter(this::valid)
                .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
        // 保持排行顺序（cnt 降序）
        return ids.stream().map(loaded::get).filter(Objects::nonNull).toList();
    }

    private List<Song> newSongs() {
        return songRepository.findTop50ByOrderByCreatedAtDesc().stream().filter(this::valid).limit(20).toList();
    }

    private List<Song> mixedSongs() {
        LinkedHashMap<Long, Song> songs = new LinkedHashMap<>();
        hotSongs().forEach(song -> songs.put(song.getId(), song));
        newSongs().forEach(song -> songs.putIfAbsent(song.getId(), song));
        return songs.values().stream().limit(20).toList();
    }

    /** 自定义待机歌曲：按 ID 批量加载，同时严格保持用户配置的 ID 顺序。 */
    private List<Song> customSongs(Object value) {
        if (!(value instanceof List<?> ids)) return List.of();
        List<Long> wanted = ids.stream()
                .filter(id -> id instanceof Number)
                .map(id -> ((Number) id).longValue())
                .toList();
        if (wanted.isEmpty()) return List.of();
        Map<Long, Song> loaded = songRepository.findAllById(wanted).stream()
                .filter(this::valid)
                .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
        return wanted.stream().map(loaded::get).filter(Objects::nonNull).toList();
    }

    private boolean valid(Song song) { return "ok".equals(song.getStatus()); }
    private String string(Map<String, Object> values, String key, String fallback) { Object value = values.get(key); return value == null || value.toString().isBlank() ? fallback : value.toString(); }
    private boolean bool(Map<String, Object> values, String key, boolean fallback) { Object value = values.get(key); return value instanceof Boolean b ? b : value == null ? fallback : Boolean.parseBoolean(value.toString()); }
    private int integer(Map<String, Object> values, String key, int fallback, int min, int max) { Object value = values.get(key); int parsed = value instanceof Number n ? n.intValue() : fallback; return Math.max(min, Math.min(max, parsed)); }
    private String option(Map<String, Object> values, String key, Set<String> allowed, String fallback) { String value = string(values, key, fallback).toLowerCase(Locale.ROOT); return allowed.contains(value) ? value : fallback; }
}
