package com.brunosong.system.springai.controller;

import com.brunosong.system.springai.pdf.PdfImageOverlayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 멀티모달 AI(gemma4) 비전을 이용한 PDF 텍스트 오버레이 컨트롤러.
 * OCR 없이 AI가 이미지를 직접 보고 라벨 위치를 찾는다.
 */
@Slf4j
@Controller
@RequestMapping("/pdf-vision")
@RequiredArgsConstructor
public class PdfImageOverlayController {

    private final PdfImageOverlayService imageOverlayService;

    @GetMapping
    public String view() {
        return "pdf-vision";
    }

    /**
     * PDF 이미지를 AI가 직접 분석하여 라벨 위치에 텍스트를 오버레이한다.
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

        log.info("비전 오버레이 요청 - 파일: {}, 매핑: {}", file.getOriginalFilename(), overlayMap);
        byte[] resultPdf = imageOverlayService.overlay(file.getBytes(), overlayMap);

        String filename = "vision_" + file.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"vision_overlay.pdf\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.APPLICATION_PDF)
                .body(resultPdf);
    }

    /**
     * 특정 페이지의 렌더링 이미지를 반환한다 (디버그용).
     */
    @PostMapping("/preview")
    public ResponseEntity<byte[]> previewPage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "page", defaultValue = "0") int page
    ) throws Exception {
        org.apache.pdfbox.pdmodel.PDDocument document =
                org.apache.pdfbox.Loader.loadPDF(file.getBytes());
        org.apache.pdfbox.rendering.PDFRenderer renderer =
                new org.apache.pdfbox.rendering.PDFRenderer(document);

        java.awt.image.BufferedImage image = renderer.renderImageWithDPI(page, 150);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "PNG", baos);
        document.close();

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(baos.toByteArray());
    }
}
