// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A name given to a file is kept for display only, never as a path. */
class DocumentNameTest {

    @Test
    void directoriesControlCharactersAndLeadingDotsAreDropped() {
        assertThat(DocumentName.clean("C:\\Users\\ama\\carte d'identité.pdf")).isEqualTo("carte d'identité.pdf");
        assertThat(DocumentName.clean("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentName.clean("plan\u0000\u202e.png")).isEqualTo("plan.png");
        assertThat(DocumentName.clean("...hidden.pdf")).isEqualTo("hidden.pdf");
        assertThat(DocumentName.clean("  ")).isEmpty();
        assertThat(DocumentName.clean(null)).isEmpty();
    }

    @Test
    void aLongNameKeepsItsExtension() {
        String cleaned = DocumentName.clean("a".repeat(300) + ".pdf");
        assertThat(cleaned).hasSize(DocumentName.MAX).endsWith(".pdf");
    }

    @Test
    void anExtensionMustAgreeWithTheType() {
        assertThat(DocumentType.PDF.named("x.PDF")).isTrue();
        assertThat(DocumentType.JPEG.named("x.jpg")).isTrue();
        assertThat(DocumentType.PNG.named("x.png.exe")).isFalse();
        assertThat(DocumentType.PDF.named("pdf")).isFalse();
    }
}
