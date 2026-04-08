package com.brunosong.system.chatting.application.port.in;

import com.brunosong.system.chatting.domain.Message;

public interface SendMessageUseCase {
    Message send(String roomId, String sender, String content);
}
