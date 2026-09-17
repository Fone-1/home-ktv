package com.homektv.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.CloseStatus;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket 会话注册与事件广播（P1.14，详设§4.1/§4.2）。
 * 线程安全：会话存于 ConcurrentHashMap，发送时对单会话加锁（WebSocketSession 非并发安全）。
 *
 * WebSocket session registration and event broadcasting (P1.14, detailed design §4.1/§4.2).
 * Thread-safe: sessions stored in ConcurrentHashMap, with per-session locking during send
 * (WebSocketSession is not concurrency-safe).
 */
@Component
public class WsBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(WsBroadcaster.class);
    public static final int MAX_QUEUE_CAPACITY = 50;

    private final Map<String, ClientSessionWorker> workers = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final ExecutorService broadcastExecutor;

    @Autowired
    public WsBroadcaster(ObjectMapper mapper) {
        this(mapper, Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "ws-broadcaster-worker");
            t.setDaemon(true);
            return t;
        }));
    }

    public WsBroadcaster(ObjectMapper mapper, ExecutorService broadcastExecutor) {
        this.mapper = mapper;
        this.broadcastExecutor = broadcastExecutor;
    }

    @PreDestroy
    public void destroy() {
        broadcastExecutor.shutdownNow();
    }

    /**
     * 客户端单会话异步发送工作单元。
     * 维护独立有界队列与最新 progress 引用，确保慢客户端不阻塞其他客户端，
     * 且高优先级的控制与状态事件始终优先发出。
     */
    static class ClientSessionWorker {
        final WebSocketSession session;
        final String clientType; // "tv" 或 "h5" 或 null
        final BlockingQueue<String> priorityQueue = new LinkedBlockingQueue<>(MAX_QUEUE_CAPACITY);
        final AtomicReference<String> latestProgressJson = new AtomicReference<>(null);
        final AtomicBoolean isDraining = new AtomicBoolean(false);
        volatile boolean closed = false;

        ClientSessionWorker(WebSocketSession session, String clientType) {
            this.session = session;
            this.clientType = clientType;
        }

        /**
         * 投递事件。若是进度事件则合并覆盖旧值；其他事件进入有界队列。
         * @return false 表示有界队列已满（慢客户端堆积溢出）
         */
        boolean offer(WsEvent event, String json) {
            if (closed || !session.isOpen()) return false;
            if (WsEvent.PROGRESS.equals(event.type())) {
                // progress 允许丢弃旧值，只保留最新进度
                latestProgressJson.set(json);
                return true;
            } else {
                // 控制与队列事件进入有界优先队列
                return priorityQueue.offer(json);
            }
        }

        void close() {
            closed = true;
            priorityQueue.clear();
            latestProgressJson.set(null);
        }
    }

    /**
     * 注册 WebSocket 会话。
     *
     * Registers a WebSocket session.
     * @param session 要注册的 WebSocket 会话 / the WebSocket session to register
     */
    public void register(WebSocketSession session) {
        Object type = session.getAttributes().get("client_type");
        String clientType = type != null ? type.toString() : null;
        ClientSessionWorker worker = new ClientSessionWorker(session, clientType);
        ClientSessionWorker prev = workers.put(session.getId(), worker);
        if (prev != null) {
            prev.close();
        }
    }

    /**
     * 注销会话，返回其 client_type（tv/h5，可能为 null），供离线清理等逻辑判断。
     *
     * Unregisters a session and returns its client_type (tv/h5, may be null) for offline cleanup logic.
     * @param session 要注销的 WebSocket 会话 / the WebSocket session to unregister
     * @return client_type（tv/h5，可能为 null）/ the client_type (tv/h5, may be null)
     */
    public String unregister(WebSocketSession session) {
        return unregisterById(session.getId());
    }

    public String unregisterById(String sessionId) {
        ClientSessionWorker worker = workers.remove(sessionId);
        if (worker != null) {
            worker.close();
            return worker.clientType;
        }
        return null;
    }

    public int sessionCount() {
        return workers.size();
    }

    /**
     * TV 是否在线（详设§4.5：点了歌但 TV 不在线时 H5 显示横幅）。
     *
     * Whether the TV is online (detailed design §4.5: H5 shows a banner when songs are queued but TV is offline).
     * @return true if TV is online
     */
    public boolean isTvOnline() {
        return workers.values().stream().anyMatch(w -> "tv".equals(w.clientType));
    }

    /**
     * 已连接的 H5 手机数。
     *
     * Number of connected H5 mobile clients.
     * @return the count of connected H5 sessions
     */
    public long h5Count() {
        return workers.values().stream().filter(w -> "h5".equals(w.clientType)).count();
    }

    /**
     * 向单个会话发送事件（如连接后的 sync_full）。
     *
     * Sends an event to a single session (e.g. sync_full after connection).
     * @param session 目标 WebSocket 会话 / the target WebSocket session
     * @param event 要发送的事件 / the event to send
     */
    public void sendTo(WebSocketSession session, WsEvent event) {
        ClientSessionWorker worker = workers.get(session.getId());
        if (worker == null || worker.closed || !session.isOpen()) return;
        String json = serialize(event);
        if (json == null) return;
        if (worker.offer(event, json)) {
            scheduleDrain(worker);
        } else {
            handleOverflow(worker);
        }
    }

    /**
     * 向所有在线会话广播事件。
     *
     * Broadcasts an event to all online sessions.
     * @param event 要广播的事件 / the event to broadcast
     */
    public void broadcast(WsEvent event) {
        String json = serialize(event);
        if (json == null) return;
        boolean isProgress = WsEvent.PROGRESS.equals(event.type());

        for (ClientSessionWorker worker : workers.values()) {
            // 过滤：TV 端不需要接收自身上报的播放进度广播
            if (isProgress && "tv".equals(worker.clientType)) {
                continue;
            }
            if (worker.offer(event, json)) {
                scheduleDrain(worker);
            } else {
                handleOverflow(worker);
            }
        }
    }

    private void scheduleDrain(ClientSessionWorker worker) {
        if (worker.isDraining.compareAndSet(false, true)) {
            try {
                broadcastExecutor.submit(() -> drain(worker));
            } catch (Exception e) {
                worker.isDraining.set(false);
                log.warn("调度异步发送任务失败: {}", e.getMessage());
            }
        }
    }

    private void drain(ClientSessionWorker worker) {
        try {
            while (!worker.closed && worker.session.isOpen()) {
                // 先消费高优先级队列（控制指令、队列快照、切歌等）
                String nextJson = worker.priorityQueue.poll();
                if (nextJson == null) {
                    // 队列为空时，取最新的进度事件（仅保留最新值）
                    nextJson = worker.latestProgressJson.getAndSet(null);
                }
                if (nextJson == null) {
                    break;
                }
                synchronized (worker.session) {
                    if (worker.session.isOpen()) {
                        worker.session.sendMessage(new TextMessage(nextJson));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("WS 发送失败，清理移除会话 {}: {}", worker.session.getId(), e.getMessage());
            closeSessionQuietly(worker.session, CloseStatus.SERVER_ERROR);
            unregisterById(worker.session.getId());
        } finally {
            worker.isDraining.set(false);
            // 双重检查：避免在退出循环到重置标志期间有新事件并发进入而遗漏
            if (!worker.closed && worker.session.isOpen() &&
                    (!worker.priorityQueue.isEmpty() || worker.latestProgressJson.get() != null)) {
                scheduleDrain(worker);
            }
        }
    }

    private String serialize(WsEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("事件序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private void handleOverflow(ClientSessionWorker worker) {
        log.warn("WS 慢客户端有界队列已满 (容量 {}), 安全关闭会话: {}", MAX_QUEUE_CAPACITY, worker.session.getId());
        closeSessionQuietly(worker.session, CloseStatus.SESSION_NOT_RELIABLE);
        unregisterById(worker.session.getId());
    }

    private void closeSessionQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {}
    }
}
