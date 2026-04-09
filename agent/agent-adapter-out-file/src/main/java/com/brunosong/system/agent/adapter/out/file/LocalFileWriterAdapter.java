package com.brunosong.system.agent.adapter.out.file;

import com.brunosong.system.agent.application.port.out.FileWriterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 지정된 work-dir 아래로만 파일을 만든다.
 *  - 절대경로 입력은 거절
 *  - ".." 같은 경로 탈출 시도 차단
 */
@Component
public class LocalFileWriterAdapter implements FileWriterPort {

    private final Path workDir;

    public LocalFileWriterAdapter(@Value("${agent.work-dir:./agent-workspace}") String workDir) {
        this.workDir = Path.of(workDir).toAbsolutePath().normalize();
    }

    @Override
    public String write(String path, String content) {
        try {
            Path target = workDir.resolve(path).normalize();
            if (!target.startsWith(workDir)) {
                throw new IllegalArgumentException("Path escapes workspace: " + path);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, content == null ? "" : content);
            return target.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to write file: " + path, e);
        }
    }
}
