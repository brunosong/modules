package com.brunosong.system.springai.pdf;

import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDFTextStripper를 확장하여 텍스트와 좌표를 함께 추출한다.
 * writeString()이 호출될 때마다 한 줄(word group)의 위치 정보를 수집한다.
 */
public class LocationTextStripper extends PDFTextStripper {

    private final List<TextWithPosition> positions = new ArrayList<>();
    private float pageWidth;     // 유효 (회전 후)
    private float pageHeight;    // 유효 (회전 후)
    private int rotation;

    public void setPageDimensions(float pageWidth, float pageHeight, int rotation) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.rotation = rotation;
    }

    public List<TextWithPosition> getTextPositions() {
        return positions;
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
        if (textPositions.isEmpty()) {
            super.writeString(text, textPositions);
            return;
        }

        TextPosition first = textPositions.get(0);
        TextPosition last = textPositions.get(textPositions.size() - 1);

        // getXDirAdj(), getYDirAdj()는 회전을 보정한 시각적 좌표
        float x = first.getXDirAdj();
        float yFromTop = first.getYDirAdj();
        float y = pageHeight - yFromTop; // 시각적 Y (좌하단 원점)

        float width = (last.getXDirAdj() + last.getWidthDirAdj()) - x;
        float fontSize = first.getFontSizeInPt();
        float height = fontSize;

        int page = getCurrentPageNo() - 1; // 0-based

        positions.add(new TextWithPosition(
                page, x, y, width, height, fontSize, text.strip(),
                pageWidth, pageHeight, rotation
        ));

        super.writeString(text, textPositions);
    }
}
