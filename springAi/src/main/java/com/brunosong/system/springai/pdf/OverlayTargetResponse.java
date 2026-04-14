package com.brunosong.system.springai.pdf;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Ollama가 반환하는 오버레이 대상 목록 래퍼.
 * Spring AI의 entity() 변환용.
 */
@Getter
@Setter
@NoArgsConstructor
public class OverlayTargetResponse {
    private List<OverlayTarget> targets;
}
