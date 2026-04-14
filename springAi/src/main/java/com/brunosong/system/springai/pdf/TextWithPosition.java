package com.brunosong.system.springai.pdf;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * PDF에서 추출한 텍스트 한 줄과 해당 위치 정보를 담는 DTO.
 * x, y는 시각적(visual) 좌표 - 사용자가 PDF를 볼 때 보이는 그대로의 좌표.
 */
@Getter
@AllArgsConstructor
@ToString
public class TextWithPosition {
    private final int page;         // 0-based 페이지 번호
    private final float x;          // 시각적 X 좌표 (왼쪽→오른쪽)
    private final float y;          // 시각적 Y 좌표 (좌하단 원점, 아래→위)
    private final float width;      // 텍스트 전체 너비
    private final float height;     // 텍스트 높이 (폰트 크기 기반)
    private final float fontSize;
    private final String text;
    private final float pageWidth;  // 유효(회전 후) 페이지 너비
    private final float pageHeight; // 유효(회전 후) 페이지 높이
    private final int rotation;     // 페이지 회전 (0, 90, 180, 270)
}
