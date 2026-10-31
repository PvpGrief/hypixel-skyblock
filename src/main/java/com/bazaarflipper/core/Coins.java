package com.bazaarflipper.core;

import java.util.Locale;

/** Formatting and parsing of coin amounts ("1.5m", "250k", "2b", "1,234,567"). */
public final class Coins {
	private Coins() {
	}

	/** Compact format: 999, 12.3k, 4.56M, 1.2B. */
	public static String compact(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "-";
		}
		double abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1e9) {
			return sign + trim(abs / 1e9) + "B";
		}
		if (abs >= 1e6) {
			return sign + trim(abs / 1e6) + "M";
		}
		if (abs >= 1e3) {
			return sign + trim(abs / 1e3) + "k";
		}
		if (abs >= 100) {
			return sign + String.format(Locale.ROOT, "%.0f", abs);
		}
		return sign + String.format(Locale.ROOT, "%.1f", abs);
	}

	/** Full format with thousands separators and one decimal: 1,234,567.8 */
	public static String full(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "-";
		}
		return String.format(Locale.ROOT, "%,.1f", value);
	}

	private static String trim(double v) {
		String s = v >= 100 ? String.format(Locale.ROOT, "%.0f", v)
				: v >= 10 ? String.format(Locale.ROOT, "%.1f", v)
				: String.format(Locale.ROOT, "%.2f", v);
		if (s.contains(".")) {
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}

	/**
	 * Parses user input such as "10m", "2.5B", "750k", "1,000,000" or "1e6".
	 *
	 * @return the value, or {@link Double#NaN} when the text is not a valid non-negative amount
	 */
	public static double parse(String text) {
		if (text == null) {
			return Double.NaN;
		}
		String s = text.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "").replace(" ", "");
		if (s.isEmpty()) {
			return Double.NaN;
		}
		double mult = 1;
		char last = s.charAt(s.length() - 1);
		switch (last) {
			case 'k' -> mult = 1e3;
			case 'm' -> mult = 1e6;
			case 'b' -> mult = 1e9;
			case 't' -> mult = 1e12;
			default -> {
			}
		}
		if (mult != 1) {
			s = s.substring(0, s.length() - 1);
		}
		try {
			double v = Double.parseDouble(s) * mult;
			if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
				return Double.NaN;
			}
			return v;
		} catch (NumberFormatException e) {
			return Double.NaN;
		}
	}
}
