package com.brunosong.system.chatting.adapter.in.web;

import com.brunosong.system.chatting.application.port.in.SendMessageUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final String K_ROOM = "roomId";
    private static final String K_ROLE = "role";
    private static final String K_NAME = "name";

    private final SendMessageUseCase sendMessageUseCase;
    private final ChatRoomRegistry registry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        URI uri = session.getUri();
        String path = uri.getPath(); // /ws/chat/{roomId}
        String roomId = path.substring(path.lastIndexOf('/') + 1);
        Map<String, String> q = parseQuery(uri.getQuery());
        String role = q.getOrDefault("role", "client");
        String name = URLDecoder.decode(q.getOrDefault("name", "anonymous"), StandardCharsets.UTF_8);

        session.getAttributes().put(K_ROOM, roomId);
        session.getAttributes().put(K_ROLE, role);
        session.getAttributes().put(K_NAME, name);

        if ("admin".equals(role)) {
            registry.markActive(roomId);
            registry.addSession(roomId, session);
            broadcast(roomId, "system", "상담사가 입장했습니다.");
        } else {
            registry.createOrGet(roomId, name);
            registry.addSession(roomId, session);
            broadcast(roomId, "system", name + "님이 상담을 요청했습니다.");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String roomId = (String) session.getAttributes().get(K_ROOM);
        String role = (String) session.getAttributes().get(K_ROLE);
        String name = (String) session.getAttributes().get(K_NAME);
        String sender = "admin".equals(role) ? "상담사" : name;
        String content = message.getPayload();

        sendMessageUseCase.send(roomId, sender, content);
        registry.recordMessage(roomId, sender, content);
        broadcast(roomId, sender, content);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.removeSession(session);
    }

    private void broadcast(String roomId, String sender, String content) throws Exception {
        TextMessage out = new TextMessage(sender + "|" + content);
        for (WebSocketSession s : registry.sessions(roomId)) {
            if (s.isOpen()) s.sendMessage(out);
        }
    }

    private Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(p.substring(0, i), p.substring(i + 1));
        }
        return m;
    }
}
