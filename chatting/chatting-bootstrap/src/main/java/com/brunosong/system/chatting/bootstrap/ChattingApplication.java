package com.brunosong.system.chatting.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ComponentScan(basePackages = "com.brunosong.system.chatting")
@EntityScan(basePackages = "com.brunosong.system.chatting.adapter.out.persistence")
@EnableJpaRepositories(basePackages = "com.brunosong.system.chatting.adapter.out.persistence")
public class ChattingApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChattingApplication.class, args);
    }
}
