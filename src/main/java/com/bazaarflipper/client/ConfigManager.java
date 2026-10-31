package com.bazaarflipper.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import com.bazaarflipper.core.calc.FlipSettings;

/** Loads and saves {@code config/bazaarflipper/config.json}. */
public final class ConfigManager {
	private static final Logger LOG = LoggerFactory.getLogger("BazaarFlipper");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();

	private final Path dir;
	private final Path file;
	private volatile FlipSettings settings = new FlipSettings();

	public ConfigManager() {
		this.dir = FabricLoader.getInstance().getConfigDir().resolve("bazaarflipper");
		this.file = dir.resolve("config.json");
	}

	public Path dir() {
		return dir;
	}

	public FlipSettings settings() {
		return settings;
	}

	public void load() {
		if (!Files.exists(file)) {
			save();
			return;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			FlipSettings loaded = GSON.fromJson(r, FlipSettings.class);
			settings = (loaded == null ? new FlipSettings() : loaded).sanitize();
		} catch (IOException | JsonParseException e) {
			LOG.warn("Could not read {}, using defaults", file, e);
			settings = new FlipSettings();
		}
	}

	public synchronized void save() {
		try {
			Files.createDirectories(dir);
			Path tmp = dir.resolve("config.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(settings.sanitize(), w);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			LOG.warn("Could not save {}", file, e);
		}
	}
}
