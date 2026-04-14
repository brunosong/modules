package com.brunosong.system.springai.pdf;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * PDF 구조 + OCR 좌표 분석 디버그용 도구. main 메서드로 직접 실행.
 */
public class PdfDebugAnalyzer {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0]
                : "C:\\Users\\airtr\\Downloads\\삼성화재_사용인등록서류 공양식.pdf";

        File file = new File(path);
        System.out.println("=== PDF 구조 분석 ===");
        System.out.println("파일: " + file.getName());
        System.out.println("크기: " + file.length() + " bytes\n");

        try (PDDocument document = Loader.loadPDF(file)) {
            int numPages = document.getNumberOfPages();
            System.out.println("총 페이지: " + numPages + "\n");

            // 1. 페이지 구조 정보
            for (int i = 0; i < numPages; i++) {
                PDPage page = document.getPage(i);
                PDRectangle mediaBox = page.getMediaBox();
                int rotation = page.getRotation();
                float effectiveW = (rotation == 90 || rotation == 270) ? mediaBox.getHeight() : mediaBox.getWidth();
                float effectiveH = (rotation == 90 || rotation == 270) ? mediaBox.getWidth() : mediaBox.getHeight();

                System.out.println("--- 페이지 " + (i + 1) + " (index " + i + ") ---");
                System.out.printf("  mediaBox: %.1f x %.1f%n", mediaBox.getWidth(), mediaBox.getHeight());
                System.out.println("  rotation: " + rotation + "°");
                System.out.printf("  유효 크기: %.1f x %.1f (%s)%n",
                        effectiveW, effectiveH,
                        effectiveW > effectiveH ? "가로" : "세로");
                System.out.println();
            }

            // 2. OCR로 텍스트+좌표 추출
            System.out.println("=== OCR 텍스트+좌표 추출 ===\n");

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath("C:/Program Files/Tesseract-OCR/tessdata");
            tesseract.setLanguage("kor+eng");

            PDFRenderer renderer = new PDFRenderer(document);
            float scale = 72f / 300f;

            for (int i = 0; i < numPages; i++) {
                PDPage page = document.getPage(i);
                PDRectangle mediaBox = page.getMediaBox();
                int rotation = page.getRotation();
                float effectiveW = (rotation == 90 || rotation == 270) ? mediaBox.getHeight() : mediaBox.getWidth();
                float effectiveH = (rotation == 90 || rotation == 270) ? mediaBox.getWidth() : mediaBox.getHeight();

                System.out.println("--- 페이지 " + (i + 1) + " (rotation=" + rotation + "°) ---");

                BufferedImage image = renderer.renderImageWithDPI(i, 300);
                System.out.printf("  렌더링 이미지 크기: %d x %d px%n", image.getWidth(), image.getHeight());

                List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
                System.out.println("  추출된 단어 수: " + words.size());
                System.out.println();

                System.out.println("  page | vis_X  | vis_Y  | width  | height | text");
                System.out.println("  -----|--------|--------|--------|--------|-----");

                int count = 0;
                for (Word word : words) {
                    String text = word.getText().strip();
                    if (text.isEmpty()) continue;

                    Rectangle bbox = word.getBoundingBox();
                    float vx = bbox.x * scale;
                    float vy = effectiveH - ((bbox.y + bbox.height) * scale);
                    float w = bbox.width * scale;
                    float h = bbox.height * scale;

                    System.out.printf("  %d    | %6.1f | %6.1f | %6.1f | %6.1f | %s%n",
                            i, vx, vy, w, h, text);

                    count++;
                    if (count >= 30) {
                        System.out.println("  ... (이하 생략, 총 " + words.size() + "개)");
                        break;
                    }
                }
                System.out.println();
            }
        }
    }
}
