package com.homektv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.SnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KTV WebSocket 处理器（P1.13/P1.15/P1.16，详设§4.1/§4.2）。
 * - 连接建立即推送 sync_full 全量快照
 * - 接收 TV 上行 progress → 转发广播给 H5（歌词/进度同步）
 * - 接收 ping → 回 pong（心跳）
 *
 * KTV WebSocket handler (P1.13/P1.15/P1.16, detailed design §4.1/§4.2).
 * - Pushes a sync_full full snapshot upon connection establishment.
 * - Receives progress messages from TV → broadcasts to H5 clients (lyrics/progress sync).
 * - Receives ping → replies with pong (heartbeat).
 */
@Component
public class KtvWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(KtvWebSocketHandler.class);

    private final WsBroadcaster broadcaster;
    private final SnapshotService snapshotService;
    private final PlaybackService playbackService;
    private final TvOfflineWatcher tvOfflineWatcher;
    private final ObjectMapper mapper;
    private final Map<String, ProgressTracker> progressTrackers = new ConcurrentHashMap<>();

    static class ProgressTracker {
        long lastReportTimeMs;
        long lastPositionMs;
        Long lastQueueId;
    }

    public KtvWebSocketHandler(WsBroadcaster broadcaster, SnapshotService snapshotService,
                               PlaybackService playbackService, TvOfflineWatcher tvOfflineWatcher,
                               ObjectMapper mapper) {
        this.broadcaster = broadcaster;
        this.snapshotService = snapshotService;
        this.playbackService = playbackService;
        this.tvOfflineWatcher = tvOfflineWatcher;
        this.mapper = mapper;
    }

    /**
     * 连接建立后注册会话并推送全量快照；若为 TV 端则触发上线监听。
     *
     * Registers the session and pushes a full snapshot upon connection
     * establishment; triggers online watcher if the client is a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        broadcaster.register(session);
        if (isTv(session)) {
            tvOfflineWatcher.onTvConnected();
        }
        // 连接/重连即推全量快照（详设§4.1）
        broadcaster.sendTo(session, WsEvent.of(WsEvent.SYNC_FULL, snapshotService.snapshot()));
        log.debug("WS 连接建立: {}，当前在线 {}", session.getId(), broadcaster.sessionCount());
    }

    /**
     * 处理上行消息：ping/pong 心跳、TV 播放进度广播、曲目完成/播放错误切歌。
     *
     * Handles incoming messages: ping/pong heartbeat, TV playback progress
     * broadcast, track finished / play-error skip and broadcast.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param message 文本消息 / text message
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText("");

        switch (type) {
            case "ping" -> broadcaster.sendTo(session, WsEvent.of("pong", null));
            case "progress" -> {
                if (!isTv(session)) {
                    log.warn("拒绝非 TV 会话 [{}] 的 progress 上报", session.getId());
                    return;
                }
                long positionMs = node.path("payload").path("position_ms").asLong(0);
                if (positionMs < 0) return;

                Long reportedQueueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                Long curQueueId = playbackService.getCurrentQueueId();
                if (curQueueId == null) {
                    return;
                }
                if (reportedQueueId != null && !curQueueId.equals(reportedQueueId)) {
                    return;
                }

                long now = System.currentTimeMillis();
                ProgressTracker tracker = progressTrackers.computeIfAbsent(session.getId(), k -> new ProgressTracker());
                // 频率限制：同一客户端进度上报间隔不低于 400ms
                if (now - tracker.lastReportTimeMs < 400) {
                    return;
                }
                // 单调性检查：同首曲目下，如果倒退超过 2000ms 且非曲目重新开始（>1000ms），判定为抖动丢弃
                boolean sameTrack = curQueueId.equals(tracker.lastQueueId);
                if (sameTrack && tracker.lastPositionMs > 0 && positionMs < tracker.lastPositionMs - 2000 && positionMs > 1000) {
                    return;
                }
                tracker.lastReportTimeMs = now;
                tracker.lastPositionMs = positionMs;
                tracker.lastQueueId = curQueueId;

                broadcaster.broadcast(WsEvent.of(WsEvent.PROGRESS,
                        java.util.Map.of("position_ms", positionMs, "queue_id", curQueueId)));
            }
            case "finished" -> {
                if (!isTv(session)) {
                    log.warn("拒绝非 TV 会话 [{}] 的 finished 上报", session.getId());
                    return;
                }
                Long queueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                // TV 上报当前曲目播放完成 → 幂等推进队列并广播
                if (playbackService.onFinished(queueId)) {
                    broadcaster.broadcast(WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot()));
                }
            }
            case "play_error" -> {
                if (!isTv(session)) {
                    log.warn("拒绝非 TV 会话 [{}] 的 play_error 上报", session.getId());
                    return;
                }
                String reason = node.path("payload").path("message").asText("媒体读取失败");
                Long fileId = node.path("payload").path("file_id").isNumber()
                        ? node.path("payload").path("file_id").asLong() : null;
                Long queueId = node.path("payload").path("queue_id").isNumber()
                        ? node.path("payload").path("queue_id").asLong() : null;
                if (playbackService.onPlayError(fileId, queueId)) {
                    broadcaster.broadcast(WsEvent.of(WsEvent.TOAST,
                            java.util.Map.of("text", "当前歌曲播放失败，已自动切换下一首：" + reason)));
                    broadcaster.broadcast(WsEvent.of(WsEvent.NOW_PLAYING, snapshotService.snapshot()));
                }
            }
            default -> log.debug("未知 WS 消息类型: {}", type);
        }
    }

    /**
     * 连接关闭时注销会话；若为 TV 端则触发离线监听。
     *
     * Unregisters the session on close; triggers offline watcher if the
     * client was a TV.
     *
     * @param session WebSocket 会话 / WebSocket session
     * @param status  关闭状态 / close status
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        progressTrackers.remove(session.getId());
        notifyOfflineIfTv(broadcaster.unregister(session));
        log.debug("WS 连接关闭: {}，剩余在线 {}", session.getId(), broadcaster.sessionCount());
    }

    /**
     * 传输异常时注销会话并记录日志；若为 TV 端则触发离线监听。
     *
     * Unregisters the session and logs the error on transport failure;
     * triggers offline watcher if the client was a TV.
     *
     * @param session   WebSocket 会话 / WebSocket session
     * @param exception 异常 / the exception
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WS 传输错误 {}: {}", session.getId(), exception.getMessage());
        progressTrackers.remove(session.getId());
        notifyOfflineIfTv(broadcaster.unregister(session));
    }

    private boolean isTv(WebSocketSession session) {
        Object type = session.getAttributes().get("client_type");
        Object token = session.getAttributes().get("client_token");
        return "tv".equals(type == null ? null : type.toString())
                && token != null && !token.toString().isBlank();
    }

    private void notifyOfflineIfTv(String clientType) {
        if ("tv".equals(clientType)) {
            tvOfflineWatcher.onTvDisconnected();
        }
    }
}
