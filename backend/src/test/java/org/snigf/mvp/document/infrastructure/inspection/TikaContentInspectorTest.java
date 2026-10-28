// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.infrastructure.inspection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.snigf.mvp.document.application.DocumentProperties;
import org.snigf.mvp.document.domain.DocumentType;
import org.snigf.mvp.document.domain.Inspection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.snigf.mvp.document.DocumentFixtures.executable;
import static org.snigf.mvp.document.DocumentFixtures.html;
import static org.snigf.mvp.document.DocumentFixtures.jpeg;
import static org.snigf.mvp.document.DocumentFixtures.pdf;
import static org.snigf.mvp.document.DocumentFixtures.png;

/** E-13: a file is what its bytes are; a disguised file is refused, whatever its name says. */
class TikaContentInspectorTest {

    @TempDir
    Path dir;

    final TikaContentInspector inspector = new TikaContentInspector(new DocumentProperties(Path.of("unused"),
            1_048_576, 3, 4_000_000, Duration.ofHours(1), "-"));

    Inspection inspect(byte[] bytes, String name) throws IOException {
        Path file = Files.write(dir.resolve("upload.part"), bytes);
        return inspector.inspect(file, name);
    }

    @Test
    void pdfJpegAndPngAreRecognisedFromTheirBytes() throws IOException {
        assertThat(inspect(pdf(), "identity.pdf").type()).isEqualTo(DocumentType.PDF);
        assertThat(inspect(png(40, 30), "plan.PNG").type()).isEqualTo(DocumentType.PNG);
        assertThat(inspect(jpeg(40, 30), "photo.jpeg").type()).isEqualTo(DocumentType.JPEG);
        assertThat(inspect(jpeg(40, 30), "photo.jpg").ok()).isTrue();
    }

    @Test
    void aDisguisedFileIsRefusedWhateverItsName() throws IOException {
        assertThat(inspect(html(), "identity.pdf").code()).isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(inspect(executable(), "identity.pdf").code()).isEqualTo("TYPE_NOT_ALLOWED");
        assertThat(inspect(png(10, 10), "identity.pdf").code()).as("an image named as a PDF").isEqualTo("TYPE_MISMATCH");
        assertThat(inspect(pdf(), "identity").code()).as("no extension").isEqualTo("TYPE_MISMATCH");
    }

    @Test
    void aPdfWithActiveContentOrEncryptionOrNoEndIsRefused() throws IOException {
        assertThat(inspect(pdf("/OpenAction << /S /JavaScript /JS (app.alert(1)) >>"), "a.pdf").code())
                .isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(inspect(pdf("/OpenAction << /S /J#61vaScript >>"), "a.pdf").code())
                .as("an escaped name is decoded").isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(inspect(pdf("/Names << /EmbeddedFiles 3 0 R >>"), "a.pdf").code()).isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(inspect(pdf("/Launch 4 0 R"), "a.pdf").code()).isEqualTo("PDF_ACTIVE_CONTENT");
        assertThat(inspect(pdf("/Encrypt 5 0 R"), "a.pdf").code()).isEqualTo("PDF_ENCRYPTED");
        assertThat(inspect("%PDF-1.4\n1 0 obj << >> endobj\n".getBytes(), "a.pdf").code()).isEqualTo("PDF_MALFORMED");
        assertThat(inspect(pdf("/JSON (not a script)"), "a.pdf").ok()).as("a longer name is not /JS").isTrue();
    }

    @Test
    void anImageTooLargeOnceDecodedIsRefused() throws IOException {
        assertThat(inspect(png(2500, 2000), "plan.png").code()).as("5 million pixels over a 4 million limit")
                .isEqualTo("IMAGE_TOO_LARGE");
        byte[] truncated = java.util.Arrays.copyOf(png(40, 30), 20);
        assertThat(inspect(truncated, "plan.png").code()).isEqualTo("IMAGE_MALFORMED");
    }

    @Test
    void pdfNamesAreDecodedAsThePdfStandardSays() {
        assertThat(TikaContentInspector.decode("J#61vaScript")).isEqualTo("JavaScript");
        assertThat(TikaContentInspector.decode("Launch")).isEqualTo("Launch");
    }
}
