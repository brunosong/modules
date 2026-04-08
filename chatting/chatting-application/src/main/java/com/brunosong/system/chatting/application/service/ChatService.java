package com.brunosong.system.chatting.application.service;

import com.brunosong.system.chatting.application.port.in.SendMessageUseCase;
import com.brunosong.system.chatting.application.port.out.SaveMessagePort;
import com.brunosong.system.chatting.domain.Message;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ChatService implements SendMessageUseCase {

    private final SaveMessagePort saveMessagePort;

    @Override
    public Message send(String roomId, String sender, String content) {
        return saveMessagePort.save(Message.create(roomId, sender, content));
    }
}
