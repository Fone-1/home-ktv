package com.homektv.library;

import com.homektv.domain.Song;
import com.homektv.repo.SongSearchRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 歌曲搜索服务（P1.6）。归一化关键词后调用综合查询。
 */
@Service
public class SongSearchService {

    private static final int PAGE_SIZE = 50;

    private final SongSearchRepository searchRepo;

    public SongSearchService(SongSearchRepository searchRepo) {
        this.searchRepo = searchRepo;
    }

    /**
     * 综合搜索（兼容旧调用，不限媒体类型）。
     */
    public List<Song> search(String keyword, int page) {
        return search(keyword, null, page);
    }

    /**
     * 综合搜索（下推媒体类型过滤）。关键词裁剪空白；拼音匹配走小写。
     * 空关键词返回空列表（前端应展示热榜而非全量）。
     */
    public List<Song> search(String keyword, String type, int page) {
        if (keyword == null || keyword.isBlank()) return List.of();
        // 拼音字段均为小写存储，中文 ILIKE 不受影响，这里统一小写以命中 init/py 条件
        String kw = keyword.trim().toLowerCase();
        String normalizedType = (type == null || type.isBlank()) ? null : type.trim().toUpperCase();
        return searchRepo.search(kw, normalizedType, PageRequest.of(Math.max(0, page), PAGE_SIZE));
    }
}
