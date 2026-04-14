package com.brunosong.system.springai.controller;

import com.brunosong.system.springai.pdf.PdfOverlayService;
import com.brunosong.system.springai.pdf.TextWithPosition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 좌표 추출 및 텍스트 오버레이 컨트롤러.
 *
 * 1) /pdf-overlay           → 테스트 UI 페이지
 * 2) /pdf-overlay/positions → PDF 업로드 후 텍스트+좌표 목록 반환 (디버깅용)
 * 3) /pdf-overlay/apply     → PDF 업로드 + 라벨-값 매핑 → 수정된 PDF 다운로드
 */
@Slf4j
@Controller
@RequestMapping("/pdf-overlay")
@RequiredArgsConstructor
public class PdfOverlayController {

    private final PdfOverlayService overlayService;

    @GetMapping
    public String view() {
        return "pdf-overlay";
    }

    /**
     * PDF에서 모든 텍스트의 좌표를 추출하여 반환한다.
     */
    @PostMapping("/positions")
    @ResponseBody
    public Map<String, Object> extractPositions(@RequestParam("file") MultipartFile file) {
        try {
            List<TextWithPosition> positions = overlayService.extractPositions(file.getBytes());
            log.info("좌표 추출 완료 - 파일: {}, 항목 수: {}", file.getOriginalFilename(), positions.size());
            return Map.of("success", true, "positions", positions);
        } catch (Exception e) {
            log.error("좌표 추출 실패", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * PDF에서 라벨을 찾아 텍스트를 덮어쓴 새 PDF를 반환한다.
     *
     * @param file    원본 PDF
     * @param labels  라벨 목록 (예: ["이름", "생년월일"])
     * @param values  각 라벨에 덮어쓸 값 (예: ["홍길동", "1990-01-01"])
     */
    @PostMapping("/apply")
    public ResponseEntity<byte[]> applyOverlay(
            @RequestParam("file") MultipartFile file,
            @RequestParam("labels") List<String> labels,
            @RequestParam("values") List<String> values
    ) throws Exception {
        Map<String, String> overlayMap = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            if (i < values.size() && !labels.get(i).isBlank() && !values.get(i).isBlank()) {
                overlayMap.put(labels.get(i).strip(), values.get(i).strip());
            }
        }

        log.info("오버레이 요청 - 파일: {}, 매핑: {}", file.getOriginalFilename(), overlayMap);
        byte[] resultPdf = overlayService.overlay(file.getBytes(), overlayMap);

        String filename = "overlay_" + file.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"overlay.pdf\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(resultPdf);
    }

    /**
     * PDF 위에 추출된 좌표마다 "(X, Y)" 텍스트를 찍어서 반환한다.
     * 실제 좌표가 PDF의 어디에 해당하는지 눈으로 확인하는 디버그용.
     */
    @PostMapping("/debug")
    public ResponseEntity<byte[]> debugPositions(@RequestParam("file") MultipartFile file) throws Exception {
        List<TextWithPosition> positions = overlayService.extractPositions(file.getBytes());

        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDType0Font font = loadFont(document);

            for (TextWithPosition pos : positions) {
                PDPage page = document.getPage(pos.getPage());

                try (PDPageContentStream cs = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    // 회전 역변환 적용 → 이후 visual 좌표를 그대로 사용 가능
                    applyRotationTransform(cs, page);

                    float fontSize = 6f;
                    String coordText = String.format("(%.0f,%.0f)p%d",
                            pos.getX(), pos.getY(), pos.getPage());

                    // 좌표 텍스트 배경
                    float textWidth = font.getStringWidth(coordText) / 1000 * fontSize;
                    cs.setNonStrokingColor(1f, 1f, 1f);
                    cs.addRect(pos.getX(), pos.getY() - 1, textWidth + 2, fontSize + 2);
                    cs.fill();

                    // 빨간색 좌표 텍스트
                    cs.beginText();
                    cs.setFont(font, fontSize);
                    cs.setNonStrokingColor(1f, 0f, 0f);
                    cs.newLineAtOffset(pos.getX(), pos.getY());
                    cs.showText(coordText);
                    cs.endText();

                    // 작은 십자 마커
                    cs.setStrokingColor(1f, 0f, 0f);
                    cs.setLineWidth(0.5f);
                    cs.moveTo(pos.getX() - 3, pos.getY());
                    cs.lineTo(pos.getX() + 3, pos.getY());
                    cs.stroke();
                    cs.moveTo(pos.getX(), pos.getY() - 3);
                    cs.lineTo(pos.getX(), pos.getY() + 3);
                    cs.stroke();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"debug_coordinates.pdf\"")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(baos.toByteArray());
        }
    }

    private void applyRotationTransform(PDPageContentStream cs, PDPage page) throws java.io.IOException {
        int rotation = page.getRotation();
        if (rotation == 0) return;

        PDRectangle mediaBox = page.getMediaBox();
        float w = mediaBox.getWidth();
        float h = mediaBox.getHeight();

        org.apache.pdfbox.util.Matrix matrix = switch (rotation) {
            case 90  -> new org.apache.pdfbox.util.Matrix(0, 1, -1, 0, w, 0);
            case 180 -> new org.apache.pdfbox.util.Matrix(-1, 0, 0, -1, w, h);
            case 270 -> new org.apache.pdfbox.util.Matrix(0, -1, 1, 0, 0, h);
            default  -> null;
        };

        if (matrix != null) {
            cs.transform(matrix);
        }
    }

    private PDType0Font loadFont(PDDocument document) throws Exception {
        try {
            org.springframework.core.io.ClassPathResource fontResource =
                    new org.springframework.core.io.ClassPathResource("fonts/malgun.ttf");
            try (InputStream is = fontResource.getInputStream()) {
                return PDType0Font.load(document, is);
            }
        } catch (Exception e) {
            java.io.File systemFont = new java.io.File("C:/Windows/Fonts/malgun.ttf");
            try (InputStream is = new java.io.FileInputStream(systemFont)) {
                return PDType0Font.load(document, is);
            }
        }
    }
}
