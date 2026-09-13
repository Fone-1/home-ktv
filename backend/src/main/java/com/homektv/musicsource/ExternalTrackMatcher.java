package com.homektv.musicsource;

import com.homektv.domain.Song;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外部音乐源匹配器与置信度打分引擎。
 * 针对 KTV 曲库中常见的复杂歌名（带序号前缀、版本后缀、内嵌歌手、视频/格式噪声标签等）
 * 提供智能清洗、内嵌歌手拆解、多候选查询词生成与多维度置信度打分。
 */
@Component
public class ExternalTrackMatcher {
    /** 基础视频媒介与卡拉OK版本标记（用于规范化比对） */
    private static final Pattern DISPLAY_MARKER = Pattern.compile(
            "[（(\\[{【]\\s*(?:mtv|mv|ktv|演|原唱)\\s*[)）\\]}】]", Pattern.CASE_INSENSITIVE);

    /** 复合噪声括号标记（用于文件名和本地歌名彻底去噪，抽取纯净歌名） */
    private static final Pattern NOISE_BRACKET = Pattern.compile(
            "(?i)[\\(\\[\\{【（][^\\(\\)\\[\\]\\{\\}【（）]*(?:ktv|mtv|mv|演|原唱|伴奏|消音|卡拉ok|无损|高品质|高清|超清|1080p|4k|720p|官方|official|rolling\\s*lyrics|滚动歌词|无杂音|纯享|动态歌词|live|现场)[^\\(\\)\\[\\]\\{\\}【（）]*[\\)\\]\\}】）]");

    /** 尾部曲风、语种等分类后缀标记 */
    private static final Pattern CATALOG_SUFFIX = Pattern.compile(
            "(?:[-_—–\\s]+(?:国语|粤语|闽南语|英语|日语|韩语|纯音乐|其他|未知|流行(?:歌曲)?|摇滚|民谣|电子|舞曲|经典|影视原声|儿歌|戏曲|说唱|r&b|爵士|古典))+$",
            Pattern.CASE_INSENSITIVE);

    /** 常见音视频文件及下载曲目序号前缀（如 002.、111.、01-、A01-、【01】等） */
    private static final Pattern INDEX_PREFIX = Pattern.compile(
            "^(?:[0-9]{1,5}|[a-zA-Z][0-9]{1,4})[.\\s\\-_、)）\\]】]+|^[\\[({【（]\\s*(?:[0-9]{1,5}|[a-zA-Z][0-9]{1,4})\\s*[\\])}】）]\\s*");

    /** 常见下载任务 ID、集数或音轨版本后缀（如 - 1、_1、(1)、[3] 等） */
    private static final Pattern INDEX_SUFFIX = Pattern.compile(
            "[-_\\s]+(?:\\d{1,4}|[a-zA-Z]\\d{0,3})$|\\s*[\\[({【（]\\s*\\d+\\s*[\\])}】）]\\s*$");

    /** 常见通用或不可信歌手占位符（未知、群星、未知UP主等） */
    private static final Set<String> UNTRUSTED_ARTISTS = Set.of(
            "未知", "未知歌手", "unknown", "variousartists", "群星", "未知up主", "none", "佚名", "null", "uploads", "downloads");

    /**
     * 计算本地歌曲与外部音轨候选的综合匹配置信度。
     * 结合歌名相似度 (55%)、歌手相似度 (35%) 和时长吻合度 (10%)，并对内嵌歌手、噪声前缀和不可信歌手做自适应评分。
     *
     * @param song  本地曲库歌曲实体
     * @param track 外部平台检索到的歌曲音轨
     * @return 置信度评分 (0.0 ~ 1.0)，保留三位小数
     */
    public double score(Song song, ExternalTrack track) {
        String rawTitle = song.getTitle();
        String rawArtist = song.getArtist();
        String cleanedTitle = cleanTitleNoise(rawTitle);
        String[] embedded = splitEmbedded(rawTitle);
        boolean hasEmbedded = embedded != null;
        String p1 = hasEmbedded ? embedded[0] : "";
        String p2 = hasEmbedded ? embedded[1] : "";

        // 1. 歌名相似度：对比原始歌名、去噪后歌名、内嵌拆分片段（如周杰伦-晴天中的晴天与周杰伦）
        List<String> candidateTitles = new ArrayList<>();
        candidateTitles.add(normalizeTitle(rawTitle));
        candidateTitles.add(normalizeTitle(cleanedTitle));
        if (hasEmbedded) {
            candidateTitles.add(normalizeTitle(p1));
            candidateTitles.add(normalizeTitle(p2));
        }
        String normTrackTitle = normalizeTitle(track.title());
        double titleScore = candidateTitles.stream()
                .filter(ct -> !ct.isEmpty())
                .mapToDouble(ct -> similarity(ct, normTrackTitle))
                .max().orElse(0.0);

        // 2. 歌手相似度：对比原始歌手、内嵌拆分片段、以及歌名是否直接包含外部歌手名
        List<String> candidateArtists = new ArrayList<>();
        candidateArtists.add(normalize(rawArtist));
        if (hasEmbedded) {
            candidateArtists.add(normalize(p1));
            candidateArtists.add(normalize(p2));
        }
        String normTrackArtists = normalize(String.join(" ", track.artists()));
        double artistScore = candidateArtists.stream()
                .filter(ca -> !ca.isEmpty())
                .mapToDouble(ca -> similarity(ca, normTrackArtists))
                .max().orElse(0.0);

        // 检查原始歌名是否直接包含外部歌手（例如歌名是“周杰伦_晴天”，而本地歌手为空或未知UP主）
        String normSongTitle = normalize(rawTitle);
        for (String externalArtist : track.artists()) {
            String normA = normalize(externalArtist);
            if (normA.length() >= 2 && normSongTitle.contains(normA)) {
                artistScore = Math.max(artistScore, 1.0);
            }
        }

        // 若本地歌手不可信（如为空、未知歌手、或被内嵌歌手否定），且歌名匹配度极高时给予中立置信度
        boolean untrusted = isUntrustedArtist(rawArtist, hasEmbedded, p1, p2);
        if (untrusted && artistScore < 0.6) {
            if (titleScore >= 0.85) {
                artistScore = 0.90;
            }
        }

        // 3. 时长吻合度：30 秒内线性评分
        double duration = 0.5;
        if (song.getDurationMs() > 0 && track.durationMs() != null && track.durationMs() > 0) {
            int difference = Math.abs(song.getDurationMs() - track.durationMs());
            duration = Math.max(0, 1 - difference / 30_000.0);
        }

        // 4. 歌名严重不匹配防误判熔断：严禁因歌手/UP主重名或时长巧合导致错误歌曲获得高分
        if (titleScore < 0.25) {
            return Math.round(Math.min(0.20, titleScore * 0.5) * 1000) / 1000.0;
        }

        // 5. 综合加权计算
        double score = titleScore * 0.55 + artistScore * 0.35 + duration * 0.10;
        // 核心字段完全吻合且时长非常接近时，确保能突破 0.95 自动写入阈值
        if (titleScore >= 0.95 && artistScore >= 0.95 && duration >= 0.85) {
            score = Math.max(score, 0.96);
        }
        return Math.round(score * 1000) / 1000.0;
    }

    /**
     * 根据本地歌曲信息生成智能搜索词列表（按优先级降序）。
     *
     * @param song 本地曲库歌曲
     * @return 优先搜索词候选列表（去重、去噪声）
     */
    public List<String> generateCandidateQueries(Song song) {
        String rawTitle = song.getTitle();
        String rawArtist = song.getArtist();
        String cleanedTitle = cleanTitleNoise(rawTitle);
        String[] embedded = splitEmbedded(rawTitle);
        if (embedded == null) {
            embedded = splitEmbedded(cleanedTitle);
        }
        boolean hasEmbedded = embedded != null;
        String p1 = hasEmbedded ? embedded[0] : "";
        String p2 = hasEmbedded ? embedded[1] : "";
        boolean untrusted = isUntrustedArtist(rawArtist, hasEmbedded, p1, p2);

        Set<String> queries = new LinkedHashSet<>();
        if (hasEmbedded) {
            // 优先采用从歌名中拆出的“歌手 歌名”和“歌名 歌手”
            queries.add(p1 + " " + p2);
            queries.add(p2 + " " + p1);
            queries.add(p2);
        } else {
            if (!untrusted && rawArtist != null && !rawArtist.isBlank()) {
                queries.add(cleanedTitle + " " + rawArtist.trim());
                queries.add(cleanedTitle);
            } else {
                queries.add(cleanedTitle);
                if (rawArtist != null && !rawArtist.isBlank()) {
                    queries.add(cleanedTitle + " " + rawArtist.trim());
                }
            }
        }
        // 兜底原始全拼接搜索词
        String fallback = (cleanedTitle.isEmpty() ? rawTitle : cleanedTitle) + " " + (rawArtist == null ? "" : rawArtist.trim());
        queries.add(fallback.trim());

        return queries.stream()
                .map(String::trim)
                .filter(q -> q.length() >= 2)
                .toList();
    }

    public String groupKey(ExternalTrack track) {
        int bucket = track.durationMs() == null ? -1 : (track.durationMs() + 15_000) / 30_000;
        return normalizeTitle(track.title()) + "|" + normalize(String.join(" ", track.artists())) + "|" + bucket;
    }

    /**
     * 清除歌名中的常见噪声（序号前缀、版本后缀、KTV/视频标签、语种曲风后缀）。
     */
    public static String cleanTitleNoise(String value) {
        if (value == null) return "";
        String s = Normalizer.normalize(value, Normalizer.Form.NFKC);
        s = INDEX_PREFIX.matcher(s).replaceAll("");
        s = INDEX_SUFFIX.matcher(s).replaceAll("");
        String prev = null;
        while (!s.equals(prev)) {
            prev = s;
            s = NOISE_BRACKET.matcher(s).replaceAll("").trim();
        }
        s = s.replaceAll("(?i)\\s*[-|]\\s*(?:ktv|mtv|mv|live|伴奏|原唱|消音|卡拉ok)\\s*$", "");
        s = CATALOG_SUFFIX.matcher(s).replaceAll("");
        return s.trim();
    }

    static String normalizeTitle(String value) {
        if (value == null) return "";
        String cleaned = DISPLAY_MARKER.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)).replaceAll("");
        cleaned = CATALOG_SUFFIX.matcher(cleaned).replaceAll("");
        return normalize(cleaned);
    }

    static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\s·•]+", "");
    }

    /**
     * 判断本地歌曲的歌手信息是否不可信（如为空、通用占位符，或与歌名中内嵌的歌手产生矛盾）。
     */
    static boolean isUntrustedArtist(String artist, boolean hasEmbedded, String p1, String p2) {
        if (artist == null || artist.isBlank()) return true;
        String normA = normalize(artist);
        if (UNTRUSTED_ARTISTS.contains(normA)) return true;
        if (hasEmbedded) {
            String normP1 = normalize(p1);
            String normP2 = normalize(p2);
            if (!normA.equals(normP1) && !normA.equals(normP2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 尝试将歌名字符串按常见分隔符拆解为两段非空子串（如“周杰伦-晴天”拆为“周杰伦”和“晴天”）。
     */
    static String[] splitEmbedded(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = cleanTitleNoise(text);
        for (String delim : new String[]{" - ", "－", "—", "–", "-", "_", "/", "|"}) {
            int idx = cleaned.indexOf(delim);
            if (idx > 0 && idx < cleaned.length() - delim.length()) {
                String p1 = cleanTitleNoise(cleaned.substring(0, idx));
                String p2 = cleanTitleNoise(cleaned.substring(idx + delim.length()));
                if (!p1.isEmpty() && !p2.isEmpty() && !p1.matches("\\d+") && !p2.matches("\\d+")) {
                    return new String[]{p1, p2};
                }
            }
        }
        return null;
    }

    static double similarity(String left, String right) {
        if (left.equals(right)) return left.isEmpty() ? 0 : 1;
        if (left.isEmpty() || right.isEmpty()) return 0;
        if (left.contains(right) || right.contains(left)) return (double) Math.min(left.length(), right.length()) / Math.max(left.length(), right.length());
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            previous = current;
        }
        return Math.max(0, 1.0 - (double) previous[right.length()] / Math.max(left.length(), right.length()));
    }
}
