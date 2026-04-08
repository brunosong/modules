package com.brunosong.system.chatting.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_id", nullable = false, length = 64)
    private String roomId;

    private String sender;

    @Column(length = 2000)
    private String content;

    private LocalDateTime sentAt;

    public MessageJpaEntity(String roomId, String sender, String content, LocalDateTime sentAt) {
        this.roomId = roomId;
        this.sender = sender;
        this.content = content;
        this.sentAt = sentAt;
    }
}
