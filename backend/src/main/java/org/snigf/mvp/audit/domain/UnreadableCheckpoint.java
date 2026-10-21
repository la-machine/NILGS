// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

/**
 * An archived checkpoint that cannot be parsed: reported, never ignored.
 *
 * @param name     file name
 * @param chainPos position taken from the name, or 0
 * @param problem  short reason
 */
public record UnreadableCheckpoint(String name, long chainPos, String problem) {
}
