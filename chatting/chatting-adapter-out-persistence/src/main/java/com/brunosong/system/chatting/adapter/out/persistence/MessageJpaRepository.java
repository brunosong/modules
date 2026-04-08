package com.brunosong.system.chatting.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageJpaRepository extends JpaRepository<MessageJpaEntity, Long> {
}
