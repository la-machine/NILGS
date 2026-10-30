// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.issuance.infrastructure.pdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.snigf.mvp.issuance.domain.DocumentRenderer;
import org.snigf.mvp.issuance.domain.DocumentType;
import org.snigf.mvp.issuance.domain.Verification;
import org.springframework.stereotype.Component;

/**
 * Draws issued documents with PDFBox and their QR code with ZXing (ADR-039), template
 * {@code ack-demo-1}: an A4 page, French and English, a red "DEMONSTRATION — NO LEGAL VALUE"
 * banner, the facts, the number, and a QR code to the public verification page. The number is also
 * written in the PDF's metadata ({@value #NUMBER_KEY}), so a presented file names what it claims to be.
 * Only the standard fonts are used: nothing is embedded or fetched; a character those fonts cannot
 * draw is printed as "?".
 */
@Component
class PdfDocumentRenderer implements DocumentRenderer {

    static final String NUMBER_KEY = "SNIGF-Document-Number";
    static final String PRODUCER = "SNIGF (DEMO)";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneId.of("UTC"));

    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    @Override
    public byte[] render(DocumentType type, String number, Map<String, String> printed, Instant issuedAt, String verifyUrl) {
        if (type != DocumentType.DEMO_SUBMISSION_ACKNOWLEDGMENT) {
            throw new IllegalArgumentException("no template for " + type);
        }
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle("SNIGF DEMO - Accusé de réception / Submission acknowledgment");
            info.setProducer(PRODUCER);
            info.setCreator(PRODUCER);
            info.setCustomMetadataValue(NUMBER_KEY, number);
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            float w = page.getMediaBox().getWidth();
            float top = page.getMediaBox().getHeight();
            try (PDPageContentStream c = new PDPageContentStream(pdf, page)) {
                // The DEMO banner, across the top.
                c.setNonStrokingColor(0.72f, 0.11f, 0.11f);
                c.addRect(0, top - 64, w, 64);
                c.fill();
                c.setNonStrokingColor(1f, 1f, 1f);
                text(c, bold, 15, 40, top - 30, "DÉMONSTRATION — SANS VALEUR LÉGALE");
                text(c, bold, 12, 40, top - 50, "DEMONSTRATION — NO LEGAL VALUE");
                c.setNonStrokingColor(0f, 0f, 0f);
                text(c, regular, 10, 40, top - 92, "SNIGF — Système National Intégré de Gouvernance Foncière");
                text(c, regular, 10, 40, top - 106, "National Integrated Land Governance System");
                text(c, bold, 17, 40, top - 140, "Accusé de réception de demande");
                text(c, regular, 13, 40, top - 158, "Application submission acknowledgment");
                float y = top - 196;
                for (String[] row : new String[][] {
                        {"Référence de la demande / Application reference", printed.get("reference")},
                        {"Type", printed.get("typeFr") + " / " + printed.get("typeEn")},
                        {"Parcelle / Parcel", printed.get("parcel")},
                        {"Bureau / Office", printed.get("office")},
                        {"Demandeur / Applicant", printed.get("holder")},
                        {"Déposée le / Submitted on", when(printed.get("submittedAt"))},
                        {"Émis le / Issued on", WHEN.format(issuedAt)},
                }) {
                    text(c, regular, 9, 40, y, row[0]);
                    text(c, bold, 11, 40, y - 14, row[1] == null ? "" : row[1]);
                    y -= 36;
                }
                text(c, regular, 9, 40, y - 4, "Numéro du document / Document number");
                text(c, bold, 12, 40, y - 20, number);
                y -= 52;
                for (String line : new String[] {
                        "Ce document atteste uniquement que la demande a été déposée. Il n'établit aucun droit sur la terre,",
                        "ni propriété, ni décision. Document de démonstration, sans valeur légale.",
                        "This document only acknowledges that the application was submitted. It establishes no right to land,",
                        "no ownership and no decision. Demonstration document, no legal value.",
                        "",
                        "Vérifier / Verify: scannez le code ou ouvrez / scan the code or open",
                        verifyUrl,
                        "Seul le fichier PDF identique à l'original est authentique ; une copie papier se vérifie par son numéro.",
                        "Only a PDF identical to the original is authentic; a paper copy is checked by its number.",
                }) {
                    text(c, regular, 8.5f, 40, y, line);
                    y -= 13;
                }
                PDImageXObject qr = LosslessFactory.createFromImage(pdf, qr(verifyUrl));
                c.drawImage(qr, w - 40 - 130, top - 330, 130, 130);
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("the document could not be drawn", e);
        }
    }

    @Override
    public Verification.Claim read(byte[] presented) {
        boolean pdf = presented.length > 5 && new String(presented, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
        if (!pdf) {
            return new Verification.Claim(false, false, null);
        }
        try (PDDocument document = Loader.loadPDF(presented)) {
            PDDocumentInformation info = document.getDocumentInformation();
            String number = info.getCustomMetadataValue(NUMBER_KEY);
            boolean claims = number != null || (info.getProducer() != null && info.getProducer().startsWith("SNIGF"))
                    || (info.getTitle() != null && info.getTitle().contains("SNIGF"));
            return new Verification.Claim(true, claims, number == null ? null : number.strip());
        } catch (IOException | RuntimeException e) {
            // Unreadable as a PDF: it claims nothing we can read; its bytes are still compared.
            return new Verification.Claim(true, false, null);
        }
    }

    private void text(PDPageContentStream c, PDType1Font font, float size, float x, float y, String value)
            throws IOException {
        c.beginText();
        c.setFont(font, size);
        c.newLineAtOffset(x, y);
        c.showText(drawable(font, value));
        c.endText();
    }

    /** Each character the font cannot draw becomes "?": a name never stops the document. */
    private static String drawable(PDType1Font font, String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }

    private static String when(String iso) {
        try {
            return WHEN.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso == null ? "" : iso;
        }
    }

    private static BufferedImage qr(String url) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0, hints);
            int scale = 4;
            BufferedImage image = new BufferedImage(matrix.getWidth() * scale, matrix.getHeight() * scale,
                    BufferedImage.TYPE_BYTE_BINARY);
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x / scale, y / scale) ? 0x000000 : 0xFFFFFF);
                }
            }
            return image;
        } catch (WriterException e) {
            throw new IllegalStateException("the verification code could not be drawn", e);
        }
    }
}
