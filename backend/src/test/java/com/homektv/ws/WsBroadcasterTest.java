package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * WsBroadcaster 独立 JVM 单元测试：
 * 验证异步有界队列、慢客户端防阻塞、progress 过滤与最新值合并、高优先级事件优先分发。
 */
class WsBroadcasterTest {

    private ObjectMapper mapper;
    private ExecutorService executor;
    private WsBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        executor = Executors.newFixedThreadPool(4);
        broadcaster = new WsBroadcaster(mapper, executor);
    }

    @AfterEach
    void tearDown() {
        broadcaster.destroy();
        executor.shutdownNow();
    }

    private WebSocketSession mockSession(String id, String clientType) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        if (clientType != null) {
            attrs.put("client_type", clientType);
            attrs.put("client_token", "token-" + id);
        }
        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    void sessionRegistrationAndTracking() {
        WebSocketSession tvSession = mockSession("tv-1", "tv");
        WebSocketSession h5Session1 = mockSession("h5-1", "h5");
        WebSocketSession h5Session2 = mockSession("h5-2", "h5");

        broadcaster.register(tvSession);
        broadcaster.register(h5Session1);
        broadcaster.register(h5Session2);

        assertThat(broadcaster.sessionCount()).isEqualTo(3);
        assertThat(broadcaster.isTvOnline()).isTrue();
        assertThat(broadcaster.h5Count()).isEqualTo(2);

        String unregType = broadcaster.unregister(tvSession);
        assertThat(unregType).isEqualTo("tv");
        assertThat(broadcaster.isTvOnline()).isFalse();
        assertThat(broadcaster.sessionCount()).isEqualTo(2);
    }

    @Test
    void tvSessionSkipsProgressBroadcast() throws Exception {
        WebSocketSession tv = mockSession("tv-1", "tv");
        WebSocketSession h5 = mockSession("h5-1", "h5");
        broadcaster.register(tv);
        broadcaster.register(h5);

        CountDownLatch h5Latch = new CountDownLatch(1);
        doAnswer(inv -> {
            h5Latch.countDown();
            return null;
        }).when(h5).sendMessage(any());

        broadcaster.broadcast(WsEvent.of(WsEvent.PROGRESS, Map.of("position_ms", 15000L)));

        assertThat(h5Latch.await(2, TimeUnit.SECONDS)).isTrue();
        // TV 端不得接收自身的 progress 反弹
        verify(tv, never()).sendMessage(any());
        // H5 端必须接收到 progress
        verify(h5, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void controlEventDeliveredToAllSessions() throws Exception {
        WebSocketSession tv = mockSession("tv-1", "tv");
        WebSocketSession h5 = mockSession("h5-1", "h5");
        broadcaster.register(tv);
        broadcaster.register(h5);

        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(inv -> { latch.countDown(); return null; }).when(tv).sendMessage(any());
        doAnswer(inv -> { latch.countDown(); return null; }).when(h5).sendMessage(any());

        broadcaster.broadcast(WsEvent.of(WsEvent.NOW_PLAYING, Map.of("state", "playing")));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        verify(tv, times(1)).sendMessage(any(TextMessage.class));
        verify(h5, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void slowClientBoundedQueueOverflowTriggersDisconnect() throws Exception {
        WebSocketSession slowSession = mockSession("slow-1", "h5");
        // 模拟慢客户端一直阻塞在 sendMessage 中
        CountDownLatch blockingLatch = new CountDownLatch(1);
        doAnswer(inv -> {
            blockingLatch.await(5, TimeUnit.SECONDS);
            return null;
        }).when(slowSession).sendMessage(any());

        broadcaster.register(slowSession);

        // 快速推送超过 50 个高优先级事件
        for (int i = 0; i < 60; i++) {
            broadcaster.sendTo(slowSession, WsEvent.of(WsEvent.QUEUE_UPDATED, Map.of("seq", i)));
        }

        // 超过队列容量后，该慢客户端会话必须被自动安全关闭并注销
        verify(slowSession, timeout(2000)).close(CloseStatus.SESSION_NOT_RELIABLE);
        assertThat(broadcaster.sessionCount()).isEqualTo(0);

        blockingLatch.countDown();
    }

    @Test
    void progressMergesAndPriorityDeliveredFirst() throws Exception {
        WebSocketSession session = mockSession("h5-merge", "h5");
        List<String> receivedPayloads = new CopyOnWriteArrayList<>();

        CountDownLatch firstMessageStarted = new CountDownLatch(1);
        CountDownLatch allowDrainToContinue = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(3);

        doAnswer(inv -> {
            TextMessage msg = inv.getArgument(0);
            receivedPayloads.add(msg.getPayload());
            firstMessageStarted.countDown();
            if (receivedPayloads.size() == 1) {
                // 暂停发送第一条消息，确保后续多条事件在同一调度周期内积压
                allowDrainToContinue.await(2, TimeUnit.SECONDS);
            }
            finishLatch.countDown();
            return null;
        }).when(session).sendMessage(any());

        broadcaster.register(session);

        // 发送首个消息触发 drain 启动并在第一条处阻塞
        broadcaster.sendTo(session, WsEvent.of(WsEvent.SYNC_FULL, Map.of("step", 0)));
        assertThat(firstMessageStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // 在阻塞期间，积压两个 progress 与一个高优先级 NOW_PLAYING
        broadcaster.sendTo(session, WsEvent.of(WsEvent.PROGRESS, Map.of("position_ms", 1000L)));
        broadcaster.sendTo(session, WsEvent.of(WsEvent.PROGRESS, Map.of("position_ms", 2000L)));
        broadcaster.sendTo(session, WsEvent.of(WsEvent.NOW_PLAYING, Map.of("step", 1)));

        // 放行 drain 继续发送
        allowDrainToContinue.countDown();

        // 总计发出 3 条消息：sync_full(step 0) + now_playing(step 1) + 合并后的最新进度(2000ms)
        // 1000ms 被原子覆盖丢弃，且 now_playing 必须先于 progress 发出
        assertThat(finishLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedPayloads).hasSize(3);
        assertThat(receivedPayloads.get(0)).contains("sync_full");
        assertThat(receivedPayloads.get(1)).contains("now_playing");
        assertThat(receivedPayloads.get(2)).contains("2000");
        assertThat(receivedPayloads.get(2)).doesNotContain("1000");
    }
}
