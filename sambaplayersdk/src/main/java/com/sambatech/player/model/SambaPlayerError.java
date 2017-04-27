package com.sambatech.player.model;

import android.graphics.drawable.Drawable;
import android.support.annotation.DrawableRes;

/**
 * Possible player errors reported through `SambaPlayerListener.onError` event.
 *
 * @author Leandro Zanol - 11/14/16
 */
public enum SambaPlayerError {
	invalidUrl,
	emptyUrl,
	rootedDevice,
	unknown;

	private int code;
	private String message;
	private boolean critical;
	private Exception exception;
	private @DrawableRes int drawableRes;

	static {
		invalidUrl.setValues(0, "Invalid URL format", true);
		emptyUrl.setValues(1, "Missing URL for the specified media", true);
		rootedDevice.setValues(2, "Specified media cannot play on a rooted device", true);
		unknown.setValues(3, "Unknown exception");
	}

	/**
	 * The exception related code
	 * @return The exception code
	 */
	public int getCode() {
		return code;
	}

	/**
	 * The exception related message
	 * @return The exception message
	 */
	public String getMessage() {
		return message;
	}

	/**
	 * Whether exception is critical (destroys player) or not
	 * @return True if is critical or false otherwise
	 */
	public boolean isCritical() {
		return critical;
	}

	/**
	 * The exception related exception
	 * @return The exception exception
	 */
	public Exception getException() {
		return exception;
	}

	public @DrawableRes int getDrawableRes() {
		return drawableRes;
	}

	/**
	 * Replaces default exception message for current instance.
	 * @param code The exception code
	 * @param message The message to be replaced
	 * @param critical Whether exception is critical (destroys player) or not
	 * @param exception The exception related exception
	 * @param drawableRes A custom error image to show with the message
	 * @return The reference to itself
	 */
	public SambaPlayerError setValues(int code, String message, boolean critical, Exception exception, @DrawableRes int drawableRes) {
		this.code = code;
		this.message = message;
		this.critical = critical;
		this.exception = exception;
		this.drawableRes = drawableRes;
		return this;
	}

	/**
	 * Replaces default exception message for current instance.
	 * @param code The exception code
	 * @param message The message to be replaced
	 * @param critical Whether exception is critical (destroys player) or not
	 * @param exception The exception related exception
	 * @return The reference to itself
	 */
	public SambaPlayerError setValues(int code, String message, boolean critical, Exception exception) {
		return setValues(code, message, critical, exception, 0);
	}

	/**
	 * Replaces default exception message for current instance.
	 * @param code The exception code
	 * @param message The message to be replaced
	 * @param critical Whether exception is critical (destroys player) or not
	 * @return The same instance reference
	 */
	public SambaPlayerError setValues(int code, String message, boolean critical) {
		return setValues(code, message, critical, null);
	}

	/**
	 * Replaces default exception message for current instance.
	 * @param code The exception code
	 * @param message The message to be replaced
	 * @return The same instance reference
	 */
	public SambaPlayerError setValues(int code, String message) {
		return setValues(code, message, false);
	}

	@Override
	public String toString() {
		return message;
	}
}
