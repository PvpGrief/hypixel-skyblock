package com.bazaarflipper.core.api;

/** Thrown when an API response is missing, malformed or fails validation. */
public class ApiException extends Exception {
	private static final long serialVersionUID = 1L;

	public ApiException(String message) {
		super(message);
	}

	public ApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
