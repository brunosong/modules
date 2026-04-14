package com.brunosong.system.springai.pdf;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * AI 비전이 반환하는 픽셀 좌표 기반 타겟.
 * 이미지 좌상단 원점, X 오른쪽, Y 아래쪽.
 */
@Getter
@Setter
@NoArgsConstructor
@ToString
public class VisionTarget {
    private String label;
    private int pixelX;       // 값 영역 시작 X (픽셀, 좌상단 원점)
    private int pixelY;       // 값 영역 시작 Y (픽셀, 좌상단 원점)
    private int pixelWidth;   // 값 영역 너비 (픽셀)
    private int pixelHeight;  // 값 영역 높이 (픽셀)
    private boolean hasExistingValue;
}
