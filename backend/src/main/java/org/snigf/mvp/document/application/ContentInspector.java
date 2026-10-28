// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.document.application;

import java.nio.file.Path;

import org.snigf.mvp.document.domain.Inspection;

/** Recognises a staged file from its bytes and refuses what must not be kept. */
public interface ContentInspector {

    /** @param name the cleaned display name, whose extension must agree with the bytes */
    Inspection inspect(Path file, String name);
}
