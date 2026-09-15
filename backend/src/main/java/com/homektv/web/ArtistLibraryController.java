package com.homektv.web;

import com.homektv.library.ArtistLibraryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/artists")
public class ArtistLibraryController {
    private final ArtistLibraryService service;

    public ArtistLibraryController(ArtistLibraryService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String gender,
                                    @RequestParam(required = false) Boolean reviewed,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    @RequestParam(required = false) Integer limit) {
        // limit 兼容旧客户端；有 page/size 时走分页，避免管理后台一次加载全部歌手
        int effectiveSize = limit != null ? Math.max(1, Math.min(limit, 200)) : size;
        Page<Map<String, Object>> result = service.page(keyword, gender, reviewed, page, effectiveSize);
        return Map.of(
                "content", result.getContent(),
                "total", result.getTotalElements(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalPages", result.getTotalPages()
        );
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody AnalyzeRequest request) { return service.analyze(request.artist()); }

    @PostMapping("/analyze-batch")
    public List<Map<String, Object>> analyzeBatch(@RequestBody BatchAnalyzeRequest request) {
        return service.analyzeBatch(request == null ? List.of() : request.artists());
    }

    @PostMapping("/apply")
    public Map<String, Object> apply(@RequestBody ApplyRequest request) { return service.apply(request.artist(), request.gender()); }

    public record AnalyzeRequest(String artist) {}
    public record BatchAnalyzeRequest(List<String> artists) {}
    public record ApplyRequest(String artist, String gender) {}
}
