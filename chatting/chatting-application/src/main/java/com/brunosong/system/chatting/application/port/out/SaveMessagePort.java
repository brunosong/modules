package com.brunosong.system.chatting.application.port.out;

import com.brunosong.system.chatting.domain.Message;

public interface SaveMessagePort {
    Message save(Message message);
}
