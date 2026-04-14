package com.brunosong.system.agent.adapter.out.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * PDF 파일을 읽어 텍스트, 페이지 수, 글자 총수를 반환하는 도구.
 * LLM이 사용자가 PDF 파일 경로를 언급하면 자동으로 이 도구를 호출한다.
 */
@Slf4j
@Component
public class PdfReaderTool {

    private static final int MAX_TEXT_LENGTH = 10_000;

    @Tool(description = "PDF 파일을 읽어서 텍스트 내용, 페이지 수, 글자 총수를 반환합니다. 사용자가 PDF 파일 경로를 언급하면 이 도구를 호출하세요.")
    public String readPdf(@ToolParam(description = "PDF 파일의 절대 경로") String filePath) {
        log.info("PDF 읽기 요청: {}", filePath);
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                return "오류: 파일을 찾을 수 없습니다 - " + filePath;
            }

            try (PDDocument document = Loader.loadPDF(file)) {
                PDFTextStripper stripper = new PDFTextStripper();
                String fullText = stripper.getText(document);
                int pageCount = document.getNumberOfPages();
                int charCount = fullText.length();

                String text = fullText.length() > MAX_TEXT_LENGTH
                        ? fullText.substring(0, MAX_TEXT_LENGTH) + "\n... (이하 생략, 총 " + charCount + "자)"
                        : fullText;

                return String.format(
                        "=== PDF 분석 결과 ===\n파일: %s\n페이지 수: %d\n글자 총수: %d\n\n=== 내용 ===\n%s",
                        filePath, pageCount, charCount, text
                );
            }
        } catch (Exception e) {
            log.error("PDF 읽기 실패", e);
            return "PDF 읽기 실패: " + e.getMessage();
        }
    }
}
