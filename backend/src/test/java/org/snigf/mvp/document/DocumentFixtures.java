// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

/** Synthetic files for the document tests: small, generated, never real documents. */
public final class DocumentFixtures {

    private DocumentFixtures() {
    }

    /** A minimal, well-formed PDF; {@code extra} goes into the catalogue (to add active content). */
    public static byte[] pdf(String extra) {
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R " + extra + " >> endobj\n"
                + "2 0 obj << /Type /Pages /Kids [] /Count 0 >> endobj\n"
                + "trailer << /Root 1 0 R >>\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    public static byte[] pdf() {
        return pdf("");
    }

    public static byte[] png(int width, int height) {
        return image("png", width, height);
    }

    public static byte[] jpeg(int width, int height) {
        return image("jpg", width, height);
    }

    /** A web page: what a "disguised" upload often is. */
    public static byte[] html() {
        return "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>".getBytes(StandardCharsets.UTF_8);
    }

    /** The start of a Windows executable (MZ header). */
    public static byte[] executable() {
        byte[] bytes = new byte[512];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        bytes[0x3C] = (byte) 0x80;
        bytes[0x80] = 'P';
        bytes[0x81] = 'E';
        return bytes;
    }

    private static byte[] image(String format, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x2E7D32);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
