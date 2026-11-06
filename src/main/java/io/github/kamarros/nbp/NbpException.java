package io.github.kamarros.nbp;

/** Base exception for every failure when talking to the NBP API. */
public class NbpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NbpException(String message) {
        super(message);
    }

    public NbpException(String message, Throwable cause) {
        super(message, cause);
    }
}
