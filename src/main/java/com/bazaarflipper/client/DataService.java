package com.bazaarflipper.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.bazaarflipper.core.api.ApiException;
import com.bazaarflipper.core.api.BazaarParser;
import com.bazaarflipper.core.api.ItemsParser;
import com.bazaarflipper.core.calc.FlipCalculator;
import com.bazaarflipper.core.calc.FlipResult;
import com.bazaarflipper.core.calc.FlipSettings;
import com.bazaarflipper.core.data.SkyblockData;
import com.bazaarflipper.core.market.PriceHistory;
import com.bazaarflipper.core.model.BazaarSnapshot;
import com.bazaarflipper.core.model.ItemInfo;

/**
 * Background worker: polls the official Hypixel API, validates every snapshot, maintains the price history
 * and recomputes the flip list. All network and calculation work runs on one daemon thread; the UI only
 * reads the immutable {@link Results} published here.
 */
public final class DataService {
	private static final Logger LOG = LoggerFactory.getLogger("BazaarFlipper");
	static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
	static final String ITEMS_URL = "https://api.hypixel.net/v2/resources/skyblock/items";
	private static final long ITEMS_REFRESH_MS = TimeUnit.HOURS.toMillis(6);
	private static final long HISTORY_SAVE_MS = TimeUnit.MINUTES.toMillis(5);
	private static final long STALE_MS = TimeUnit.MINUTES.toMillis(5);
	private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
	/** Seconds between stored history samples. */
	private static final int HISTORY_INTERVAL_SEC = 120;

	/** One computed result list together with the settings that produced it. */
	public record Results(List<FlipResult> flips, FlipSettings settings, long computedAt, long dataUpdated) {
	}

	private final ConfigManager config;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "BazaarFlipper-Worker");
		t.setDaemon(true);
		return t;
	});
	private final PriceHistory history = new PriceHistory(HISTORY_INTERVAL_SEC);
	private final AtomicBoolean recomputeQueued = new AtomicBoolean();

	private volatile SkyblockData data = SkyblockData.empty();
	private volatile Map<String, ItemInfo> items = Map.of();
	private volatile BazaarSnapshot bazaar;
	private volatile Results results;
	private volatile String error;
	private volatile boolean fetching;
	private volatile long lastBazaarAttempt;
	private volatile long lastItemsFetch;
	private long lastHistorySave = System.currentTimeMillis();

	public DataService(ConfigManager config) {
		this.config = config;
	}

	public void start() {
		exec.execute(this::loadLocalFiles);
		exec.scheduleWithFixedDelay(this::tick, 1, 5, TimeUnit.SECONDS);
	}

	public void shutdown() {
		exec.shutdown();
		try {
			exec.awaitTermination(3, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		saveHistory();
	}

	// ---------------------------------------------------------------------------------------------- getters

	public Results results() {
		return results;
	}

	public BazaarSnapshot bazaar() {
		return bazaar;
	}

	public String error() {
		return error;
	}

	public boolean fetching() {
		return fetching;
	}

	public PriceHistory history() {
		return history;
	}

	public SkyblockData data() {
		return data;
	}

	// ---------------------------------------------------------------------------------------------- actions

	/** Fetches fresh Bazaar data as soon as possible. */
	public void refreshNow() {
		exec.execute(() -> fetchBazaar(true));
	}

	/** Recomputes the flip list with the current settings (coalesced). */
	public void requestRecompute() {
		if (recomputeQueued.compareAndSet(false, true)) {
			exec.execute(() -> {
				recomputeQueued.set(false);
				recompute();
			});
		}
	}

	// ---------------------------------------------------------------------------------------------- worker

	private void tick() {
		try {
			long now = System.currentTimeMillis();
			if (now - lastItemsFetch > ITEMS_REFRESH_MS) {
				fetchItems();
			}
			if (now - lastBazaarAttempt >= config.settings().refreshSeconds * 1000L) {
				fetchBazaar(false);
			}
			if (now - lastHistorySave > HISTORY_SAVE_MS) {
				lastHistorySave = now;
				saveHistory();
			}
		} catch (RuntimeException e) {
			LOG.warn("Bazaar Flipper worker error", e);
		}
	}

	private void fetchBazaar(boolean manual) {
		lastBazaarAttempt = System.currentTimeMillis();
		fetching = true;
		try {
			String body = get(BAZAAR_URL);
			BazaarSnapshot snap = BazaarParser.parse(body, System.currentTimeMillis());
			String rejected = validate(snap);
			if (rejected != null) {
				error = rejected;
				LOG.warn("Rejected Bazaar snapshot: {}", rejected);
				return;
			}
			BazaarSnapshot prev = bazaar;
			bazaar = snap;
			history.record(snap);
			error = isStale(snap) ? "API data is more than 5 minutes old" : null;
			if (manual || prev == null || prev.lastUpdated() != snap.lastUpdated()) {
				recompute();
			}
		} catch (IOException | ApiException e) {
			error = "Could not load Bazaar data: " + e.getMessage();
			LOG.warn("Bazaar fetch failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			fetching = false;
		}
	}

	/**
	 * Sanity checks across snapshots. Returns a reason when the snapshot must not be used: data older than
	 * what we already have (e.g. a stale cache) or a partial response with far fewer products.
	 */
	private String validate(BazaarSnapshot snap) {
		BazaarSnapshot prev = bazaar;
		if (prev == null) {
			return null;
		}
		if (snap.lastUpdated() > 0 && prev.lastUpdated() > 0 && snap.lastUpdated() < prev.lastUpdated()) {
			return "API returned older data than before";
		}
		if (snap.products().size() < prev.products().size() / 2) {
			return "API returned a partial product list (" + snap.products().size() + " products)";
		}
		return null;
	}

	private static boolean isStale(BazaarSnapshot snap) {
		return snap.lastUpdated() > 0 && snap.fetchedAt() - snap.lastUpdated() > STALE_MS;
	}

	private void fetchItems() {
		lastItemsFetch = System.currentTimeMillis();
		try {
			Map<String, ItemInfo> parsed = ItemsParser.parse(get(ITEMS_URL));
			items = Map.copyOf(parsed);
			saveItemsCache(parsed);
			requestRecompute();
		} catch (IOException | ApiException e) {
			LOG.warn("Items fetch failed (NPC prices may be missing until the next try)", e);
			lastItemsFetch = System.currentTimeMillis() - ITEMS_REFRESH_MS + TimeUnit.MINUTES.toMillis(5);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void recompute() {
		BazaarSnapshot snap = bazaar;
		if (snap == null) {
			return;
		}
		FlipSettings s = config.settings().copy().sanitize();
		try {
			List<FlipResult> flips = new FlipCalculator(snap, items, data, history, s, System.currentTimeMillis()).compute();
			results = new Results(flips, s, System.currentTimeMillis(), snap.lastUpdated());
		} catch (RuntimeException e) {
			LOG.error("Flip calculation failed", e);
			error = "Calculation failed: " + e;
		}
	}

	private String get(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(20))
				.header("Accept", "application/json")
				.header("Accept-Encoding", "gzip")
				.header("User-Agent", "BazaarFlipper-Mod/1.0 (Fabric)")
				.GET()
				.build();
		HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream raw = resp.body()) {
			if (resp.statusCode() != 200) {
				throw new IOException("HTTP " + resp.statusCode());
			}
			boolean gzip = resp.headers().firstValue("Content-Encoding").map(v -> v.equalsIgnoreCase("gzip")).orElse(false);
			try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
				return readLimited(in);
			}
		}
	}

	private static String readLimited(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
		byte[] buf = new byte[1 << 16];
		int total = 0;
		int n;
		while ((n = in.read(buf)) != -1) {
			total += n;
			if (total > MAX_BODY_BYTES) {
				throw new IOException("Response too large");
			}
			out.write(buf, 0, n);
		}
		return out.toString(StandardCharsets.UTF_8);
	}

	// ---------------------------------------------------------------------------------------------- files

	private void loadLocalFiles() {
		// Bundled recipes; a user-supplied override file (same format) takes precedence.
		Path override = config.dir().resolve("skyblock_data.json");
		try (InputStream in = Files.exists(override) ? Files.newInputStream(override)
				: DataService.class.getResourceAsStream("/assets/bazaarflipper/skyblock_data.json")) {
			if (in != null) {
				data = SkyblockData.load(in);
				LOG.info("Loaded {} crafts, {} forge recipes, {} NPC offers ({})",
						data.crafts().size(), data.forge().size(), data.npcShop().size(), data.source());
			}
		} catch (IOException e) {
			LOG.error("Could not load recipe data", e);
		}

		Path hist = config.dir().resolve("history.bin.gz");
		if (Files.exists(hist)) {
			try (InputStream in = Files.newInputStream(hist)) {
				history.load(in, System.currentTimeMillis() / 1000);
				LOG.info("Loaded price history for {} products", history.productCount());
			} catch (IOException e) {
				LOG.warn("Ignoring unreadable price history", e);
			}
		}
		loadItemsCache();
	}

	private void saveHistory() {
		try {
			Files.createDirectories(config.dir());
			Path tmp = config.dir().resolve("history.bin.gz.tmp");
			try (OutputStream out = Files.newOutputStream(tmp)) {
				history.save(out);
			}
			Files.move(tmp, config.dir().resolve("history.bin.gz"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warn("Could not save price history", e);
		}
	}

	private void saveItemsCache(Map<String, ItemInfo> map) {
		JsonObject root = new JsonObject();
		root.addProperty("savedAt", System.currentTimeMillis());
		JsonArray arr = new JsonArray();
		for (ItemInfo i : map.values()) {
			JsonArray e = new JsonArray();
			e.add(i.id());
			e.add(i.name());
			e.add(i.npcSellPrice());
			arr.add(e);
		}
		root.add("items", arr);
		try {
			Files.createDirectories(config.dir());
			try (Writer w = Files.newBufferedWriter(config.dir().resolve("items_cache.json"), StandardCharsets.UTF_8)) {
				w.write(root.toString());
			}
		} catch (IOException e) {
			LOG.warn("Could not save items cache", e);
		}
	}

	private void loadItemsCache() {
		Path p = config.dir().resolve("items_cache.json");
		if (!Files.exists(p)) {
			return;
		}
		try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
			Map<String, ItemInfo> map = new HashMap<>();
			for (JsonElement el : root.getAsJsonArray("items")) {
				JsonArray a = el.getAsJsonArray();
				double npc = a.get(2).getAsDouble();
				map.put(a.get(0).getAsString(), new ItemInfo(a.get(0).getAsString(), a.get(1).getAsString(),
						npc > 0 && Double.isFinite(npc) ? npc : 0));
			}
			if (items.isEmpty()) {
				items = Map.copyOf(map);
			}
		} catch (IOException | RuntimeException e) {
			LOG.warn("Ignoring unreadable items cache", e);
		}
	}
}
