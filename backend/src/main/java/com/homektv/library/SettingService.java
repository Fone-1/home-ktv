package com.homektv.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Setting;
import com.homektv.repo.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 系统设置读写（P2.6，详设§8 ADM-03）。settings 表 key→JSONB value。
 */
@Service
public class SettingService {

   public static final String LIBRARY_WATCH_ENABLED = "library_watch_enabled";
   public static final String DELETE_SOURCE_AFTER_TRANSCODE = "delete_source_after_transcode";
    public static final String MV_AUTO_ENQUEUE = "mv_auto_enqueue";
    public static final String MV_AUTO_CONVERT_DUAL_TRACK = "mv_auto_convert_dual_track";
    public static final String ADMIN_PIN_ENABLED = "admin_pin_enabled";
    public static final String ADMIN_PIN_HASH = "admin_pin_hash";
    public static final String ADMIN_READ_REQUIRE_AUTH = "admin_read_require_auth";

   public static final String DUAL_TRACK_ENGINE = "dual_track_engine";
    public static final String DUAL_TRACK_REMOTE_URL = "dual_track_remote_url";
    public static final String DUAL_TRACK_REMOTE_TOKEN = "dual_track_remote_token";
    public static final String DUAL_TRACK_CONCURRENCY = "dual_track_concurrency";
    public static final String DUAL_TRACK_BACKUP_ORIGINAL = "dual_track_backup_original";
    public static final String DUAL_TRACK_AUDIO_BITRATE = "dual_track_audio_bitrate";

    public static final Map<String, Object> DUAL_TRACK_DEFAULTS = Map.of(
            DUAL_TRACK_ENGINE, "REMOTE_AI",
            DUAL_TRACK_REMOTE_URL, System.getenv().getOrDefault("KTV_DUAL_TRACK_REMOTE_URL", "http://127.0.0.1:8900/api/separate"),
            DUAL_TRACK_REMOTE_TOKEN, System.getenv().getOrDefault("KTV_DUAL_TRACK_REMOTE_TOKEN", ""),
            DUAL_TRACK_CONCURRENCY, 1,
            DUAL_TRACK_BACKUP_ORIGINAL, false,
            DUAL_TRACK_AUDIO_BITRATE, "192k"
    );

    public static final Map<String, Object> TRANSCODE_DEFAULTS = Map.of(
            "direct_copy_containers", List.of("mp4", "m4v", "mkv"),
            "direct_copy_video_codecs", List.of("h264", "hevc"),
            "direct_copy_audio_codecs", List.of("aac", "mp3"),
            "transcode_audio_only", false,
            "transcode_output_container", "mkv",
            "transcode_video_codec", "h264",
            "transcode_audio_codec", "aac",
            "transcode_hardware_acceleration", false,
            "transcode_hardware_auto_configured", false
    );
    private static final Map<String, Object> GENERAL_DEFAULTS = Map.ofEntries(
            Map.entry(LIBRARY_WATCH_ENABLED, false), Map.entry("tv_video_scale_mode", "zoom"),
            Map.entry("qr_address", ""), Map.entry("standby_carousel", true), Map.entry("anti_burn", true),
            Map.entry("mini_qr", true), Map.entry("standby_welcome", "今晚开唱"),
            Map.entry("standby_subtitle", "手机点歌，电视欢唱\n一家人的客厅 KTV"), Map.entry("standby_source", "mixed"),
            Map.entry("standby_song_ids", List.of()), Map.entry("standby_interval_sec", 8),
           Map.entry("display_address", ""), Map.entry("standby_logo_path", ""),
            Map.entry(DELETE_SOURCE_AFTER_TRANSCODE, false), Map.entry("room_host_user_id", 0L),
            Map.entry(MV_AUTO_ENQUEUE, true),
            Map.entry(MV_AUTO_CONVERT_DUAL_TRACK, false),
            Map.entry(ADMIN_PIN_ENABLED, false),
            Map.entry(ADMIN_PIN_HASH, ""),
            Map.entry(ADMIN_READ_REQUIRE_AUTH, false));
   private static final Set<String> TRANSCODE_KEYS = TRANSCODE_DEFAULTS.keySet();
    private static final Set<String> ALLOWED_KEYS = new HashSet<>();
    static {
        ALLOWED_KEYS.addAll(TRANSCODE_DEFAULTS.keySet());
        ALLOWED_KEYS.addAll(GENERAL_DEFAULTS.keySet());
        ALLOWED_KEYS.addAll(DUAL_TRACK_DEFAULTS.keySet());
    }

    private final SettingRepository repo;
    private final ObjectMapper mapper;
    private final StandbyContentCache standbyCache;

    public SettingService(SettingRepository repo, ObjectMapper mapper) {
        // 兼容旧手工构造（单元测试）：使用独立缓存实例，行为一致
        this(repo, mapper, new StandbyContentCache());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SettingService(SettingRepository repo, ObjectMapper mapper, StandbyContentCache standbyCache) {
        this.repo = repo;
        this.mapper = mapper;
        this.standbyCache = standbyCache;
    }

    /** 读取全部设置为 map（value 反序列化为对象） */
    @Transactional(readOnly = true)
    public Map<String, Object> getAll() {
        Map<String, Object> out = new HashMap<>(TRANSCODE_DEFAULTS);
        out.putAll(GENERAL_DEFAULTS);
        out.putAll(DUAL_TRACK_DEFAULTS);
        for (Setting s : repo.findAll()) {
            if (ALLOWED_KEYS.contains(s.getKey())) {
                out.put(s.getKey(), parse(s.getValue()));
            }
        }
        return out;
    }

   public boolean isLibraryWatchEnabled() {
       return Boolean.TRUE.equals(getAll().get(LIBRARY_WATCH_ENABLED));
   }

    /** MV 在线下载入库后是否自动加入点歌队列 */
    public boolean isMvAutoEnqueue() {
        return Boolean.TRUE.equals(getAll().getOrDefault(MV_AUTO_ENQUEUE, true));
    }

    /** MV 在线下载单音轨视频是否自动触发转双轨伴奏分离 */
    public boolean isMvAutoConvertDualTrack() {
        return Boolean.TRUE.equals(getAll().getOrDefault(MV_AUTO_CONVERT_DUAL_TRACK, false));
    }

    /** 是否启用了管理员 PIN 保护（默认 false 保持家庭局域网零摩擦体验） */
    public boolean isAdminPinEnabled() {
        return Boolean.TRUE.equals(getAll().get(ADMIN_PIN_ENABLED));
    }

    /** 获取管理员 PIN 哈希（带盐存储） */
    public String getAdminPinHash() {
        return repo.findById(ADMIN_PIN_HASH).map(Setting::getValue).map(this::parse)
                .map(Object::toString).orElse("");
    }

    /** 读取类操作是否强制需要管理员登录 */
    public boolean isAdminReadRequireAuth() {
        return Boolean.TRUE.equals(getAll().get(ADMIN_READ_REQUIRE_AUTH));
    }

   /** 批量写入设置 */
    @Transactional
    public void putAll(Map<String, Object> settings) {
        settings.forEach((k, v) -> {
            if (!ALLOWED_KEYS.contains(k)) {
                return;
            }
            validateKeyValue(k, v);
            Setting s = repo.findById(k).orElseGet(() -> {
                Setting ns = new Setting();
                ns.setKey(k);
                return ns;
            });
            s.setValue(write(v));
            repo.save(s);
        });
        // 设置变更（待机来源/自定义歌曲/文案等）可能影响待机内容，直接失效短缓存
        standbyCache.evict();
    }

    private void validateKeyValue(String key, Object value) {
        if (!ALLOWED_KEYS.contains(key)) throw new com.homektv.web.ApiException("SETTING_NOT_ALLOWED", "不允许修改设置：" + key);
        if (value instanceof String text && text.length() > 1000)
            throw new com.homektv.web.ApiException("SETTING_INVALID_RANGE", key + " 文本过长");
       if (Set.of(LIBRARY_WATCH_ENABLED, "standby_carousel", "anti_burn", "mini_qr", "transcode_audio_only",
                "transcode_hardware_acceleration", "transcode_hardware_auto_configured", DELETE_SOURCE_AFTER_TRANSCODE,
                MV_AUTO_ENQUEUE, MV_AUTO_CONVERT_DUAL_TRACK).contains(key)) {
           if (!(value instanceof Boolean)) throw new com.homektv.web.ApiException("SETTING_INVALID_TYPE", key + " 必须是布尔值");
       }
        if (DUAL_TRACK_BACKUP_ORIGINAL.equals(key)) {
            if (!(value instanceof Boolean)) throw new com.homektv.web.ApiException("SETTING_INVALID_TYPE", key + " 必须是布尔值");
        }
        if (DUAL_TRACK_CONCURRENCY.equals(key)) {
            if (!(value instanceof Number number) || number.intValue() < 1 || number.intValue() > 3)
                throw new com.homektv.web.ApiException("SETTING_INVALID_RANGE", key + " 并发数必须在 1 到 3 之间");
        }
        if (DUAL_TRACK_ENGINE.equals(key) && !Set.of("DSP", "LOCAL_AI", "REMOTE_AI").contains(String.valueOf(value).toUpperCase())) {
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "伴奏分离引擎无效，支持 DSP / LOCAL_AI / REMOTE_AI");
        }
        if (DUAL_TRACK_AUDIO_BITRATE.equals(key) && !Set.of("128k", "192k", "256k", "320k").contains(String.valueOf(value).toLowerCase())) {
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "伴奏音频码率无效，支持 128k / 192k / 256k / 320k");
        }
        if (key.endsWith("_interval_sec")) {
            if (!(value instanceof Number number) || number.intValue() < 3 || number.intValue() > 60)
                throw new com.homektv.web.ApiException("SETTING_INVALID_RANGE", key + " 必须在 3 到 60 之间");
        }
        if (key.startsWith("direct_copy_") && (key.contains("codec") || key.contains("container"))) {
            if (!(value instanceof List<?>)) throw new com.homektv.web.ApiException("SETTING_INVALID_TYPE", key + " 必须是数组或选项值");
        }
        if ("transcode_output_container".equals(key) && !Set.of("mkv", "mp4").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "输出容器无效");
        if ("tv_video_scale_mode".equals(key) && !Set.of("zoom", "fit", "fill").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "视频画面模式无效");
        if ("standby_source".equals(key) && !Set.of("mixed", "hot", "new", "custom").contains(String.valueOf(value)))
            throw new com.homektv.web.ApiException("SETTING_INVALID_VALUE", "待机轮播来源无效");
    }

    @Transactional
    public Map<String, Object> resetTranscodeDefaults() {
        repo.deleteAllById(TRANSCODE_KEYS);
        return getAll();
    }

    public TranscodePolicy transcodePolicy() {
        Map<String, Object> settings = getAll();
        return new TranscodePolicy(
                strings(settings.get("direct_copy_containers"), List.of("mp4", "m4v", "mkv")),
                strings(settings.get("direct_copy_video_codecs"), List.of("h264", "hevc")),
                strings(settings.get("direct_copy_audio_codecs"), List.of("aac", "mp3")),
                Boolean.TRUE.equals(settings.get("transcode_audio_only")),
                option(settings, "transcode_output_container", Set.of("mkv", "mp4"), "mkv"),
                option(settings, "transcode_video_codec", Set.of("h264", "hevc"), "h264"),
                option(settings, "transcode_audio_codec", Set.of("aac", "mp3", "opus"), "aac"),
                Boolean.TRUE.equals(settings.get("transcode_hardware_acceleration"))
        );
    }

    public DualTrackPolicy dualTrackPolicy() {
        Map<String, Object> settings = getAll();
        String rawEngine = String.valueOf(settings.getOrDefault(DUAL_TRACK_ENGINE, "REMOTE_AI")).toUpperCase();
        String engine = Set.of("DSP", "LOCAL_AI", "REMOTE_AI").contains(rawEngine) ? rawEngine : "REMOTE_AI";
        String defaultRemoteUrl = System.getenv().getOrDefault("KTV_DUAL_TRACK_REMOTE_URL", "http://127.0.0.1:8900/api/separate");
        String remoteUrl = String.valueOf(settings.getOrDefault(DUAL_TRACK_REMOTE_URL, defaultRemoteUrl)).trim();
        if (remoteUrl.isBlank()) {
            remoteUrl = defaultRemoteUrl;
        }
        String remoteToken = String.valueOf(settings.getOrDefault(DUAL_TRACK_REMOTE_TOKEN, "")).trim();
        int concurrency = settings.get(DUAL_TRACK_CONCURRENCY) instanceof Number n
                ? Math.max(1, Math.min(3, n.intValue())) : 1;
        boolean backupOriginal = Boolean.TRUE.equals(settings.get(DUAL_TRACK_BACKUP_ORIGINAL));
        String bitrate = option(settings, DUAL_TRACK_AUDIO_BITRATE, Set.of("128k", "192k", "256k", "320k"), "192k");
        return new DualTrackPolicy(engine, remoteUrl, remoteToken, concurrency, backupOriginal, bitrate);
    }

    public record DualTrackPolicy(String engine, String remoteUrl, String remoteToken, int concurrency,
                                  boolean backupOriginal, String audioBitrate) {}

    public record TranscodePolicy(List<String> directCopyContainers, List<String> directCopyVideoCodecs,
                                  List<String> directCopyAudioCodecs, boolean transcodeAudioOnly,
                                  String outputContainer, String videoCodec, String audioCodec,
                                  boolean hardwareAcceleration) {}

    private static List<String> strings(Object value, List<String> fallback) {
        if (!(value instanceof List<?> list)) return fallback;
        List<String> result = list.stream().map(String::valueOf).map(String::toLowerCase).distinct().toList();
        return result.isEmpty() ? fallback : result;
    }

    private static String option(Map<String, Object> settings, String key, Set<String> allowed, String fallback) {
        String value = String.valueOf(settings.getOrDefault(key, fallback)).toLowerCase();
        return allowed.contains(value) ? value : fallback;
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return "null";
        }
    }
}
