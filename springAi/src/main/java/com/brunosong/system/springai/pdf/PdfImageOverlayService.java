package com.brunosong.system.springai.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF 페이지를 이미지로 렌더링하여 Ollama(gemma4)에 직접 보내서
 * 라벨의 위치를 찾고 텍스트를 오버레이하는 서비스.
 *
 * AI는 픽셀 좌표만 반환하고, PDF 좌표 변환은 이 서비스에서 처리한다.
 */
@Slf4j
@Service
public class PdfImageOverlayService {

    private static final int RENDER_DPI = 150;
    private static final float DEFAULT_FONT_SIZE = 10f;

    private final ChatClient chatClient;

    public PdfImageOverlayService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * PDF에서 라벨 위치를 AI 비전으로 찾아 텍스트를 오버레이한 새 PDF를 반환한다.
     */
    public byte[] overlay(byte[] pdfBytes, Map<String, String> overlayMap) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDType0Font font = loadFont(document);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageIdx = 0; pageIdx < document.getNumberOfPages(); pageIdx++) {
                PDPage page = document.getPage(pageIdx);
                float effectiveWidth = getEffectiveWidth(page);
                float effectiveHeight = getEffectiveHeight(page);

                // 페이지를 이미지로 렌더링 (rotation 자동 적용)
                BufferedImage image = renderer.renderImageWithDPI(pageIdx, RENDER_DPI);
                byte[] imageBytes = toBytes(image);
                int imgW = image.getWidth();
                int imgH = image.getHeight();

                log.info("페이지 {} 렌더링 - 이미지: {}x{}px, PDF유효: {}x{}pt, rotation: {}°",
                        pageIdx + 1, imgW, imgH, effectiveWidth, effectiveHeight, page.getRotation());

                // AI에게 이미지를 보내서 픽셀 좌표를 받는다
                List<VisionTarget> visionTargets = findTargetsByVision(
                        imageBytes, imgW, imgH, pageIdx,
                        overlayMap.keySet().stream().toList()
                );

                // 픽셀 좌표 → PDF visual 좌표로 변환 후 오버레이
                for (VisionTarget vt : visionTargets) {
                    String newText = overlayMap.get(vt.getLabel());
                    if (newText == null) continue;

                    // 픽셀 → PDF visual 좌표 변환
                    float pdfX = (float) vt.getPixelX() / imgW * effectiveWidth;
                    float pdfY = effectiveHeight - ((float) vt.getPixelY() / imgH * effectiveHeight);
                    float pdfW = (float) vt.getPixelWidth() / imgW * effectiveWidth;
                    float pdfH = (float) vt.getPixelHeight() / imgH * effectiveHeight;

                    log.info("좌표 변환 - 라벨: '{}', pixel:({},{}) → pdf:({},{}), page:{}",
                            vt.getLabel(), vt.getPixelX(), vt.getPixelY(), pdfX, pdfY, pageIdx);

                    // 기존 값 덮기
                    if (vt.isHasExistingValue()) {
                        try (PDPageContentStream cs = new PDPageContentStream(
                                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            applyRotationTransform(cs, page);
                            cs.setNonStrokingColor(1f, 1f, 1f);
                            cs.addRect(pdfX - 1, pdfY - 2, pdfW + 4, pdfH + 4);
                            cs.fill();
                        }
                    }

                    // 새 텍스트 쓰기
                    try (PDPageContentStream cs = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        applyRotationTransform(cs, page);
                        cs.beginText();
                        cs.setFont(font, DEFAULT_FONT_SIZE);
                        cs.setNonStrokingColor(0f, 0f, 0f);
                        cs.newLineAtOffset(pdfX, pdfY);
                        cs.showText(newText);
                        cs.endText();
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * AI에게 이미지를 보내서 라벨의 픽셀 좌표를 받는다.
     * 좌표 변환은 AI가 하지 않고 서비스에서 처리한다.
     */
    private List<VisionTarget> findTargetsByVision(
            byte[] imageBytes, int imgWidth, int imgHeight,
            int pageIdx, List<String> labels
    ) {
        String labelsText = String.join(", ", labels);

        String prompt = String.format("""
                이 이미지는 PDF 문서의 한 페이지입니다.
                이미지 크기: %d x %d 픽셀 (좌상단이 원점, X는 오른쪽, Y는 아래쪽)

                찾아야 할 라벨: [%s]

                각 라벨에 대해:
                1. 이미지에서 해당 라벨 텍스트의 위치를 찾으세요.
                2. 라벨 오른쪽 또는 아래에 값이 들어갈 영역(빈칸 또는 기존 값)을 찾으세요.
                3. 그 값 영역의 **픽셀 좌표**를 반환하세요.
                   - pixelX: 값 영역의 왼쪽 X 픽셀
                   - pixelY: 값 영역의 위쪽 Y 픽셀
                   - pixelWidth: 값 영역의 너비 (픽셀)
                   - pixelHeight: 값 영역의 높이 (픽셀)
                4. 기존 값이 적혀있으면 hasExistingValue=true, 빈칸이면 false

                반드시 아래 JSON 형식으로만 응답하세요. 다른 텍스트는 절대 포함하지 마세요.
                {"targets":[{"label":"라벨명","pixelX":0,"pixelY":0,"pixelWidth":100,"pixelHeight":20,"hasExistingValue":false}]}

                찾을 수 없는 라벨은 targets에 포함하지 마세요.
                """, imgWidth, imgHeight, labelsText);

        log.info("AI 비전 분석 요청 - 페이지: {}, 이미지: {}x{}px, 라벨: {}",
                pageIdx, imgWidth, imgHeight, labelsText);

        VisionTargetResponse response = chatClient.prompt()
                .user(u -> u
                        .text(prompt)
                        .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(imageBytes))
                )
                .call()
                .entity(VisionTargetResponse.class);

        if (response == null || response.getTargets() == null) {
            log.warn("AI 비전 응답이 비어있습니다. 페이지: {}", pageIdx);
            return List.of();
        }

        for (VisionTarget vt : response.getTargets()) {
            log.info("AI 반환 - 라벨: '{}', pixel:({}, {}), size:{}x{}, 기존값:{}",
                    vt.getLabel(), vt.getPixelX(), vt.getPixelY(),
                    vt.getPixelWidth(), vt.getPixelHeight(), vt.isHasExistingValue());
        }

        return response.getTargets();
    }

    // ──────────────────────────────────────────────
    // 유틸
    // ──────────────────────────────────────────────

    private byte[] toBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        return baos.toByteArray();
    }

    private float getEffectiveWidth(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        int rotation = page.getRotation();
        return (rotation == 90 || rotation == 270) ? mediaBox.getHeight() : mediaBox.getWidth();
    }

    private float getEffectiveHeight(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        int rotation = page.getRotation();
        return (rotation == 90 || rotation == 270) ? mediaBox.getWidth() : mediaBox.getHeight();
    }

    private void applyRotationTransform(PDPageContentStream cs, PDPage page) throws java.io.IOException {
        int rotation = page.getRotation();
        if (rotation == 0) return;

        PDRectangle mediaBox = page.getMediaBox();
        float w = mediaBox.getWidth();
        float h = mediaBox.getHeight();

        Matrix matrix = switch (rotation) {
            case 90  -> new Matrix(0, 1, -1, 0, w, 0);
            case 180 -> new Matrix(-1, 0, 0, -1, w, h);
            case 270 -> new Matrix(0, -1, 1, 0, 0, h);
            default  -> null;
        };

        if (matrix != null) {
            cs.transform(matrix);
        }
    }

    private PDType0Font loadFont(PDDocument document) throws Exception {
        try {
            ClassPathResource fontResource = new ClassPathResource("fonts/malgun.ttf");
            try (InputStream is = fontResource.getInputStream()) {
                return PDType0Font.load(document, is);
            }
        } catch (Exception e) {
            log.warn("classpath 폰트 로드 실패: {}", e.getMessage());
        }

        java.io.File systemFont = new java.io.File("C:/Windows/Fonts/malgun.ttf");
        if (systemFont.exists()) {
            try (InputStream is = new java.io.FileInputStream(systemFont)) {
                return PDType0Font.load(document, is);
            }
        }

        throw new IllegalStateException("한글 폰트(malgun.ttf)를 찾을 수 없습니다.");
    }
}
