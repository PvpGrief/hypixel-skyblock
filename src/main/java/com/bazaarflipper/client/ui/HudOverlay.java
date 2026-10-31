package com.bazaarflipper.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.bazaarflipper.client.BazaarFlipperClient;
import com.bazaarflipper.client.DataService;
import com.bazaarflipper.core.Coins;
import com.bazaarflipper.core.calc.FlipResult;
import com.bazaarflipper.core.calc.FlipSettings;

/** Small always-on list of the current best flips. */
public final class HudOverlay {
	private static final int BG = 0x90000000;
	private static final int TITLE = 0xFFFFAA00;
	private static final int NAME = 0xFFFFFFFF;
	private static final int PROFIT = 0xFF55FF55;
	private static final int MUTED = 0xFFAAAAAA;

	private HudOverlay() {
	}

	private record Line(String left, String right, int leftColor) {
	}

	public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		BazaarFlipperClient mod = BazaarFlipperClient.get();
		Minecraft mc = Minecraft.getInstance();
		if (mod == null || mc.player == null || mc.gui.screen() instanceof FlipperScreen) {
			return;
		}
		FlipSettings s = mod.settings();
		if (!s.hudEnabled) {
			return;
		}
		Font font = mc.font;
		List<Line> lines = new ArrayList<>();
		DataService.Results r = mod.data().results();
		if (r == null) {
			lines.add(new Line(mod.data().error() != null ? "Bazaar data unavailable" : "Loading Bazaar...", "", MUTED));
		} else if (r.flips().isEmpty()) {
			lines.add(new Line("No flips match your filters", "", MUTED));
		} else {
			for (int i = 0; i < Math.min(s.hudLines, r.flips().size()); i++) {
				FlipResult f = r.flips().get(i);
				lines.add(new Line(FlipperScreen.truncate(font, f.name(), 110), "+" + Coins.compact(f.profitPerHour()) + "/h", NAME));
			}
		}
		String title = "Bazaar Flipper · " + (r != null ? r.settings().mode.label : s.mode.label);
		int width = font.width(title);
		for (Line l : lines) {
			width = Math.max(width, font.width(l.left) + (l.right.isEmpty() ? 0 : 8 + font.width(l.right)));
		}
		int x = Math.min(s.hudX, Math.max(0, graphics.guiWidth() - width - 6));
		int y = Math.min(s.hudY, Math.max(0, graphics.guiHeight() - (lines.size() + 1) * 10 - 6));
		graphics.fill(x, y, x + width + 6, y + (lines.size() + 1) * 10 + 4, BG);
		graphics.text(font, title, x + 3, y + 3, TITLE, true);
		int ly = y + 14;
		for (Line l : lines) {
			graphics.text(font, l.left, x + 3, ly, l.leftColor, true);
			if (!l.right.isEmpty()) {
				graphics.text(font, l.right, x + 3 + width - font.width(l.right), ly, PROFIT, true);
			}
			ly += 10;
		}
	}
}
