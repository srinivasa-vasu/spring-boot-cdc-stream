package io.cdc.stream.apply;

/**
 * Thrown when the pipeline cannot continue without either losing data or corrupting the
 * sink — an unsafe source type change, a table it may not create, a row it cannot key.
 * Deliberately not retried: the pipeline stops so the divergence is visible, rather than
 * skipping the event and drifting silently.
 */
public class UnrecoverableApplyException extends RuntimeException {

	public UnrecoverableApplyException(String message) {
		super(message);
	}

	public UnrecoverableApplyException(String message, Throwable cause) {
		super(message, cause);
	}

}
