package com.bazaarflipper.core;

import java.util.Arrays;

/** Small robust-statistics helpers (median / MAD are hard to move with a few outliers). */
public final class Stats {
	private Stats() {
	}

	/** Median of the first {@code n} values (the array is copied, not modified). NaN when n == 0. */
	public static double median(double[] values, int n) {
		if (n <= 0) {
			return Double.NaN;
		}
		double[] copy = Arrays.copyOf(values, n);
		Arrays.sort(copy);
		int mid = n / 2;
		return (n % 2 == 1) ? copy[mid] : (copy[mid - 1] + copy[mid]) / 2.0;
	}

	/** Median absolute deviation of the first {@code n} values. */
	public static double mad(double[] values, int n) {
		double med = median(values, n);
		if (Double.isNaN(med)) {
			return Double.NaN;
		}
		double[] dev = new double[n];
		for (int i = 0; i < n; i++) {
			dev[i] = Math.abs(values[i] - med);
		}
		return median(dev, n);
	}

	public static boolean isUsable(double v) {
		return !Double.isNaN(v) && !Double.isInfinite(v) && v > 0;
	}
}
