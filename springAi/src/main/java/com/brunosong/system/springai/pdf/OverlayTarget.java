package com.brunosong.system.springai.pdf;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI가 분석한 오버레이 대상 좌표 정보.
 * Ollama가 JSON으로 반환하는 구조.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class OverlayTarget {
    private String label;       // 라벨 (예: "이름")
    private int page;           // 0-based 페이지 번호
    private float x;            // 값을 쓸 X 좌표
    private float y;            // 값을 쓸 Y 좌표
    private float width;        // 기존 값 영역 너비 (덮어쓸 영역)
    private float height;       // 기존 값 영역 높이
    private float fontSize;     // 사용할 폰트 크기
    private boolean hasExistingValue; // 기존 값이 있는지 여부 (흰색 박스 처리 필요 여부)
}
