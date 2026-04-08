package com.brunosong.system.chatting.adapter.out.persistence;

import com.brunosong.system.chatting.application.port.out.SaveMessagePort;
import com.brunosong.system.chatting.domain.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessagePersistenceAdapter implements SaveMessagePort {

    private final MessageJpaRepository repository;

    @Override
    public Message save(Message message) {
        MessageJpaEntity saved = repository.save(
                new MessageJpaEntity(message.getRoomId(), message.getSender(), message.getContent(), message.getSentAt())
        );
        return new Message(saved.getId(), saved.getRoomId(), saved.getSender(), saved.getContent(), saved.getSentAt());
    }
}
