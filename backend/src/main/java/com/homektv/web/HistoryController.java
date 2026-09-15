package com.homektv.web;

import com.homektv.domain.PlayHistory;
import com.homektv.domain.Song;
import com.homektv.repo.PlayHistoryRepository;
import com.homektv.repo.AppUserRepository;
import com.homektv.repo.SongRepository;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.domain.QueueItem;
import com.homektv.domain.AppUser;
import com.homektv.web.dto.RecentHistoryDto;
import com.homektv.web.dto.SongDto;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 已播历史（P3.2，详设§7 H5-05）：今晚已唱列表，供「再唱一遍」（前端用 song id 再点歌）。
 *
 * Playback history (P3.2, detail design §7 H5-05): tonight's already-sung list,
 * used for "sing again" (the frontend re-orders by song id).
 */
@RestController
@RequestMapping("/api")
public class HistoryController {

    private final PlayHistoryRepository historyRepo;
    private final SongRepository songRepo;
    private final AppUserRepository userRepo;
    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final SnapshotService snapshotService;
    private final UserService userService;
    private final WsBroadcaster broadcaster;

    /**
     * 构造函数，注入所有依赖服务与仓库。
     *
     * Constructor, injects all dependent services and repositories.
     */
    public HistoryController(PlayHistoryRepository historyRepo, SongRepository songRepo,
                             AppUserRepository userRepo, QueueService queueService,
                             PlaybackService playbackService, SnapshotService snapshotService,
                             UserService userService, WsBroadcaster broadcaster) {
        this.historyRepo = historyRepo;
        this.songRepo = songRepo;
        this.userRepo = userRepo;
        this.queueService = queueService;
        this.playbackService = playbackService;
        this.snapshotService = snapshotService;
        this.userService = userService;
        this.broadcaster = broadcaster;
    }

    /**
     * 查询最近 50 条已播歌曲记录。
     *
     * Returns the most recent 50 playback history entries as song DTOs.
     * @return 最近已播歌曲列表 / the recent playback song list
     */
    @GetMapping("/history")
    public List<SongDto> history() {
        List<PlayHistory> records = historyRepo.findTop50ByOrderByPlayedAtDesc();
        Map<Long, Song> songs = loadSongs(records.stream().map(PlayHistory::getSongId).toList());
        return records.stream()
                .map(h -> songs.get(h.getSongId()))
                .filter(s -> s != null)
                .map(SongDto::from)
                .toList();
    }

    /**
     * 查询最近播放记录（含演唱者昵称和是否为当前用户所点），支持仅看我的。
     *
     * Returns recent playback history with singer nickname and ownership info,
     * optionally filtered to the current user's own entries.
     * @param clientToken 客户端令牌，用于识别当前用户 / client token to identify the current user
     * @param mine        是否只返回当前用户点播的记录 / whether to return only the current user's entries
     * @return 最近播放记录列表 / the recent playback history list
     */
    @GetMapping("/history/recent")
    public List<RecentHistoryDto> recent(@RequestParam(required = false) String clientToken,
                                         @RequestParam(defaultValue = "false") boolean mine) {
        Long currentUserId = clientToken == null || clientToken.isBlank()
                ? null : userRepo.findByClientToken(clientToken).map(AppUser::getId).orElse(null);
        List<PlayHistory> records = historyRepo.findTop50ByOrderByPlayedAtDesc();
        if (mine) {
            records = records.stream()
                    .filter(h -> currentUserId != null && currentUserId.equals(h.getPlayedBy()))
                    .toList();
        }
        Map<Long, Song> songs = loadSongs(records.stream().map(PlayHistory::getSongId).toList());
        Map<Long, String> nicknames = loadNicknames(records.stream().map(PlayHistory::getPlayedBy).toList());
        return records.stream()
                .filter(h -> songs.containsKey(h.getSongId()))
                .map(h -> {
                    String nickname = h.getPlayedBy() == null ? "家人"
                            : nicknames.getOrDefault(h.getPlayedBy(), "家人");
                    return new RecentHistoryDto(h.getId(), SongDto.from(songs.get(h.getSongId())), h.getPlayedBy(),
                            nickname, currentUserId != null && currentUserId.equals(h.getPlayedBy()), h.getPlayedAt());
                })
                .toList();
    }

    /** 历史歌曲批量加载：先收集去重 ID，再一次性 findAllById，避免逐条 findById 的 N+1。 */
    private Map<Long, Song> loadSongs(List<Long> songIds) {
        Set<Long> ids = songIds.stream().filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();
        return songRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Song::getId, Function.identity(), (a, b) -> a));
    }

    /** 历史用户昵称批量加载，行为同 {@link #loadSongs}。 */
    private Map<Long, String> loadNicknames(List<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(id -> id != null)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) return Map.of();
        return userRepo.findAllById(ids).stream()
                .filter(u -> u.getNickname() != null)
                .collect(Collectors.toMap(AppUser::getId, AppUser::getNickname, (a, b) -> a));
    }

    /**
     * 「再唱一遍」：根据历史记录重新点歌，加入播放队列并广播状态更新。
     *
     * "Sing again": re-orders the song from history, enqueues it,
     * and broadcasts queue / playback status updates.
     * @param historyId 历史记录 ID / the history record ID
     * @param request   包含 clientToken 和是否强插的请求体 / request body with client token and force flag
     * @return 包含 queueId、排队位置和队列快照的 map / map with queueId, position, and snapshot
     */
    @PostMapping("/history/{historyId}/repeat")
    public Map<String, Object> repeat(@PathVariable Long historyId, @RequestBody RepeatRequest request) {
        PlayHistory history = historyRepo.findById(historyId)
                .orElseThrow(() -> new ApiException("HISTORY_NOT_FOUND", "历史记录不存在"));
        Long userId = userService.resolveUserId(request.clientToken());
        QueueItem item = queueService.order(history.getSongId(), userId, request.force());
        int position = queueService.waitingPosition(item);
        boolean started = playbackService.startIfIdle();
        var snapshot = snapshotService.snapshot();
        broadcaster.broadcast(WsEvent.of(WsEvent.QUEUE_UPDATED, snapshot));
        if (started) broadcaster.broadcast(WsEvent.of(WsEvent.NOW_PLAYING, snapshot));
        return Map.of("queueId", item.getId(), "position", position, "snapshot", snapshot);
    }

    public record RepeatRequest(String clientToken, boolean force) {}
}
