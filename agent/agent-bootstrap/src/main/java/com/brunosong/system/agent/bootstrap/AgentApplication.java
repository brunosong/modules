package com.brunosong.system.agent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.brunosong.system.agent")
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
