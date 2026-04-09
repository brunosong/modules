package com.brunosong.system.agent.application.port.out;

/** 실제 파일 시스템에 파일을 만드는 능력. */
public interface FileWriterPort {
    /** @return 실제 저장된 절대 경로 */
    String write(String path, String content);
}
