package com.homektv.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homektv.domain.Song;
import com.homektv.repo.PlayerStateRepository;
import com.homektv.repo.QueueItemRepository;
import com.homektv.repo.SongRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 实时通道集成测试（P1.13-P1.15 验收，详设§4.1/§4.2）。
 * 用真实 WS 客户端连接：验证连接推 sync_full、点歌后广播 queue_updated、progress 转发。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class WebSocketIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ktv").withUsername("ktv").withPassword("ktv");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort int port;
    @Autowired SongRepository songRepo;
    @Autowired QueueItemRepository queueRepo;
    @Autowired PlayerStateRepository playerRepo;
    @Autowired com.homektv.web.ControlController controlController;
    @Autowired ObjectMapper mapper;

    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        var ps = playerRepo.getSingleton();
        ps.setCurrentQueueId(null);
        ps.setState("idle");
        playerRepo.save(ps);
        queueRepo.deleteAll();
        songRepo.deleteAll();
        received.clear();
    }

    private WebSocketSession connect() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                received.offer(message.getPayload());
            }
        }, new WebSocketHttpHeaders(), URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
    }

    private JsonNode awaitEvent(String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String msg = received.poll(5, TimeUnit.SECONDS);
            if (msg == null) continue;
            JsonNode node = mapper.readTree(msg);
            if (type.equals(node.path("type").asText())) return node;
        }
        throw new AssertionError("未收到事件: " + type);
    }

    @Test
    void connectReceivesSyncFull() throws Exception {
        WebSocketSession session = connect();
        JsonNode ev = awaitEvent(WsEvent.SYNC_FULL);
        assertThat(ev.path("payload")).isNotNull();
        assertThat(ev.path("payload").has("state")).isTrue();
        session.close();
    }

    @Test
    void orderBroadcastsQueueUpdatedAndNowPlaying() throws Exception {
        Song s1 = new Song();
        s1.setTitle("晴天"); s1.setArtist("周杰伦");
        s1.setMediaType("KTV_VIDEO"); s1.setFingerprint("ws-fp-1-" + System.nanoTime());
        Long songId1 = songRepo.save(s1).getId();

        WebSocketSession session = connect();
        awaitEvent(WsEvent.SYNC_FULL); // 先消费连接快照

        // 1. 空闲状态点歌：立即开播，广播 now_playing
        var req1 = new com.homektv.web.dto.ControlRequest(
                "order", java.util.Map.of("song_id", songId1), "tok-ws-1");
        controlController.control(req1);

        JsonNode playingEvent = awaitEvent(WsEvent.NOW_PLAYING);
        assertThat(playingEvent.path("payload").path("state").asText()).isEqualTo("playing");
        assertThat(playingEvent.path("payload").path("playing").path("song").path("id").asLong())
                .isEqualTo(songId1);

        // 2. 播放中再次点歌：进入等待队列，广播 queue_updated
        Song s2 = new Song();
        s2.setTitle("七里香"); s2.setArtist("周杰伦");
        s2.setMediaType("KTV_VIDEO"); s2.setFingerprint("ws-fp-2-" + System.nanoTime());
        Long songId2 = songRepo.save(s2).getId();

        var req = new com.homektv.web.dto.ControlRequest(
                "order", java.util.Map.of("song_id", songId2), "tok-ws-1");
        controlController.control(req);

        JsonNode queueEvent = awaitEvent(WsEvent.QUEUE_UPDATED);
        assertThat(queueEvent.path("payload").path("list").isArray()).isTrue();
        assertThat(queueEvent.path("payload").path("list").get(0).path("song").path("id").asLong())
                .isEqualTo(songId2);
        session.close();
    }

    @Test
    void pingReceivesPong() throws Exception {
        WebSocketSession session = connect();
        awaitEvent(WsEvent.SYNC_FULL);
        session.sendMessage(new TextMessage("{\"type\":\"ping\"}"));
        JsonNode ev = awaitEvent("pong");
        assertThat(ev.path("type").asText()).isEqualTo("pong");
        session.close();
    }
}
