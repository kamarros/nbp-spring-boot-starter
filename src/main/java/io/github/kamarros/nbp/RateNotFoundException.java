package io.github.kamarros.nbp;

/**
 * Thrown when NBP has no data for the requested currency or date
 * (HTTP 404), e.g. on weekends and public holidays.
 */
public class RateNotFoundException extends NbpException {

    private static final long serialVersionUID = 1L;

    public RateNotFoundException(String message) {
        super(message);
    }
}
