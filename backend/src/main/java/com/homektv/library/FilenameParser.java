package com.homektv.library;

/**
 * 文件名规则兜底解析（P1.2，详设§9.3）。
 * 标签缺失时，从文件名按常见模式解析歌手/歌名：
 *   「歌手 - 歌名」「歌名 - 歌手」「歌手－歌名」（全角）等。
 * 分隔符两侧的空白会被裁剪；无法拆分时归入未识别。
 */
public final class FilenameParser {

    private FilenameParser() {}

    /**
     * @param filename 文件名（可含扩展名）
     * @return 解析结果；无法识别时 recognized=false，title 回退为去扩展名的文件名
     */
    public static ParsedMeta parse(String filename) {
        return parse(filename, "artist_title");
    }

    public static ParsedMeta parse(String filename, String rule) {
        String base = stripExtension(filename).trim();
        if (base.isBlank()) return ParsedMeta.unrecognized(base);

        // Strip catalogue numbers and transport/version markers before identifying fields.
        base = base.replaceFirst("^\\s*\\d{1,5}\\s*[-._、)】]\\s*", "");
        base = base.replaceAll("\\s*\\[(?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK)\\]\\s*$", "");
        base = base.replaceAll("\\s*\\((?:KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK|Official Video)\\)\\s*$", "");
        base = base.replaceAll("(?i)\\s*[-|]\\s*(KTV|MTV|MV|LIVE|伴奏|原唱|消音|卡拉OK)\\s*$", "");
        // 去除尾部常见的下载任务 ID 或版本序号（如 " - 1", " - 3"）
        base = base.replaceAll("\\s*[-_]\\s*\\d{1,5}\\s*$", "");

        // 统一常见分隔符为标准 " - "
        String normalized = base
                .replace('－', '-')   // 全角连字符
                .replace('—', '-')    // 破折号
                .replace('_', '-')
                .replace('–', '-')
                .replace('｜', '|');

        // 用 " - " 或 "-" 分隔（优先带空格的）
        String[] parts = splitByDash(normalized);
        if (parts == null) {
            // 无分隔符：整个作为歌名，歌手未知
            return ParsedMeta.of(base.trim(), "");
        }

        String left = cleanPart(parts[0]);
        String right = cleanPart(parts[1]);
        if (left.isEmpty() || right.isEmpty()) {
            return ParsedMeta.of(base.trim(), "");
        }
        if (left.matches("(?i)track|track\\s*\\d*") && right.matches("\\d{2,}")) {
            return ParsedMeta.unrecognized(base.trim());
        }

        // 若右侧部分本身还包含类似 "002.周杰伦-晴天" 的内嵌歌手-歌名结构，进一步拆解
        String[] nested = splitByDash(right);
        if (nested != null) {
            String nestedLeft = cleanPart(nested[0]);
            String nestedRight = cleanPart(nested[1]);
            if (!nestedLeft.isEmpty() && !nestedRight.isEmpty() && !nestedLeft.matches("\\d+") && !nestedRight.matches("\\d+")) {
                left = nestedLeft;
                right = nestedRight;
            }
        }

        return "title_artist".equals(rule) ? ParsedMeta.of(left, right) : ParsedMeta.of(right, left);
    }

    private static String[] splitByDash(String s) {
        for (String delimiter : new String[]{" - ", "|", "/", "\\\\", "-"}) {
            int idx = s.indexOf(delimiter);
            if (idx > 0 && idx < s.length() - delimiter.length()) {
                return new String[]{ s.substring(0, idx), s.substring(idx + delimiter.length()) };
            }
        }
        return null;
    }

    private static String cleanPart(String value) {
        String cleaned = value.replaceAll("^\\s*[【\\[][^】\\]]+[】\\]]\\s*", "");
        // 去除段内前导曲目序号（如 002.）
        cleaned = cleaned.replaceFirst("^\\s*\\d{1,5}\\s*[-._、)】]\\s*", "");
        // 去除段内尾部版本序号（如 - 1）
        cleaned = cleaned.replaceAll("\\s*[-_]\\s*\\d{1,5}\\s*$", "");
        return cleaned.trim();
    }

    static String stripExtension(String filename) {
        if (filename == null) return "";
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slash >= 0 ? filename.substring(slash + 1) : filename;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
