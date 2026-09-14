package com.homektv.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.QueueItem;
import com.homektv.queue.PlaybackService;
import com.homektv.queue.QueueService;
import com.homektv.queue.SnapshotService;
import com.homektv.queue.UserService;
import com.homektv.web.dto.ControlRequest;
import com.homektv.web.dto.QueueSnapshot;
import com.homektv.ws.WsBroadcaster;
import com.homektv.ws.WsEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ControlControllerTest {

    @Test
    void orderWithPriorityReturnsPositionAndBroadcastsOnce() {
        AtomicInteger broadcastCount = new AtomicInteger();
        AtomicReference<WsEvent> broadcastEvent = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "accompaniment", true, 1);

        QueueItem item = new QueueItem();
        item.setId(99L);
        item.setSongId(10L);

        QueueService queueService = new QueueService(null, null, null) {
            @Override
            public OrderResult order(Long songId, Long userId, boolean force, boolean priority) {
                assertThat(priority).isTrue();
                assertThat(songId).isEqualTo(10L);
                return new OrderResult(item, 1, false, false);
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcast(WsEvent event) {
                broadcastCount.incrementAndGet();
                broadcastEvent.set(event);
            }
        };
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public boolean startIfIdle() { return false; }
        };
        ControlController controller = new ControlController(
                queueService, playbackService, snapshotService, userService, null, broadcaster);

        QueueSnapshot result = controller.control(
                new ControlRequest("order", Map.of("song_id", 10L, "priority", true), "user-tok"));

        assertThat(broadcastCount.get()).isEqualTo(1);
        assertThat(broadcastEvent.get().type()).isEqualTo(WsEvent.QUEUE_UPDATED);
        assertThat(result.queueId()).isEqualTo(99L);
        assertThat(result.position()).isEqualTo(1);
        assertThat(result.playbackStarted()).isFalse();
        assertThat(result.duplicated()).isFalse();
    }

    @Test
    void orderStartsPlaybackWhenIdleAndBroadcastsNowPlayingOnce() {
        AtomicInteger broadcastCount = new AtomicInteger();
        AtomicReference<WsEvent> broadcastEvent = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "accompaniment", true, 1);

        QueueItem item = new QueueItem();
        item.setId(101L);
        item.setSongId(20L);

        QueueService queueService = new QueueService(null, null, null) {
            @Override
            public OrderResult order(Long songId, Long userId, boolean force, boolean priority) {
                return new OrderResult(item, 1, false, false);
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcast(WsEvent event) {
                broadcastCount.incrementAndGet();
                broadcastEvent.set(event);
            }
        };
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public boolean startIfIdle() { return true; }
        };
        ControlController controller = new ControlController(
                queueService, playbackService, snapshotService, userService, null, broadcaster);

        QueueSnapshot result = controller.control(
                new ControlRequest("order", Map.of("song_id", 20L), "user-tok"));

        assertThat(broadcastCount.get()).isEqualTo(1);
        assertThat(broadcastEvent.get().type()).isEqualTo(WsEvent.NOW_PLAYING);
        assertThat(result.playbackStarted()).isTrue();
        assertThat(result.position()).isEqualTo(0);
    }

    @Test
    void anyUserCanShuffleWaitingQueue() {
        AtomicBoolean shuffled = new AtomicBoolean();
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "idle", 60, false,
                "accompaniment", true, 1);
        QueueService queueService = new QueueService(null, null, null) {
            @Override public List<QueueItem> shuffleWaiting() {
                shuffled.set(true);
                return List.of();
            }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcast(WsEvent event) { broadcast.set(event); }
        };
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null);
        ControlController controller = new ControlController(
                queueService, playbackService, snapshotService, userService, null, broadcaster);

        QueueSnapshot result = controller.control(
                new ControlRequest("shuffle", Map.of(), "guest-token"));

        assertThat(shuffled).isTrue();
        assertThat(broadcast.get().type()).isEqualTo(WsEvent.QUEUE_UPDATED);
        assertThat(result).isSameAs(snapshot);
    }

    @Test
    void restartBroadcastsDedicatedEventForTvSeek() {
        AtomicReference<WsEvent> broadcast = new AtomicReference<>();
        QueueSnapshot snapshot = new QueueSnapshot(null, List.of(), "playing", 60, false,
                "accompaniment", true, 1);
        PlaybackService playbackService = new PlaybackService(null, null, null, null, null) {
            @Override public com.homektv.domain.PlayerState restart() { return null; }
        };
        SnapshotService snapshotService = new SnapshotService(null, null, null, null, null) {
            @Override public QueueSnapshot snapshot() { return snapshot; }
        };
        UserService userService = new UserService(null) {
            @Override public Long resolveUserId(String clientToken) { return 42L; }
        };
        WsBroadcaster broadcaster = new WsBroadcaster(new ObjectMapper()) {
            @Override public void broadcast(WsEvent event) { broadcast.set(event); }
        };
        ControlController controller = new ControlController(
                null, playbackService, snapshotService, userService, null, broadcaster);

        controller.control(new ControlRequest("restart", Map.of(), "guest-token"));

        assertThat(broadcast.get().type()).isEqualTo(WsEvent.PLAYBACK_RESTARTED);
    }
}
