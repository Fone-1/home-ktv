package com.homektv.library;

import com.homektv.config.AppProperties;
import com.homektv.domain.Song;
import com.homektv.domain.SongFile;
import com.homektv.media.FFprobeService;
import com.homektv.media.MediaProbe;
import com.homektv.media.MediaProbeException;
import com.homektv.repo.SongFileRepository;
import com.homektv.repo.SongRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 曲库扫描入库管线（P1.1-P1.5，详设§9.3）。
 * 枚举文件 → ffprobe 探测 → 标签解析 → 文件名兜底 → 类型判定
 * → 指纹去重 → 拼音字段 → 歌词/封面落盘 → 写 songs/song_files。
 *
 * Library scanning and ingestion pipeline (P1.1-P1.5, Detailed Design §9.3).
 * Enumerates files, probes media, parses tags, applies filename fallbacks,
 * classifies media, deduplicates by fingerprint, generates Pinyin fields,
 * stores lyrics/covers, and persists songs/song_files.
 */
@Service
public class LibraryScanService {

    private static final Logger log = LoggerFactory.getLogger(LibraryScanService.class);

    private static final Set<String> MEDIA_EXT = Set.of(
            "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "webm", "wmv", "asf", "flv", "f4v", "3gp", "3g2", "rm", "rmvb", // 视频
            "mp3", "mp2", "aac", "flac", "wav", "m4a", "ape", "ogg", "oga", "opus",
            "ac3", "eac3", "dts", "mka", "wma", "aiff", "aif", "alac" // 音频
    );

    private final AppProperties props;
    private final FFprobeService ffprobe;
    private final TagReader tagReader;
    private final SongRepository songRepo;
    private final SongFileRepository fileRepo;
    private final AssetWriter assetWriter;
    private final StandbyContentCache standbyCache;

    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter) {
        // 兼容旧手工构造：使用独立缓存实例，行为一致
        this(props, ffprobe, tagReader, songRepo, fileRepo, assetWriter, new StandbyContentCache());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LibraryScanService(AppProperties props, FFprobeService ffprobe, TagReader tagReader,
                              SongRepository songRepo, SongFileRepository fileRepo, AssetWriter assetWriter,
                              StandbyContentCache standbyCache) {
        this.props = props;
        this.ffprobe = ffprobe;
        this.tagReader = tagReader;
        this.songRepo = songRepo;
        this.fileRepo = fileRepo;
        this.assetWriter = assetWriter;
        this.standbyCache = standbyCache;
    }

    public record ScanResult(int scanned, int added, int updated, int skipped, int unrecognized) {}
    public record IngestResult(boolean imported, Long songId, Long songFileId) {}

    /** 全量/增量扫描曲库根目录 */
    public ScanResult scanAll() {
        Path root = Path.of(props.getKtvLibraryPath());
        if (!Files.isDirectory(root)) {
            log.warn("曲库目录不存在：{}", root);
            return new ScanResult(0, 0, 0, 0, 0);
        }
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isMediaFile(file)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("遍历曲库失败：{}", e.getMessage());
        }

        int added = 0, updated = 0, skipped = 0, unrecognized = 0;
        for (Path file : files) {
            try {
                IngestOutcome outcome = ingest(file);
                switch (outcome) {
                    case ADDED -> added++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                    case UNRECOGNIZED -> { added++; unrecognized++; }
                }
            } catch (Exception e) {
                log.warn("入库失败，跳过：{} - {}", file, e.getMessage());
                skipped++;
            }
        }
        log.info("扫描完成：共 {} 文件，新增 {}，更新 {}，跳过 {}，未识别 {}",
                files.size(), added, updated, skipped, unrecognized);
        return new ScanResult(files.size(), added, updated, skipped, unrecognized);
    }

    enum IngestOutcome { ADDED, UPDATED, SKIPPED, UNRECOGNIZED }

    /** 单文件入库（幂等：已存在的文件路径按 mtime 判断是否需更新） */
    @Transactional
    public IngestOutcome ingest(Path file) {
        IngestState state = ingestInternal(file, null, null, null, false);
        if (state.outcome() == IngestOutcome.ADDED) {
            // 新歌入库会改变「新歌/混合」待机内容，失效短缓存
            standbyCache.evict();
        }
        return state.outcome();
    }

    @Transactional
    public IngestResult ingestLibraryFile(Path file, Path sourceFile, String sourceMd5, String outputMd5, boolean transcodeRequired) {
        IngestState state = ingestInternal(file, sourceFile, sourceMd5, outputMd5, transcodeRequired);
        if (state.outcome() == IngestOutcome.ADDED) {
            standbyCache.evict();
        }
        return new IngestResult(
                state.outcome() == IngestOutcome.ADDED || state.outcome() == IngestOutcome.UPDATED,
                state.songId(),
                state.songFileId()
        );
    }

    private IngestState ingestInternal(Path file, Path sourceFile, String sourceMd5, String outputMd5, boolean transcodeRequired) {
        String pathStr = file.toString();
        Path sidecarLyric = sidecarLyricOf(file);
        OffsetDateTime mtime = newestMtime(file, sidecarLyric);

        // 已入库且未修改 → 跳过（增量扫描）。
        // 用秒级比较：DB TIMESTAMPTZ 为微秒精度，文件系统 mtime 为纳秒，直接比较会因精度截断误判为“已修改”。
        Optional<SongFile> existing = fileRepo.findByFilePath(pathStr);
        if (existing.isPresent()) {
            long storedSec = existing.get().getFileMtime().toEpochSecond();
            long fileSec = mtime.toEpochSecond();
            if (fileSec <= storedSec) {
                SongFile existingFile = existing.get();
                return new IngestState(IngestOutcome.SKIPPED, existingFile.getSongId(), existingFile.getId());
            }
        }

        // 1) ffprobe 探测
        MediaProbe probe;
        try {
            probe = ffprobe.probe(file);
        } catch (MediaProbeException e) {
            log.debug("ffprobe 失败：{} - {}", file.getFileName(), e.getMessage());
            return new IngestState(IngestOutcome.SKIPPED, null, null);
        }

        // 2) 标签解析 + 容器标签 + LRC + 3) 文件名兜底
        TagInfo tag = tagReader.read(file.toFile());
        String sidecarLyricText = readValidSidecarLyric(sidecarLyric);
        String lrcTitle = lrcTag(sidecarLyricText, "ti");
        String lrcArtist = lrcTag(sidecarLyricText, "ar");
        boolean recognized;
        String title, artist;
        String identitySource;
        if (tag.hasTitle()) {
            title = tag.getTitle();
            artist = tag.getArtist() != null ? tag.getArtist() : "";
            recognized = true;
            identitySource = "audio_tag";
        } else if (probe.title() != null && !probe.title().isBlank()) {
            title = probe.title();
            artist = probe.artist() == null ? "" : probe.artist();
            recognized = true;
            identitySource = "container_tag";
        } else if (lrcTitle != null && !lrcTitle.isBlank()) {
            title = lrcTitle;
            artist = lrcArtist == null ? "" : lrcArtist;
            recognized = true;
            identitySource = "lrc_tag";
        } else {
            ParsedMeta pm = FilenameParser.parse(file.getFileName().toString());
            title = pm.title();
            artist = pm.artist();
            recognized = pm.recognized();
            identitySource = "filename";
        }
        if (artist == null || artist.isBlank()) artist = "未知歌手";

        // 4) 类型判定 + 伴奏轨判定 + 指纹
        String mediaType = MediaClassifier.classify(probe);
        boolean hasVocal = MediaClassifier.hasVocalTrack(probe);
        VocalTrackDetector.Result vocalDetect = VocalTrackDetector.detect(probe);
        String fingerprint = MediaClassifier.fingerprint(artist, title, probe.durationMs());

        // 5) 指纹去重：同指纹已存在 → 作为多文件源加入，按 priority 择优
        Optional<Song> dup = songRepo.findByFingerprint(fingerprint);
        Song song;
        boolean isNew;
        if (dup.isPresent()) {
            song = dup.get();
            isNew = false;
        } else {
            song = new Song();
            song.setTitle(title);
            song.setArtist(artist);
            song.setTitlePy(PinyinUtil.fullPinyin(title));
            song.setTitleInit(PinyinUtil.initials(title));
            song.setArtistPy(PinyinUtil.fullPinyin(artist));
            song.setArtistInit(PinyinUtil.initials(artist));
            song.setMediaType(mediaType);
            song.setHasVocalTrack(hasVocal);
            song.setDurationMs((int) probe.durationMs());
            song.setLyricType(LyricType.NONE);
            song.setFingerprint(fingerprint);
            // 未识别（标签+文件名均无有效歌名）标记 unrecognized，供后台筛选补录；否则 ok
            song.setStatus(recognized ? "ok" : "unrecognized");
            if (tag.getLanguage() != null && !tag.getLanguage().isBlank()) song.setLanguage(normalizeLanguage(tag.getLanguage()));
            else if (probe.language() != null && !probe.language().isBlank()) song.setLanguage(normalizeLanguage(probe.language()));
            song.setMetadataProvenance("{\"title\":{\"source\":\"" + identitySource + "\"},\"artist\":{\"source\":\"" + identitySource + "\"}}");
            song.setNeedsAiOptimization(!recognized || "未知".equals(song.getLanguage()) || "未知歌手".equals(song.getArtist()));
            isNew = true;
        }

        // 6) 歌词/封面落盘。同名增强 LRC 优先，且允许侧车文件独立更新。
        if (sidecarLyricText != null) {
            String lyricPath = assetWriter.writeLyric(fingerprint, sidecarLyricText);
            song.setLyricPath(lyricPath);
            song.setLyricType(LyricType.detect(sidecarLyricText));
        }
        if (isNew) {
            String lyricText = sidecarLyricText == null ? tag.getEmbeddedLyric() : null;
            if (lyricText != null && !lyricText.isBlank()) {
                String lyricPath = assetWriter.writeLyric(fingerprint, lyricText);
                song.setLyricPath(lyricPath);
                song.setLyricType(LyricType.detect(lyricText));
            }
            if (tag.getCoverImage() != null) {
                String coverPath = assetWriter.writeCover(fingerprint, tag.getCoverImage(), tag.getCoverExt());
                song.setCoverPath(coverPath);
            }
        }
        song = songRepo.save(song);

        // 7) 写 song_files（KTV 视频优先级高）
        int priority = switch (mediaType) {
            case MediaClassifier.KTV_VIDEO -> 100;
            case MediaClassifier.MV -> 50;
            default -> 10;
        };
        SongFile sf = existing.orElseGet(SongFile::new);
        sf.setSongId(song.getId());
        sf.setFilePath(pathStr);
        sf.setFormat(extOf(file));
        sf.setAudioTracks(probe.audioTracks());
        // 伴奏轨 index（0-based 音频相对序号）。已有值优先（尊重人工/历史校正），
        // 否则用元数据判定，判不出再回落默认 1（多数双轨片源 track0=原唱、track1=伴奏）。
        Integer existingVocalTrackIndex = existing.map(SongFile::getVocalTrackIndex).orElse(null);
        Integer vocalTrackIndex = null;
        String vocalConfidence = null;
        if (hasVocal) {
            if (existingVocalTrackIndex != null) {
                // 尊重人工/历史校正：index 不变，置信度视为已确认（HIGH）
                vocalTrackIndex = existingVocalTrackIndex;
                vocalConfidence = VocalTrackDetector.Confidence.HIGH.name();
            } else if (vocalDetect.accompanimentIndex() != null) {
                vocalTrackIndex = vocalDetect.accompanimentIndex();
                vocalConfidence = vocalDetect.confidence().name();
                log.debug("伴奏轨判定 {} → track#{}（{}，{}）", file.getFileName(),
                        vocalTrackIndex, vocalDetect.confidence(), vocalDetect.reason());
            } else {
                // 判不出：回落默认 track#1，标 LOW 供后台筛选人工复核
                vocalTrackIndex = 1;
                vocalConfidence = VocalTrackDetector.Confidence.LOW.name();
                log.info("伴奏轨无法确定，回落默认 track#1，建议人工复核：{}（{}）",
                        file.getFileName(), vocalDetect.reason());
            }
        }
        sf.setVocalTrackIndex(vocalTrackIndex);
        sf.setVocalConfidence(vocalConfidence);
        sf.setResolution(probe.resolution());
        sf.setFileSize(sizeOf(file));
        sf.setFileMtime(mtime);
        sf.setPriority(priority);
        // 文件重新被成功探测，说明之前的瞬时播放失败不应永久屏蔽该源。
        sf.setValid(true);
        sf.setFileRole("LIBRARY");
        sf.setSourcePath(sourceFile != null ? sourceFile.toString() : sf.getSourcePath());
        sf.setSourceMd5(sourceMd5);
        sf.setOutputMd5(outputMd5);
        sf.setTranscodeRequired(transcodeRequired);
        sf.setImportedAt(OffsetDateTime.now());
        sf.setSourceDeleted(false);
        sf = fileRepo.save(sf);

        IngestOutcome outcome;
        if (!recognized) outcome = IngestOutcome.UNRECOGNIZED;
        else if (existing.isPresent()) outcome = IngestOutcome.UPDATED;
        else outcome = isNew ? IngestOutcome.ADDED : IngestOutcome.UPDATED;
        return new IngestState(outcome, song.getId(), sf.getId());
    }

    public static boolean isMediaFile(Path file) {
        return MEDIA_EXT.contains(extOf(file));
    }

    private static String extOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static OffsetDateTime mtimeOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant().atOffset(ZoneOffset.UTC);
        } catch (IOException e) {
            return OffsetDateTime.now();
        }
    }

    private static Path sidecarLyricOf(Path mediaFile) {
        String name = mediaFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return mediaFile.resolveSibling(stem + ".lrc");
    }

    private static OffsetDateTime newestMtime(Path mediaFile, Path sidecarLyric) {
        OffsetDateTime mediaMtime = mtimeOf(mediaFile);
        if (!Files.isRegularFile(sidecarLyric)) return mediaMtime;
        OffsetDateTime lyricMtime = mtimeOf(sidecarLyric);
        return lyricMtime.isAfter(mediaMtime) ? lyricMtime : mediaMtime;
    }

    private static String readValidSidecarLyric(Path sidecarLyric) {
        if (!Files.isRegularFile(sidecarLyric)) return null;
        try {
            String text = Files.readString(sidecarLyric);
            return LyricType.NONE.equals(LyricType.detect(text)) ? null : text;
        } catch (IOException e) {
            log.warn("读取同名歌词失败：{} - {}", sidecarLyric, e.getMessage());
            return null;
        }
    }

    private static String lrcTag(String lyric, String key) {
        if (lyric == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?im)^\\[" + key + "\\s*:\\s*(.+?)\\]\\s*$").matcher(lyric);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String normalizeLanguage(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "未知";
        if (value.matches("zh(-|_)?cn|中文|mandarin|国语|普通话")) return "国语";
        if (value.matches("yue|zh(-|_)?hk|粤语|cantonese")) return "粤语";
        if (value.matches("nan|闽南语|台语|hokkien")) return "闽南语";
        if (value.matches("en|英语|英文|english")) return "英语";
        if (value.matches("ja|日语|日文|japanese")) return "日语";
        if (value.matches("ko|韩语|韩文|korean")) return "韩语";
        if (value.matches("instrumental|纯音乐|music")) return "纯音乐";
        return Set.of("国语", "粤语", "闽南语", "英语", "日语", "韩语", "纯音乐", "其他", "未知").contains(raw) ? raw : "其他";
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private record IngestState(IngestOutcome outcome, Long songId, Long songFileId) {}
}
