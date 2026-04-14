package com.brunosong.system.springai.controller;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.util.Map;

/**
 * PDF 파일을 업로드하면 텍스트를 추출하고 AI에게 분석을 요청하는 컨트롤러.
 * agent 모듈과 달리 Tool Calling 없이 직접 PDF → 텍스트 → AI 프롬프트 방식.
 */
@Slf4j
@Controller
@RequestMapping("/pdf")
public class PdfChatController {

    private static final int MAX_TEXT_LENGTH = 10_000;

    private final ChatClient chatClient;

    @Value("${tesseract.data-path:#{null}}")
    private String tessDataPath;

    @Value("${tesseract.language:kor+eng}")
    private String tessLanguage;

    public PdfChatController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @GetMapping
    public String view() {
        return "pdf-chat";
    }

    @PostMapping("/analyze")
    @ResponseBody
    public Map<String, Object> analyze(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "question", defaultValue = "이 PDF 문서의 내용을 요약해주세요.") String question
    ) {
        try {
            String pdfText = extractText(file);
            int pageCount = countPages(file);

            String prompt = String.format(
                    "다음은 PDF 문서의 내용입니다. (총 %d페이지, %d자)\n\n---\n%s\n---\n\n질문: %s",
                    pageCount, pdfText.length(), pdfText, question
            );

            log.info("PDF 분석 요청 - 파일: {}, 페이지: {}, 질문: {}", file.getOriginalFilename(), pageCount, question);

            String reply = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return Map.of(
                    "success", true,
                    "fileName", file.getOriginalFilename(),
                    "pageCount", pageCount,
                    "charCount", pdfText.length(),
                    "reply", reply
            );
        } catch (Exception e) {
            log.error("PDF 분석 실패", e);
            return Map.of(
                    "success", false,
                    "reply", "PDF 분석 실패: " + e.getMessage()
            );
        }
    }

    private String extractText(MultipartFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document).strip();

            if (fullText.isEmpty()) {
                log.info("텍스트 추출 실패 - OCR로 전환합니다. 파일: {}", file.getOriginalFilename());
                fullText = extractTextByOcr(document);
            }

            if (fullText.length() > MAX_TEXT_LENGTH) {
                return fullText.substring(0, MAX_TEXT_LENGTH)
                        + "\n... (이하 생략, 총 " + fullText.length() + "자)";
            }
            return fullText;
        }
    }

    private String extractTextByOcr(PDDocument document) throws TesseractException {
        Tesseract tesseract = new Tesseract();
        if (tessDataPath != null) {
            tesseract.setDatapath(tessDataPath);
        }
        tesseract.setLanguage(tessLanguage);

        PDFRenderer renderer = new PDFRenderer(document);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < document.getNumberOfPages(); i++) {
            try {
                BufferedImage image = renderer.renderImageWithDPI(i, 300);
                String pageText = tesseract.doOCR(image);
                sb.append(pageText).append("\n");
            } catch (Exception e) {
                log.warn("{}페이지 OCR 실패: {}", i + 1, e.getMessage());
            }
        }

        return sb.toString().strip();
    }

    private int countPages(MultipartFile file) throws Exception {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return document.getNumberOfPages();
        }
    }
}
