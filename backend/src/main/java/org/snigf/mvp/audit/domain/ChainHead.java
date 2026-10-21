// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.audit.domain;

/**
 * The newest event of the chain.
 *
 * @param chainPos its position
 * @param hash     its chain hash (32 bytes)
 */
public record ChainHead(long chainPos, byte[] hash) {

    public ChainHead {
        hash = hash.clone();
    }

    @Override
    public byte[] hash() {
        return hash.clone();
    }
}
