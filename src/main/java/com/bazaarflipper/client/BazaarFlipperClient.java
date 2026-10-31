package com.bazaarflipper.client;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.lwjgl.glfw.GLFW;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

import com.bazaarflipper.client.ui.FlipperScreen;
import com.bazaarflipper.client.ui.HudOverlay;
import com.bazaarflipper.core.Coins;
import com.bazaarflipper.core.calc.FlipMode;
import com.bazaarflipper.core.calc.FlipResult;
import com.bazaarflipper.core.calc.FlipSettings;

/** Client entry point: key mappings, the /bzflip command, the HUD element and chat alerts. */
public final class BazaarFlipperClient implements ClientModInitializer {
	public static final String MOD_ID = "bazaarflipper";
	private static final long ALERT_REPEAT_MS = 15 * 60 * 1000L;

	private static BazaarFlipperClient instance;

	private final ConfigManager config = new ConfigManager();
	private DataService data;
	private KeyMapping openKey;
	private KeyMapping hudKey;
	private boolean openRequested;

	private DataService.Results lastSeenResults;
	private String lastAlertId;
	private long lastAlertTime;

	public static BazaarFlipperClient get() {
		return instance;
	}

	public ConfigManager config() {
		return config;
	}

	public FlipSettings settings() {
		return config.settings();
	}

	public DataService data() {
		return data;
	}

	@Override
	public void onInitializeClient() {
		instance = this;
		config.load();
		data = new DataService(config);
		data.start();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.bazaarflipper.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
		hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.bazaarflipper.toggle_hud", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(MOD_ID, "top_flips"), HudOverlay::extract);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> registerCommands(dispatcher));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			config.save();
			data.shutdown();
		});
	}

	private void onTick(Minecraft client) {
		while (openKey.consumeClick()) {
			openRequested = true;
		}
		while (hudKey.consumeClick()) {
			toggleHud();
		}
		if (openRequested) {
			// Opened from a tick, never directly from a command, so the closing chat screen does not replace it.
			openRequested = false;
			client.gui.setScreen(new FlipperScreen(this));
		}
		checkAlert(client);
	}

	public void toggleHud() {
		settings().hudEnabled = !settings().hudEnabled;
		config.save();
		chat(Component.literal("HUD " + (settings().hudEnabled ? "enabled" : "disabled")).withStyle(ChatFormatting.GRAY));
	}

	private void checkAlert(Minecraft client) {
		DataService.Results r = data.results();
		if (r == null || r == lastSeenResults || client.player == null) {
			return;
		}
		lastSeenResults = r;
		FlipSettings s = settings();
		if (!s.alertsEnabled || r.flips().isEmpty() || r.settings().mode != s.mode) {
			return;
		}
		FlipResult top = r.flips().getFirst();
		long now = System.currentTimeMillis();
		if (top.profitPerHour() < s.alertMinProfitPerHour) {
			return;
		}
		if (top.id().equals(lastAlertId) && now - lastAlertTime < ALERT_REPEAT_MS) {
			return;
		}
		lastAlertId = top.id();
		lastAlertTime = now;
		chat(Component.literal("Top " + s.mode.label + " flip: ").withStyle(ChatFormatting.GOLD).append(describe(top)));
	}

	/** One-line chat summary of a flip. */
	public static MutableComponent describe(FlipResult f) {
		return Component.literal(f.name()).withStyle(ChatFormatting.YELLOW)
				.append(Component.literal(" +" + Coins.compact(f.profitPerHour()) + "/h").withStyle(ChatFormatting.GREEN))
				.append(Component.literal(String.format(Locale.ROOT, " (%s/item, %s/h items, risk %d) %s",
						Coins.compact(f.profitPerItem()), Coins.compact(f.itemsPerHour()), f.risk(), f.route())).withStyle(ChatFormatting.GRAY));
	}

	public static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.sendSystemMessage(Component.literal("[Bazaar Flipper] ").withStyle(ChatFormatting.DARK_AQUA).append(message));
		}
	}

	// ---------------------------------------------------------------------------------------------- commands

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("bzflip")
				.executes(ctx -> {
					openRequested = true;
					return Command.SINGLE_SUCCESS;
				})
				.then(ClientCommands.literal("top")
						.executes(ctx -> printTop(5))
						.then(ClientCommands.argument("count", IntegerArgumentType.integer(1, 20))
								.executes(ctx -> printTop(IntegerArgumentType.getInteger(ctx, "count")))))
				.then(ClientCommands.literal("refresh").executes(ctx -> {
					data.refreshNow();
					chat(Component.literal("Refreshing Bazaar data...").withStyle(ChatFormatting.GRAY));
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommands.literal("hud").executes(ctx -> {
					toggleHud();
					return Command.SINGLE_SUCCESS;
				}))
				.then(ClientCommands.literal("help").executes(ctx -> {
					printHelp();
					return Command.SINGLE_SUCCESS;
				}));

		LiteralArgumentBuilder<FabricClientCommandSource> mode = ClientCommands.literal("mode");
		for (FlipMode m : FlipMode.values()) {
			mode.then(ClientCommands.literal(m.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
				settings().mode = m;
				config.save();
				data.requestRecompute();
				chat(Component.literal("Mode: " + m.label).withStyle(ChatFormatting.GRAY));
				return Command.SINGLE_SUCCESS;
			}));
		}
		root.then(mode);
		dispatcher.register(root);
	}

	private int printTop(int count) {
		DataService.Results r = data.results();
		if (r == null) {
			chat(Component.literal(data.error() != null ? data.error() : "No Bazaar data yet, try again in a few seconds.").withStyle(ChatFormatting.RED));
			return 0;
		}
		List<FlipResult> flips = r.flips();
		chat(Component.literal("Top " + Math.min(count, flips.size()) + " " + r.settings().mode.label + " flips:").withStyle(ChatFormatting.GOLD));
		if (flips.isEmpty()) {
			chat(Component.literal("Nothing matches your filters right now.").withStyle(ChatFormatting.GRAY));
		}
		for (int i = 0; i < Math.min(count, flips.size()); i++) {
			chat(Component.literal((i + 1) + ". ").withStyle(ChatFormatting.WHITE).append(describe(flips.get(i))));
		}
		return Command.SINGLE_SUCCESS;
	}

	private void printHelp() {
		chat(Component.literal("/bzflip").withStyle(ChatFormatting.YELLOW).append(Component.literal(" open the flipper (or press B)").withStyle(ChatFormatting.GRAY)));
		chat(Component.literal("/bzflip top [n]").withStyle(ChatFormatting.YELLOW).append(Component.literal(" print the best flips").withStyle(ChatFormatting.GRAY)));
		chat(Component.literal("/bzflip mode <mode>").withStyle(ChatFormatting.YELLOW).append(Component.literal(" bazaar_flip, craft, forge, bazaar_to_npc, npc_to_bazaar").withStyle(ChatFormatting.GRAY)));
		chat(Component.literal("/bzflip refresh | hud").withStyle(ChatFormatting.YELLOW).append(Component.literal(" reload data / toggle the HUD").withStyle(ChatFormatting.GRAY)));
	}
}
