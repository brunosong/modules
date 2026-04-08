package com.brunosong.system.chatting.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

@Getter
@RequiredArgsConstructor
public class Message {
    private final Long id;
    private final String roomId;
    private final String sender;
    private final String content;
    private final LocalDateTime sentAt;

    public static Message create(String roomId, String sender, String content) {
        return new Message(null, roomId, sender, content, LocalDateTime.now());
    }
}
