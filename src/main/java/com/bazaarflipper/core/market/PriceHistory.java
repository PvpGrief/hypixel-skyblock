package com.bazaarflipper.core.market;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.bazaarflipper.core.Stats;
import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.Product;

/**
 * Rolling per-product history of the (bait-filtered) best buy-order and sell-offer prices, collected from
 * the API while the game runs and saved to disk. The medians of this history are the reference that
 * makes the calculator resistant to short-term manipulation: a price that suddenly jumps away from its
 * own recent median is not trusted.
 */
public final class PriceHistory {
	private static final int MAGIC = 0x425A4648; // "BZFH"
	private static final int VERSION = 1;
	public static final int MAX_SAMPLES = 480;
	public static final long MAX_AGE_SEC = 24 * 3600;

	private final int minIntervalSec;
	private final Map<String, Series> series = new HashMap<>();
	private long lastRecordedSec = Long.MIN_VALUE;

	/** Robust statistics over a time window. Values are NaN when there are no samples. */
	public record Window(int samples, long spanSec, double medianBid, double medianAsk, double bidCv, double askCv) {
		public static final Window EMPTY = new Window(0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

		public double medianSpreadRatio() {
			return (Stats.isUsable(medianBid) && Stats.isUsable(medianAsk)) ? medianAsk / medianBid : Double.NaN;
		}
	}

	private static final class Series {
		final long[] t = new long[MAX_SAMPLES];
		final float[] bid = new float[MAX_SAMPLES];
		final float[] ask = new float[MAX_SAMPLES];
		int start;
		int size;

		void add(long time, float b, float a) {
			int idx;
			if (size < MAX_SAMPLES) {
				idx = (start + size) % MAX_SAMPLES;
				size++;
			} else {
				idx = start;
				start = (start + 1) % MAX_SAMPLES;
			}
			t[idx] = time;
			bid[idx] = b;
			ask[idx] = a;
		}
	}

	/** @param minIntervalSec minimum spacing between recorded samples (keeps one sample per interval) */
	public PriceHistory(int minIntervalSec) {
		this.minIntervalSec = Math.max(1, minIntervalSec);
	}

	/** Records a snapshot if enough time passed since the previous sample. Returns true if recorded. */
	public synchronized boolean record(BazaarSnapshot snap) {
		long time = (snap.lastUpdated() > 0 ? snap.lastUpdated() : snap.fetchedAt()) / 1000;
		if (lastRecordedSec != Long.MIN_VALUE && time < lastRecordedSec + minIntervalSec) {
			return false;
		}
		lastRecordedSec = time;
		for (Product p : snap.products().values()) {
			BookMath.Best bid = BookMath.effectiveBest(p.buyOrders(), p.instaSellWeek() / 168.0, true);
			BookMath.Best ask = BookMath.effectiveBest(p.sellOffers(), p.instaBuyWeek() / 168.0, false);
			float b = bid.present() ? (float) bid.price() : Float.NaN;
			float a = ask.present() ? (float) ask.price() : Float.NaN;
			if (Float.isNaN(b) && Float.isNaN(a)) {
				continue;
			}
			series.computeIfAbsent(p.id(), k -> new Series()).add(time, b, a);
		}
		return true;
	}

	/** Statistics for the samples of {@code id} newer than {@code nowSec - windowSec}. */
	public synchronized Window window(String id, long nowSec, long windowSec) {
		Series s = series.get(id);
		if (s == null || s.size == 0) {
			return Window.EMPTY;
		}
		double[] bids = new double[s.size];
		double[] asks = new double[s.size];
		int nb = 0;
		int na = 0;
		long first = Long.MAX_VALUE;
		long last = Long.MIN_VALUE;
		int count = 0;
		for (int i = 0; i < s.size; i++) {
			int idx = (s.start + i) % MAX_SAMPLES;
			long time = s.t[idx];
			if (time < nowSec - windowSec || time > nowSec + 600) {
				continue;
			}
			count++;
			first = Math.min(first, time);
			last = Math.max(last, time);
			if (!Float.isNaN(s.bid[idx])) {
				bids[nb++] = s.bid[idx];
			}
			if (!Float.isNaN(s.ask[idx])) {
				asks[na++] = s.ask[idx];
			}
		}
		if (count == 0) {
			return Window.EMPTY;
		}
		double mb = Stats.median(bids, nb);
		double ma = Stats.median(asks, na);
		double cvB = nb > 0 && mb > 0 ? 1.4826 * Stats.mad(bids, nb) / mb : Double.NaN;
		double cvA = na > 0 && ma > 0 ? 1.4826 * Stats.mad(asks, na) / ma : Double.NaN;
		return new Window(count, last - first, mb, ma, cvB, cvA);
	}

	public synchronized int productCount() {
		return series.size();
	}

	/** Largest number of samples stored for any product (a rough "how much history do we have"). */
	public synchronized int maxSamples() {
		int max = 0;
		for (Series s : series.values()) {
			max = Math.max(max, s.size);
		}
		return max;
	}

	public synchronized void clear() {
		series.clear();
		lastRecordedSec = Long.MIN_VALUE;
	}

	public synchronized void save(OutputStream raw) throws IOException {
		GZIPOutputStream gz = new GZIPOutputStream(raw);
		DataOutputStream out = new DataOutputStream(gz);
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeLong(lastRecordedSec);
		out.writeInt(series.size());
		for (Map.Entry<String, Series> e : series.entrySet()) {
			Series s = e.getValue();
			out.writeUTF(e.getKey());
			out.writeInt(s.size);
			for (int i = 0; i < s.size; i++) {
				int idx = (s.start + i) % MAX_SAMPLES;
				out.writeLong(s.t[idx]);
				out.writeFloat(s.bid[idx]);
				out.writeFloat(s.ask[idx]);
			}
		}
		out.flush();
		gz.finish();
	}

	/**
	 * Loads a history file written by {@link #save}. Corrupt, implausible or old samples are dropped, so a
	 * tampered file cannot inject fake reference prices beyond what a live market check would accept.
	 */
	public synchronized void load(InputStream raw, long nowSec) throws IOException {
		DataInputStream in = new DataInputStream(new GZIPInputStream(raw));
		if (in.readInt() != MAGIC || in.readInt() != VERSION) {
			throw new IOException("Not a bazaar history file");
		}
		long lastRec = in.readLong();
		int products = in.readInt();
		if (products < 0 || products > 100_000) {
			throw new IOException("Corrupt history file");
		}
		Map<String, Series> loaded = new HashMap<>();
		for (int p = 0; p < products; p++) {
			String id = in.readUTF();
			int n = in.readInt();
			if (n < 0 || n > MAX_SAMPLES) {
				throw new IOException("Corrupt history file");
			}
			Series s = new Series();
			long prev = Long.MIN_VALUE;
			for (int i = 0; i < n; i++) {
				long t = in.readLong();
				float b = in.readFloat();
				float a = in.readFloat();
				boolean fresh = t >= nowSec - MAX_AGE_SEC && t <= nowSec + 600 && t > prev;
				boolean sane = valid(b) && valid(a) && !(Float.isNaN(b) && Float.isNaN(a));
				if (fresh && sane) {
					s.add(t, b, a);
					prev = t;
				}
			}
			if (s.size > 0) {
				loaded.put(id, s);
			}
		}
		series.clear();
		series.putAll(loaded);
		lastRecordedSec = Math.min(lastRec, nowSec);
	}

	private static boolean valid(float v) {
		return Float.isNaN(v) || (v > 0 && v < 1e11f);
	}
}
