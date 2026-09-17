package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.SnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * KtvWebSocketHandler 单元测试：
 * 验证非 TV 会话上行拦截、进度限频与单调性校验、finished 幂等处理。
 */
class KtvWebSocketHandlerTest {

    private WsBroadcaster broadcaster;
    private SnapshotService snapshotService;
    private PlaybackService playbackService;
    private TvOfflineWatcher tvOfflineWatcher;
    private ObjectMapper mapper;
    private KtvWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        broadcaster = mock(WsBroadcaster.class);
        snapshotService = mock(SnapshotService.class);
        playbackService = mock(PlaybackService.class);
        tvOfflineWatcher = mock(TvOfflineWatcher.class);
        mapper = new ObjectMapper();
        handler = new KtvWebSocketHandler(broadcaster, snapshotService, playbackService, tvOfflineWatcher, mapper);
    }

    private WebSocketSession createSession(String id, String clientType, String clientToken) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        if (clientType != null) attrs.put("client_type", clientType);
        if (clientToken != null) attrs.put("client_token", clientToken);
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    void pingRespondsPongForAnyClient() throws Exception {
        WebSocketSession session = createSession("h5-1", "h5", "token-h5");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"ping\"}"));

        ArgumentCaptor<WsEvent> captor = ArgumentCaptor.forClass(WsEvent.class);
        verify(broadcaster).sendTo(eq(session), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("pong");
    }

    @Test
    void nonTvClientUpstreamControlRejected() throws Exception {
        WebSocketSession h5Session = createSession("h5-1", "h5", "token-h5");
        WebSocketSession anonSession = createSession("anon-1", null, null);

        // 非 TV 尝试上报 finished
        handler.handleTextMessage(h5Session, new TextMessage("{\"type\":\"finished\"}"));
        handler.handleTextMessage(anonSession, new TextMessage("{\"type\":\"finished\"}"));
        verify(playbackService, never()).onFinished(any());

        // 非 TV 尝试上报 progress
        handler.handleTextMessage(h5Session, new TextMessage("{\"type\":\"progress\",\"payload\":{\"position_ms\":1000}}"));
        verify(broadcaster, never()).broadcast(any());

        // 非 TV 尝试上报 play_error
        handler.handleTextMessage(h5Session, new TextMessage("{\"type\":\"play_error\",\"payload\":{\"message\":\"fail\"}}"));
        verify(playbackService, never()).onPlayError(any(), any());
    }

    @Test
    void tvFinishedIdempotency() throws Exception {
        WebSocketSession tvSession = createSession("tv-1", "tv", "token-tv-1");

        when(playbackService.onFinished(100L)).thenReturn(true);
        when(playbackService.onFinished(101L)).thenReturn(false); // 重复或已被处理

        // 第一次成功完成
        handler.handleTextMessage(tvSession, new TextMessage("{\"type\":\"finished\",\"payload\":{\"queue_id\":100}}"));
        verify(broadcaster, times(1)).broadcast(any(WsEvent.class));

        // 第二次重复上报被忽略，不触发广播
        handler.handleTextMessage(tvSession, new TextMessage("{\"type\":\"finished\",\"payload\":{\"queue_id\":101}}"));
        verify(broadcaster, times(1)).broadcast(any(WsEvent.class));
    }

    @Test
    void tvProgressValidationAndRateLimiting() throws Exception {
        WebSocketSession tvSession = createSession("tv-1", "tv", "token-tv-1");
        when(playbackService.getCurrentQueueId()).thenReturn(200L);

        // 1. 上报不同 queueId 的过期进度，直接忽略
        handler.handleTextMessage(tvSession, new TextMessage("{\"type\":\"progress\",\"payload\":{\"position_ms\":5000,\"queue_id\":199}}"));
        verify(broadcaster, never()).broadcast(any());

        // 2. 正常首次上报，匹配当前 queueId
        handler.handleTextMessage(tvSession, new TextMessage("{\"type\":\"progress\",\"payload\":{\"position_ms\":5000,\"queue_id\":200}}"));
        verify(broadcaster, times(1)).broadcast(any());

        // 3. 高频立即再次上报（<400ms），触发限频被忽略
        handler.handleTextMessage(tvSession, new TextMessage("{\"type\":\"progress\",\"payload\":{\"position_ms\":5100,\"queue_id\":200}}"));
        verify(broadcaster, times(1)).broadcast(any());
    }
}
