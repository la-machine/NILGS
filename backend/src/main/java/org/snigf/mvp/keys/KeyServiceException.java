// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.keys;

/**
 * Key custody is unavailable or refused the operation, or protected data failed its
 * integrity check. The message never contains key material, tokens or plaintext.
 */
public class KeyServiceException extends RuntimeException {

    public KeyServiceException(String message) {
        super(message);
    }

    public KeyServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
