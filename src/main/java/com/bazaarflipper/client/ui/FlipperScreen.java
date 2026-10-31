package com.bazaarflipper.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoublePredicate;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import com.bazaarflipper.client.BazaarFlipperClient;
import com.bazaarflipper.client.DataService;
import com.bazaarflipper.core.Coins;
import com.bazaarflipper.core.calc.FlipCalculator;
import com.bazaarflipper.core.calc.FlipMode;
import com.bazaarflipper.core.calc.FlipResult;
import com.bazaarflipper.core.calc.FlipSettings;
import com.bazaarflipper.core.calc.TradeMethod;
import com.bazaarflipper.core.model.BazaarSnapshot;

/** The main Bazaar Flipper window. */
public final class FlipperScreen extends Screen {
	private static final int ROW_H = 11;
	private static final int GAP = 3;

	private static final int C_TEXT = 0xFFFFFFFF;
	private static final int C_MUTED = 0xFFAAAAAA;
	private static final int C_LABEL = 0xFFC8C8C8;
	private static final int C_TITLE = 0xFFFFAA00;
	private static final int C_GOOD = 0xFF55FF55;
	private static final int C_WARN = 0xFFFFFF55;
	private static final int C_BAD = 0xFFFF5555;
	private static final int C_PANEL = 0x99000000;
	private static final int C_HEADER = 0xCC202020;
	private static final int C_HOVER = 0x40FFFFFF;
	private static final int C_SELECTED = 0x60FFAA00;
	private static final int C_VALID = 0xFFE0E0E0;

	private static final double[] TAX_STEPS = {1.25, 1.125, 1.0};
	private static final int[] RISK_STEPS = {30, 50, 70};
	private static final double[] HISTORY_STEPS = {1, 3, 6, 12, 24};

	private final BazaarFlipperClient mod;
	private final FlipSettings s;
	private final List<Button> modeButtons = new ArrayList<>();

	private Button buyButton;
	private Button sellButton;
	private Button taxButton;
	private Button sortButton;
	private Button safeButton;
	private Button extraButton;
	private Button hudButton;
	private Button alertButton;
	private Button historyButton;
	private Button chatButton;

	private int left;
	private int panelW;
	private int labelY;
	private int tableTop;
	private int tableBottom;
	private int detailsTop;
	private int detailLines;
	private int scroll;
	private String selectedId;

	public FlipperScreen(BazaarFlipperClient mod) {
		super(Component.literal("Bazaar Flipper"));
		this.mod = mod;
		this.s = mod.settings();
	}

	@Override
	protected void init() {
		modeButtons.clear();
		panelW = Math.min(width - 16, 480);
		left = (width - panelW) / 2;
		int y = 18;

		// Mode tabs
		FlipMode[] modes = FlipMode.values();
		int tabW = (panelW - GAP * (modes.length - 1)) / modes.length;
		for (int i = 0; i < modes.length; i++) {
			FlipMode m = modes[i];
			Button b = addRenderableWidget(Button.builder(Component.literal(m.label), btn -> {
						s.mode = m;
						scroll = 0;
						selectedId = null;
						changed(true);
					})
					.pos(left + i * (tabW + GAP), y)
					.size(tabW, 18)
					.tooltip(Tooltip.create(Component.literal(m.description)))
					.build());
			modeButtons.add(b);
		}
		y += 24;

		// Number inputs (labels are drawn in extractRenderState)
		labelY = y;
		int boxW = (panelW - GAP * 4) / 5;
		int boxY = y + 10;
		numberBox(0, boxW, boxY, "Budget", Coins.compact(s.budget), v -> v >= 1, v -> s.budget = v,
				"Max coins tied up in flips at once (e.g. 10m, 1.5b).");
		numberBox(1, boxW, boxY, "Max items/h", s.maxItemsPerHour > 0 ? Coins.compact(s.maxItemsPerHour) : "0", v -> true,
				v -> s.maxItemsPerHour = v, "How many items you can handle per hour (0 = no limit).");
		numberBox(2, boxW, boxY, "Min volume/wk", Coins.compact(s.minWeeklyVolume), v -> true, v -> s.minWeeklyVolume = v,
				"Skip items traded less than this per week. Low volume = easy to manipulate.");
		numberBox(3, boxW, boxY, "Market share %", trim(s.marketSharePercent), v -> v >= 0.1 && v <= 100,
				v -> s.marketSharePercent = v, "Share of the market's hourly volume you expect to get (competition). 5% is realistic.");
		EditBox search = new EditBox(font, left + 4 * (boxW + GAP), boxY, boxW, 16, Component.literal("Search"));
		search.setMaxLength(40);
		search.setValue(s.search);
		search.setResponder(text -> {
			s.search = text;
			changed(false);
		});
		search.setTooltip(Tooltip.create(Component.literal("Filter by item name or id.")));
		addRenderableWidget(search);
		y = boxY + 22;

		// Toggles
		int tW = (panelW - GAP * 5) / 6;
		buyButton = addRenderableWidget(toggle(0, tW, y, b -> {
			s.buyMethod = s.buyMethod.next();
			changed(true);
		}, "Buy order: place an order and wait (cheaper). Instant: instabuy now (walks the real order book)."));
		sellButton = addRenderableWidget(toggle(1, tW, y, b -> {
			s.sellMethod = s.sellMethod.next();
			changed(true);
		}, "Sell offer: list and wait (higher price). Instant: instasell to buy orders now."));
		taxButton = addRenderableWidget(toggle(2, tW, y, b -> {
			s.taxPercent = nextOf(TAX_STEPS, s.taxPercent);
			changed(true);
		}, "Bazaar sales tax. 1.25% by default; 1.125% / 1% with the Bazaar Flipper account upgrades."));
		sortButton = addRenderableWidget(toggle(3, tW, y, b -> {
			s.sort = s.sort.next();
			changed(true);
		}, "Sort order of the results."));
		safeButton = addRenderableWidget(toggle(4, tW, y, b -> {
			cycleSafety();
			changed(true);
		}, "Manipulation guard. Strict/Normal/Loose hide flips above risk 30/50/70 and cap prices that moved away from "
				+ "their recent median. Off shows raw prices (flags are still shown)."));
		extraButton = addRenderableWidget(toggle(5, tW, y, b -> {
			if (s.mode == FlipMode.FORGE) {
				s.forgeSlots = s.forgeSlots % 7 + 1;
			} else if (s.mode == FlipMode.CRAFT) {
				s.npcIngredients = !s.npcIngredients;
			}
			changed(true);
		}, "Forge: number of forge slots you use. Craft: allow buying ingredients from NPC shops."));
		y += 22;

		tableTop = y + 12;
		detailLines = height < 300 ? 3 : 5;
		int footerY = height - 24;
		detailsTop = footerY - 4 - detailLines * 10 - 4;
		tableBottom = detailsTop - 4;

		// Footer
		int fW = (panelW - GAP * 5) / 6;
		addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> mod.data().refreshNow())
				.pos(left, footerY).size(fW, 20).tooltip(Tooltip.create(Component.literal("Fetch fresh Bazaar data now."))).build());
		hudButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			s.hudEnabled = !s.hudEnabled;
			changed(true);
		}).pos(left + (fW + GAP), footerY).size(fW, 20)
				.tooltip(Tooltip.create(Component.literal("Show the top flips on screen while playing."))).build());
		alertButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			s.alertsEnabled = !s.alertsEnabled;
			changed(true);
		}).pos(left + 2 * (fW + GAP), footerY).size(fW, 20)
				.tooltip(Tooltip.create(Component.literal("Chat message when a new best flip above "
						+ Coins.compact(s.alertMinProfitPerHour) + "/h appears (threshold in config/bazaarflipper/config.json)."))).build());
		historyButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			s.historyWindowHours = nextOf(HISTORY_STEPS, s.historyWindowHours);
			changed(true);
		}).pos(left + 3 * (fW + GAP), footerY).size(fW, 20)
				.tooltip(Tooltip.create(Component.literal("How much price history the manipulation guard uses for its medians."))).build());
		chatButton = addRenderableWidget(Button.builder(Component.literal("To chat"), b -> printSelected())
				.pos(left + 4 * (fW + GAP), footerY).size(fW, 20)
				.tooltip(Tooltip.create(Component.literal("Print the selected flip's full breakdown to chat."))).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.pos(left + 5 * (fW + GAP), footerY).size(fW, 20).build());

		refreshLabels();
	}

	private void numberBox(int index, int boxW, int y, String label, String initial, DoublePredicate valid, DoubleConsumer setter, String help) {
		EditBox box = new EditBox(font, left + index * (boxW + GAP), y, boxW, 16, Component.literal(label));
		box.setMaxLength(24);
		box.setValue(initial);
		box.setResponder(text -> {
			double v = Coins.parse(text);
			if (Double.isNaN(v) || !valid.test(v)) {
				box.setTextColor(C_BAD);
			} else {
				box.setTextColor(C_VALID);
				setter.accept(v);
				changed(false);
			}
		});
		box.setTooltip(Tooltip.create(Component.literal(help)));
		addRenderableWidget(box);
	}

	private Button toggle(int index, int w, int y, Button.OnPress onPress, String help) {
		return Button.builder(Component.empty(), onPress)
				.pos(left + index * (w + GAP), y)
				.size(w, 16)
				.tooltip(Tooltip.create(Component.literal(help)))
				.build();
	}

	private void cycleSafety() {
		if (!s.safeMode) {
			s.safeMode = true;
			s.maxRisk = RISK_STEPS[0];
			return;
		}
		for (int i = 0; i < RISK_STEPS.length; i++) {
			if (s.maxRisk <= RISK_STEPS[i]) {
				if (i == RISK_STEPS.length - 1) {
					s.safeMode = false;
				} else {
					s.maxRisk = RISK_STEPS[i + 1];
				}
				return;
			}
		}
		s.safeMode = false;
	}

	private String safetyLabel() {
		if (!s.safeMode) {
			return "Guard: Off";
		}
		if (s.maxRisk <= 30) {
			return "Guard: Strict";
		}
		if (s.maxRisk <= 50) {
			return "Guard: Normal";
		}
		return "Guard: Loose";
	}

	private static double nextOf(double[] steps, double current) {
		for (int i = 0; i < steps.length; i++) {
			if (Math.abs(steps[i] - current) < 1e-9) {
				return steps[(i + 1) % steps.length];
			}
		}
		return steps[0];
	}

	private void changed(boolean labels) {
		if (labels) {
			refreshLabels();
		}
		mod.data().requestRecompute();
	}

	private void refreshLabels() {
		for (int i = 0; i < modeButtons.size(); i++) {
			modeButtons.get(i).active = FlipMode.values()[i] != s.mode;
		}
		boolean buysOnBazaar = s.mode != FlipMode.NPC_TO_BAZAAR;
		boolean sellsOnBazaar = s.mode != FlipMode.BAZAAR_TO_NPC;
		buyButton.setMessage(Component.literal(buysOnBazaar ? "Buy: " + s.buyMethod.label : "Buy: NPC"));
		buyButton.active = buysOnBazaar;
		sellButton.setMessage(Component.literal(sellsOnBazaar ? "Sell: " + (s.sellMethod == TradeMethod.ORDER ? "Offer" : "Instant") : "Sell: NPC"));
		sellButton.active = sellsOnBazaar;
		taxButton.setMessage(Component.literal("Tax: " + trim(s.taxPercent) + "%"));
		sortButton.setMessage(Component.literal("Sort: " + s.sort.label));
		safeButton.setMessage(Component.literal(safetyLabel()));
		switch (s.mode) {
			case FORGE -> {
				extraButton.setMessage(Component.literal("Slots: " + s.forgeSlots));
				extraButton.active = true;
			}
			case CRAFT -> {
				extraButton.setMessage(Component.literal("NPC ingr: " + (s.npcIngredients ? "On" : "Off")));
				extraButton.active = true;
			}
			default -> {
				extraButton.setMessage(Component.literal("-"));
				extraButton.active = false;
			}
		}
		hudButton.setMessage(Component.literal("HUD: " + (s.hudEnabled ? "On" : "Off")));
		alertButton.setMessage(Component.literal("Alerts: " + (s.alertsEnabled ? "On" : "Off")));
		historyButton.setMessage(Component.literal("History: " + trim(s.historyWindowHours) + "h"));
	}

	// ---------------------------------------------------------------------------------------------- rendering

	private List<FlipResult> flips() {
		DataService.Results r = mod.data().results();
		return r == null ? List.of() : r.flips();
	}

	private int visibleRows() {
		return Math.max(1, (tableBottom - tableTop) / ROW_H);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		chatButton.active = selected() != null;

		// Title and status line
		graphics.text(font, "Bazaar Flipper", left, 6, C_TITLE, true);
		String[] status = statusLine();
		int statusColor = status[1].equals("error") ? C_BAD : status[1].equals("warn") ? C_WARN : C_MUTED;
		String st = truncate(font, status[0], panelW - font.width("Bazaar Flipper") - 10);
		graphics.text(font, st, left + panelW - font.width(st), 6, statusColor, true);

		// Input labels
		int boxW = (panelW - GAP * 4) / 5;
		String[] labels = {"Budget", "Max items/h", "Min volume/wk", "Market share %", "Search"};
		for (int i = 0; i < labels.length; i++) {
			graphics.text(font, labels[i], left + i * (boxW + GAP) + 1, labelY, C_LABEL, false);
		}

		// Table
		int hx = left;
		int hy = tableTop - 12;
		graphics.fill(left, hy, left + panelW, tableBottom, C_PANEL);
		graphics.fill(left, hy, left + panelW, tableTop - 1, C_HEADER);
		int[] col = columns();
		graphics.text(font, "Item", hx + 4, hy + 2, C_LABEL, false);
		rightText(graphics, "Profit/item", col[0], hy + 2, C_LABEL);
		rightText(graphics, "Items/h", col[1], hy + 2, C_LABEL);
		rightText(graphics, "Profit/h", col[2], hy + 2, C_LABEL);
		rightText(graphics, "Capital", col[3], hy + 2, C_LABEL);
		rightText(graphics, "Risk", col[4], hy + 2, C_LABEL);

		List<FlipResult> flips = flips();
		int rows = visibleRows();
		scroll = Math.max(0, Math.min(scroll, Math.max(0, flips.size() - rows)));
		FlipResult hovered = null;
		if (flips.isEmpty()) {
			String msg = emptyMessage();
			graphics.text(font, msg, left + (panelW - font.width(msg)) / 2, tableTop + 20, C_MUTED, false);
		}
		for (int i = 0; i < rows && scroll + i < flips.size(); i++) {
			FlipResult f = flips.get(scroll + i);
			int ry = tableTop + i * ROW_H;
			boolean isHover = mouseX >= left && mouseX < left + panelW && mouseY >= ry && mouseY < ry + ROW_H;
			if (f.id().equals(selectedId)) {
				graphics.fill(left, ry, left + panelW, ry + ROW_H, C_SELECTED);
			} else if (isHover) {
				graphics.fill(left, ry, left + panelW, ry + ROW_H, C_HOVER);
			}
			if (isHover) {
				hovered = f;
			}
			String rank = (scroll + i + 1) + ".";
			graphics.text(font, rank, hx + 4, ry + 2, C_MUTED, false);
			int nameX = hx + 4 + font.width("000.");
			graphics.text(font, truncate(font, f.name(), col[0] - font.width("000.00k") - nameX - 4), nameX, ry + 2, C_TEXT, false);
			rightText(graphics, Coins.compact(f.profitPerItem()), col[0], ry + 2, C_TEXT);
			rightText(graphics, Coins.compact(f.itemsPerHour()), col[1], ry + 2, C_TEXT);
			rightText(graphics, Coins.compact(f.profitPerHour()), col[2], ry + 2, C_GOOD);
			rightText(graphics, Coins.compact(f.capitalNeeded()), col[3], ry + 2, C_MUTED);
			rightText(graphics, Integer.toString(f.risk()), col[4], ry + 2, riskColor(f.risk()));
		}
		if (flips.size() > rows) {
			int trackH = tableBottom - tableTop;
			int barH = Math.max(8, trackH * rows / flips.size());
			int barY = tableTop + (trackH - barH) * scroll / Math.max(1, flips.size() - rows);
			graphics.fill(left + panelW - 3, barY, left + panelW - 1, barY + barH, 0xAAFFFFFF);
		}

		// Details of the selected (or hovered) flip
		graphics.fill(left, detailsTop, left + panelW, detailsTop + detailLines * 10 + 4, C_PANEL);
		FlipResult shown = selected() != null ? selected() : hovered;
		if (shown == null) {
			graphics.text(font, truncate(font, "Hover or click a row for the full breakdown. Sell prices are capped at their recent median", panelW - 8),
					left + 4, detailsTop + 3, C_MUTED, false);
			graphics.text(font, truncate(font, "and buy prices floored, so pumps, dumps and bait orders can't fake a profit.", panelW - 8),
					left + 4, detailsTop + 13, C_MUTED, false);
		} else {
			List<String> lines = shown.details();
			int n = Math.min(detailLines, lines.size());
			for (int i = 0; i < n; i++) {
				String line = lines.get(i);
				if (i == n - 1 && lines.size() > n) {
					line = "(+" + (lines.size() - n + 1) + " more lines: click \"To chat\") " + line;
				}
				int color = line.startsWith("⚠") ? C_WARN : i == 0 ? C_TITLE : C_TEXT;
				graphics.text(font, truncate(font, line, panelW - 8), left + 4, detailsTop + 3 + i * 10, color, false);
			}
		}
	}

	private int[] columns() {
		return new int[]{
				left + (int) (panelW * 0.56),
				left + (int) (panelW * 0.67),
				left + (int) (panelW * 0.80),
				left + (int) (panelW * 0.91),
				left + panelW - 6
		};
	}

	private void rightText(GuiGraphicsExtractor g, String text, int rightX, int y, int color) {
		g.text(font, text, rightX - font.width(text), y, color, false);
	}

	private static int riskColor(int risk) {
		return risk < 20 ? C_GOOD : risk < 45 ? C_WARN : C_BAD;
	}

	private String emptyMessage() {
		DataService data = mod.data();
		if (data.results() == null) {
			if (data.error() != null) {
				return truncate(font, data.error(), panelW - 10);
			}
			return "Loading Bazaar data...";
		}
		return "No profitable flips match your filters. Try a bigger budget, lower min volume or another mode.";
	}

	/** Returns {text, level} where level is "ok", "warn" or "error". */
	private String[] statusLine() {
		DataService data = mod.data();
		BazaarSnapshot snap = data.bazaar();
		if (snap == null) {
			return new String[]{data.error() != null ? data.error() : "Connecting to the Hypixel API...", data.error() != null ? "error" : "ok"};
		}
		long ageSec = Math.max(0, (System.currentTimeMillis() - snap.fetchedAt()) / 1000);
		int samples = data.history().maxSamples();
		String base = String.format(Locale.ROOT, "%d flips · data %s old", flips().size(), FlipCalculator.formatDuration(ageSec));
		if (data.error() != null) {
			return new String[]{data.error() + " · " + base, "warn"};
		}
		if (samples < s.minHistorySamples) {
			return new String[]{base + " · building history " + samples + "/" + s.minHistorySamples, "warn"};
		}
		return new String[]{base + " · history " + samples + " samples", "ok"};
	}

	private FlipResult selected() {
		if (selectedId == null) {
			return null;
		}
		for (FlipResult f : flips()) {
			if (f.id().equals(selectedId)) {
				return f;
			}
		}
		return null;
	}

	/** Cuts {@code text} to {@code maxWidth} pixels, adding "..." when it was shortened. */
	public static String truncate(Font font, String text, int maxWidth) {
		if (maxWidth <= 0) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String dots = "...";
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) + font.width(dots) > maxWidth) {
			end--;
		}
		return text.substring(0, end) + dots;
	}

	private static String trim(double v) {
		return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%s", v);
	}

	// ---------------------------------------------------------------------------------------------- input

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY >= tableTop && mouseY < tableBottom && mouseX >= left && mouseX < left + panelW) {
			scroll -= (int) Math.signum(scrollY) * 3;
			scroll = Math.max(0, Math.min(scroll, Math.max(0, flips().size() - visibleRows())));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		double mx = event.x();
		double my = event.y();
		if (mx >= left && mx < left + panelW && my >= tableTop && my < tableBottom) {
			int idx = scroll + (int) ((my - tableTop) / ROW_H);
			List<FlipResult> flips = flips();
			if (idx >= 0 && idx < flips.size()) {
				String id = flips.get(idx).id();
				selectedId = id.equals(selectedId) ? null : id;
				return true;
			}
		}
		return false;
	}

	private void printSelected() {
		FlipResult f = selected();
		if (f == null) {
			return;
		}
		BazaarFlipperClient.chat(BazaarFlipperClient.describe(f));
		for (String line : f.details()) {
			BazaarFlipperClient.chat(Component.literal(line).withStyle(line.startsWith("⚠") ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		mod.config().save();
		super.onClose();
	}
}
