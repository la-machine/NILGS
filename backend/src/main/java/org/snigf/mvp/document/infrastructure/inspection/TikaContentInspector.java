// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.inspection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.tika.Tika;
import org.snigf.mvp.document.application.ContentInspector;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.domain.DocumentType;
import org.snigf.mvp.document.domain.Inspection;
import org.springframework.stereotype.Component;

/**
 * Recognises an upload from its bytes (Baseline: content-type detection by file signature,
 * Apache Tika), never from its name or the type the browser declared. Then, by type:
 * <ul>
 *   <li>the name's extension must agree with the bytes ({@code TYPE_MISMATCH});</li>
 *   <li>a PDF must look like one from start to end, must not be encrypted (it could not be
 *       checked) and must not carry active content: scripts, launch actions, embedded files,
 *       forms that submit or import, rich media, XFA. Names are read with their {@code #xx}
 *       escapes decoded, so {@code /J#61vaScript} is caught. This is a screen, not a malware
 *       scan: content inside compressed object streams is not read; ClamAV comes with the
 *       pilot (P-02);</li>
 *   <li>an image must have a readable header and at most {@code SNIGF_DOCUMENT_MAX_PIXELS}
 *       pixels, so a small file cannot expand into gigabytes when someone opens it.</li>
 * </ul>
 */
@Component
class TikaContentInspector implements ContentInspector {

    /** PDF names that act rather than show. */
    static final Set<String> ACTIVE = Set.of("JavaScript", "JS", "Launch", "EmbeddedFile", "EmbeddedFiles",
            "RichMedia", "XFA", "SubmitForm", "ImportData", "GoToE");
    private static final Pattern NAME = Pattern.compile("/([A-Za-z0-9#._-]{1,64})");
    private static final Pattern ESCAPE = Pattern.compile("#([0-9A-Fa-f]{2})");
    private static final int TAIL = 2048;

    private final Tika tika = new Tika();
    private final long maxPixels;

    TikaContentInspector(DocumentProperties settings) {
        this.maxPixels = settings.maxPixels();
    }

    @Override
    public Inspection inspect(Path file, String name) {
        String detected;
        try (InputStream in = Files.newInputStream(file)) {
            // The stream alone: no name, so only the bytes decide.
            detected = tika.detect(in);
        } catch (IOException e) {
            return Inspection.refused("TYPE_NOT_ALLOWED", "unreadable");
        }
        Optional<DocumentType> type = DocumentType.ofMediaType(detected);
        if (type.isEmpty()) {
            return Inspection.refused("TYPE_NOT_ALLOWED", detected);
        }
        if (!type.get().named(name)) {
            return Inspection.refused("TYPE_MISMATCH", detected);
        }
        return switch (type.get()) {
            case PDF -> pdf(file);
            case JPEG, PNG -> image(file, type.get());
        };
    }

    private Inspection pdf(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return Inspection.refused("PDF_MALFORMED", DocumentType.PDF.mediaType());
        }
        // ISO-8859-1 keeps every byte as one character, so offsets and names survive.
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        String tail = text.substring(Math.max(0, text.length() - TAIL));
        if (!text.startsWith("%PDF-") || !tail.contains("%%EOF")) {
            return Inspection.refused("PDF_MALFORMED", DocumentType.PDF.mediaType());
        }
        Matcher names = NAME.matcher(text);
        while (names.find()) {
            String pdfName = decode(names.group(1));
            if (pdfName.equals("Encrypt")) {
                return Inspection.refused("PDF_ENCRYPTED", DocumentType.PDF.mediaType());
            }
            if (ACTIVE.contains(pdfName)) {
                return Inspection.refused("PDF_ACTIVE_CONTENT", DocumentType.PDF.mediaType());
            }
        }
        return Inspection.accepted(DocumentType.PDF);
    }

    private Inspection image(Path file, DocumentType type) {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                return Inspection.refused("IMAGE_MALFORMED", type.mediaType());
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0) {
                    return Inspection.refused("IMAGE_MALFORMED", type.mediaType());
                }
                if (pixels > maxPixels) {
                    return Inspection.refused("IMAGE_TOO_LARGE", type.mediaType());
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return Inspection.refused("IMAGE_MALFORMED", type.mediaType());
        }
        return Inspection.accepted(type);
    }

    /** A PDF name with its #xx escapes decoded (PDF 32000, 7.3.5). */
    static String decode(String name) {
        Matcher escape = ESCAPE.matcher(name);
        StringBuilder out = new StringBuilder();
        while (escape.find()) {
            escape.appendReplacement(out, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(escape.group(1), 16))));
        }
        escape.appendTail(out);
        return out.toString();
    }
}
