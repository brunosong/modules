package com.brunosong.system.springai.pdf;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF에서 텍스트+좌표를 추출한 뒤 Ollama(AI)에게 보내서
 * 라벨에 해당하는 값 위치를 찾고, 그 좌표에 새 텍스트를 덮어쓰는 서비스.
 */
@Slf4j
@Service
public class PdfOverlayService {

    private static final int OCR_DPI = 300;
    private static final float PDF_POINTS_PER_INCH = 72f;

    private final ChatClient chatClient;

    @Value("${tesseract.data-path:#{null}}")
    private String tessDataPath;

    @Value("${tesseract.language:kor+eng}")
    private String tessLanguage;

    public PdfOverlayService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    // ──────────────────────────────────────────────
    // 페이지 크기 유틸 (회전 고려)
    // ──────────────────────────────────────────────

    /**
     * 회전을 고려한 유효 페이지 너비를 반환한다.
     * rotation 90/270이면 mediaBox의 height가 실제 너비가 된다.
     */
    private float getEffectiveWidth(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        int rotation = page.getRotation();
        return (rotation == 90 || rotation == 270) ? mediaBox.getHeight() : mediaBox.getWidth();
    }

    /**
     * 회전을 고려한 유효 페이지 높이를 반환한다.
     * rotation 90/270이면 mediaBox의 width가 실제 높이가 된다.
     */
    private float getEffectiveHeight(PDPage page) {
        PDRectangle mediaBox = page.getMediaBox();
        int rotation = page.getRotation();
        return (rotation == 90 || rotation == 270) ? mediaBox.getWidth() : mediaBox.getHeight();
    }

    // ──────────────────────────────────────────────
    // 텍스트+좌표 추출
    // ──────────────────────────────────────────────

    public List<TextWithPosition> extractPositions(byte[] pdfBytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            List<TextWithPosition> positions = extractByTextStripper(document);

            if (!positions.isEmpty()) {
                log.info("텍스트 기반 추출 성공 - {}개 항목", positions.size());
                return mergeNearbyText(positions);
            }

            log.info("텍스트 추출 결과 없음 - OCR로 전환합니다.");
            return mergeNearbyText(extractByOcr(document));
        }
    }

    private List<TextWithPosition> extractByTextStripper(PDDocument document) throws Exception {
        LocationTextStripper stripper = new LocationTextStripper();

        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            float effectiveWidth = getEffectiveWidth(page);
            float effectiveHeight = getEffectiveHeight(page);
            int rotation = page.getRotation();

            stripper.setPageDimensions(effectiveWidth, effectiveHeight, rotation);
            stripper.setStartPage(i + 1);
            stripper.setEndPage(i + 1);
            stripper.getText(document);

            log.info("페이지 {} 추출 - 크기: {}x{}, 회전: {}°, 방향: {}",
                    i + 1, effectiveWidth, effectiveHeight, rotation,
                    effectiveWidth > effectiveHeight ? "가로" : "세로");
        }

        return stripper.getTextPositions();
    }

    private List<TextWithPosition> extractByOcr(PDDocument document) throws Exception {
        Tesseract tesseract = createTesseract();
        PDFRenderer renderer = new PDFRenderer(document);
        List<TextWithPosition> positions = new ArrayList<>();
        float scale = PDF_POINTS_PER_INCH / OCR_DPI;

        for (int i = 0; i < document.getNumberOfPages(); i++) {
            PDPage page = document.getPage(i);
            float effectiveWidth = getEffectiveWidth(page);
            float effectiveHeight = getEffectiveHeight(page);
            int rotation = page.getRotation();

            // renderImageWithDPI는 회전을 자동으로 적용하여 렌더링한다
            BufferedImage image = renderer.renderImageWithDPI(i, OCR_DPI);
            List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);

            for (Word word : words) {
                String text = word.getText().strip();
                if (text.isEmpty()) continue;

                Rectangle bbox = word.getBoundingBox();
                float x = bbox.x * scale;
                float width = bbox.width * scale;
                float height = bbox.height * scale;
                // OCR Y는 상단 기준 → 시각적 좌표계(좌하단 원점)로 변환
                float y = effectiveHeight - ((bbox.y + bbox.height) * scale);
                float fontSize = height;

                positions.add(new TextWithPosition(
                        i, x, y, width, height, fontSize, text,
                        effectiveWidth, effectiveHeight, rotation
                ));
            }

            log.info("OCR 페이지 {} 완료 - {}개 단어, 크기: {}x{}, 회전: {}°",
                    i + 1, words.size(), effectiveWidth, effectiveHeight, rotation);
        }

        return positions;
    }

    private Tesseract createTesseract() {
        Tesseract tesseract = new Tesseract();
        if (tessDataPath != null) {
            tesseract.setDatapath(tessDataPath);
        }
        tesseract.setLanguage(tessLanguage);
        return tesseract;
    }

    // ──────────────────────────────────────────────
    // 텍스트 병합
    // ──────────────────────────────────────────────

    private List<TextWithPosition> mergeNearbyText(List<TextWithPosition> positions) {
        if (positions.isEmpty()) return positions;

        List<TextWithPosition> sorted = new ArrayList<>(positions);
        sorted.sort((a, b) -> {
            if (a.getPage() != b.getPage()) return Integer.compare(a.getPage(), b.getPage());
            int yGroupA = Math.round(a.getY() / 5f);
            int yGroupB = Math.round(b.getY() / 5f);
            if (yGroupA != yGroupB) return Integer.compare(yGroupB, yGroupA);
            return Float.compare(a.getX(), b.getX());
        });

        List<TextWithPosition> merged = new ArrayList<>();
        TextWithPosition current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            TextWithPosition next = sorted.get(i);

            boolean samePage = current.getPage() == next.getPage();
            boolean sameLine = Math.abs(current.getY() - next.getY()) <= 5;
            float gap = next.getX() - (current.getX() + current.getWidth());
            boolean closeEnough = gap < current.getFontSize() * 1.5f;

            if (samePage && sameLine && closeEnough) {
                float mergedWidth = (next.getX() + next.getWidth()) - current.getX();
                float mergedHeight = Math.max(current.getHeight(), next.getHeight());
                float mergedFontSize = Math.max(current.getFontSize(), next.getFontSize());
                current = new TextWithPosition(
                        current.getPage(),
                        current.getX(),
                        current.getY(),
                        mergedWidth,
                        mergedHeight,
                        mergedFontSize,
                        current.getText() + next.getText(),
                        current.getPageWidth(),
                        current.getPageHeight(),
                        current.getRotation()
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        log.info("텍스트 병합 완료: {} → {}개", positions.size(), merged.size());
        return merged;
    }

    // ──────────────────────────────────────────────
    // AI 기반 좌표 탐색 + 오버레이
    // ──────────────────────────────────────────────

    public byte[] overlay(byte[] pdfBytes, Map<String, String> overlayMap) throws Exception {
        List<TextWithPosition> allPositions = extractPositions(pdfBytes);

        Set<String> labels = overlayMap.keySet();
        List<OverlayTarget> targets = findTargetsByAi(allPositions, labels);

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDType0Font font = loadFont(document);

            for (OverlayTarget target : targets) {
                String newText = overlayMap.get(target.getLabel());
                if (newText == null) {
                    log.warn("AI가 반환한 라벨에 매핑된 값 없음: {}", target.getLabel());
                    continue;
                }

                log.info("AI 좌표 분석 결과 - 라벨: '{}', page:{}, x:{}, y:{}, fontSize:{}, 기존값:{}",
                        target.getLabel(), target.getPage(), target.getX(), target.getY(),
                        target.getFontSize(), target.isHasExistingValue());

                PDPage page = document.getPage(target.getPage());

                // 기존 값이 있으면 흰색 박스로 덮는다
                if (target.isHasExistingValue()) {
                    try (PDPageContentStream cs = new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        applyRotationTransform(cs, page); // 회전 보정 → visual 좌표 사용 가능
                        cs.setNonStrokingColor(1f, 1f, 1f);
                        cs.addRect(target.getX() - 1, target.getY() - 2,
                                target.getWidth() + 4, target.getHeight() + 4);
                        cs.fill();
                    }
                }

                // 새 텍스트 작성 (visual 좌표 그대로 사용)
                try (PDPageContentStream cs = new PDPageContentStream(
                        document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    applyRotationTransform(cs, page);
                    cs.beginText();
                    cs.setFont(font, target.getFontSize());
                    cs.setNonStrokingColor(0f, 0f, 0f);
                    cs.newLineAtOffset(target.getX(), target.getY());
                    cs.showText(newText);
                    cs.endText();
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    private List<OverlayTarget> findTargetsByAi(List<TextWithPosition> positions, Set<String> labels) {
        String pageInfoText = formatPageInfoForAi(positions);
        String positionsText = formatPositionsForAi(positions);
        String labelsText = String.join(", ", labels);

        String prompt = String.format("""
                당신은 PDF 문서 좌표 분석 전문가입니다.
                아래는 PDF에서 추출한 페이지 정보와 텍스트 좌표 목록입니다.

                [페이지 정보]
                %s

                [추출된 텍스트 좌표 목록]
                페이지(0-based) | X좌표 | Y좌표 | 너비 | 높이 | 폰트크기 | 텍스트
                %s

                [찾아야 할 라벨들]
                %s

                위 좌표 목록에서 각 라벨에 해당하는 **값이 들어갈 위치**를 찾아주세요.

                규칙:
                1. 라벨(예: "성명")은 보통 왼쪽에 있고, 그 오른쪽이나 아래에 값이 들어가는 영역이 있습니다.
                2. **page 번호는 반드시 해당 라벨이 실제로 존재하는 페이지 번호(0-based)를 사용하세요.**
                3. 라벨 오른쪽에 이미 값 텍스트가 있으면 그 텍스트의 좌표를 반환하고 hasExistingValue=true로 설정하세요.
                4. 라벨 오른쪽에 값이 없으면 라벨의 X+너비+5 위치를 반환하고 hasExistingValue=false로 설정하세요.
                5. 같은 줄은 Y좌표가 비슷한(차이 5 이내) 항목입니다.
                6. fontSize는 해당 영역의 폰트 크기를 사용하세요.
                7. 페이지가 가로(landscape)인 경우 좌표 범위가 세로와 다르니 페이지 크기를 참고하세요.

                반드시 아래 JSON 형식으로만 응답하세요. 다른 텍스트는 포함하지 마세요.
                {"targets":[{"label":"라벨명","page":0,"x":0.0,"y":0.0,"width":0.0,"height":0.0,"fontSize":0.0,"hasExistingValue":false}]}
                """, pageInfoText, positionsText, labelsText);

        log.info("AI 좌표 분석 요청 - 라벨: {}, 좌표 항목 수: {}", labelsText, positions.size());

        OverlayTargetResponse response = chatClient.prompt()
                .user(prompt)
                .call()
                .entity(OverlayTargetResponse.class);

        if (response == null || response.getTargets() == null) {
            log.warn("AI 응답이 비어있습니다.");
            return List.of();
        }

        log.info("AI 좌표 분석 완료 - {}개 타겟 반환", response.getTargets().size());
        return response.getTargets();
    }

    /**
     * 페이지별 크기/방향 정보를 AI에게 전달할 텍스트로 포맷팅한다.
     */
    private String formatPageInfoForAi(List<TextWithPosition> positions) {
        // 페이지별로 중복 없이 정보 수집
        Map<Integer, TextWithPosition> pageMap = new LinkedHashMap<>();
        for (TextWithPosition pos : positions) {
            pageMap.putIfAbsent(pos.getPage(), pos);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, TextWithPosition> entry : pageMap.entrySet()) {
            TextWithPosition pos = entry.getValue();
            String orientation = pos.getPageWidth() > pos.getPageHeight() ? "가로(landscape)" : "세로(portrait)";
            sb.append(String.format("페이지 %d: 너비=%.1f, 높이=%.1f, 회전=%d°, 방향=%s%n",
                    entry.getKey(), pos.getPageWidth(), pos.getPageHeight(),
                    pos.getRotation(), orientation));
        }
        return sb.toString();
    }

    private String formatPositionsForAi(List<TextWithPosition> positions) {
        StringBuilder sb = new StringBuilder();
        for (TextWithPosition pos : positions) {
            sb.append(String.format("%d | %.1f | %.1f | %.1f | %.1f | %.1f | %s%n",
                    pos.getPage(), pos.getX(), pos.getY(),
                    pos.getWidth(), pos.getHeight(), pos.getFontSize(),
                    pos.getText()));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────
    // 페이지 회전 역변환 (visual 좌표를 그대로 쓸 수 있게)
    // ──────────────────────────────────────────────

    /**
     * ContentStream에 페이지 회전의 역변환 행렬을 적용한다.
     * 이 변환을 적용하면 이후 모든 그리기 작업에서 visual 좌표를 그대로 사용할 수 있다.
     * 텍스트 방향도 자동으로 보정된다.
     *
     * 원리: 뷰어가 페이지를 R도 회전해서 보여주므로,
     *       content stream에 -R도 역회전을 걸면 상쇄되어 visual 좌표계가 된다.
     */
    private void applyRotationTransform(PDPageContentStream cs, PDPage page) throws java.io.IOException {
        int rotation = page.getRotation();
        if (rotation == 0) return;

        PDRectangle mediaBox = page.getMediaBox();
        float w = mediaBox.getWidth();
        float h = mediaBox.getHeight();

        // visual 좌표 → raw 좌표로 매핑하는 아핀 변환 행렬
        // Matrix(a, b, c, d, tx, ty): x' = ax + cy + tx, y' = bx + dy + ty
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

    // ──────────────────────────────────────────────
    // 폰트 로딩
    // ──────────────────────────────────────────────

    private PDType0Font loadFont(PDDocument document) throws Exception {
        try {
            ClassPathResource fontResource = new ClassPathResource("fonts/malgun.ttf");
            try (InputStream is = fontResource.getInputStream()) {
                log.info("한글 폰트 로드 성공 (classpath): fonts/malgun.ttf");
                return PDType0Font.load(document, is);
            }
        } catch (Exception e) {
            log.warn("classpath 폰트 로드 실패: {}", e.getMessage());
        }

        java.io.File systemFont = new java.io.File("C:/Windows/Fonts/malgun.ttf");
        if (systemFont.exists()) {
            try (InputStream is = new java.io.FileInputStream(systemFont)) {
                log.info("한글 폰트 로드 성공 (system): {}", systemFont.getAbsolutePath());
                return PDType0Font.load(document, is);
            }
        }

        throw new IllegalStateException("한글 폰트(malgun.ttf)를 찾을 수 없습니다.");
    }
}
