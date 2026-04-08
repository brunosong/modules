package com.brunosong.system.chatting.adapter.in.web;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 1:1 상담 채팅방의 in-memory 레지스트리.
 * - 방 메타데이터(상태, 최근 메시지 등)
 * - 방별 WebSocket 세션 집합
 */
@Component
public class ChatRoomRegistry {

    public enum Status { WAITING, ACTIVE, CLOSED }

    public static class Room {
        private final String id;
        private final String clientName;
        private final Instant createdAt;
        private volatile Status status;
        private volatile String lastMessage;

        Room(String id, String clientName) {
            this.id = id;
            this.clientName = clientName;
            this.createdAt = Instant.now();
            this.status = Status.WAITING;
            this.lastMessage = "";
        }

        public String getId() { return id; }
        public String getClientName() { return clientName; }
        public Instant getCreatedAt() { return createdAt; }
        public Status getStatus() { return status; }
        public String getLastMessage() { return lastMessage; }
    }

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Set<WebSocketSession>> sessionsByRoom = new ConcurrentHashMap<>();

    public Room createOrGet(String roomId, String clientName) {
        return rooms.computeIfAbsent(roomId, id -> new Room(id, clientName));
    }

    public void markActive(String roomId) {
        Room r = rooms.get(roomId);
        if (r != null) r.status = Status.ACTIVE;
    }

    public void recordMessage(String roomId, String sender, String content) {
        Room r = rooms.get(roomId);
        if (r != null) r.lastMessage = sender + ": " + content;
    }

    public void addSession(String roomId, WebSocketSession session) {
        sessionsByRoom.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void removeSession(WebSocketSession session) {
        sessionsByRoom.values().forEach(set -> set.remove(session));
    }

    public Set<WebSocketSession> sessions(String roomId) {
        return sessionsByRoom.getOrDefault(roomId, Set.of());
    }

    public Collection<Room> list() {
        return rooms.values();
    }
}
