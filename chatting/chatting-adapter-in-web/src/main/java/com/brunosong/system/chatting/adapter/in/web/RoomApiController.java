package com.brunosong.system.chatting.adapter.in.web;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomApiController {

    private final ChatRoomRegistry registry;

    @GetMapping
    public List<Map<String, Object>> list() {
        return registry.list().stream()
                .sorted(Comparator.comparing(ChatRoomRegistry.Room::getCreatedAt).reversed())
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("clientName", r.getClientName());
                    m.put("status", r.getStatus().name());
                    m.put("lastMessage", r.getLastMessage());
                    m.put("createdAt", r.getCreatedAt().toString());
                    return m;
                })
                .toList();
    }
}
