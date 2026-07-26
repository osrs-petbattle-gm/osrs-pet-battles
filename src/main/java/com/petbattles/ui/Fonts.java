package com.petbattles.ui;

import java.awt.Font;
import net.runelite.client.ui.FontManager;

/**
 * Shared fonts for the plugin's custom-drawn overlays, so titles and subtext read consistently
 * everywhere and can be swapped in one place. These use the RuneLite RuneScape fonts at their
 * NATIVE size: they are pixel fonts and render cleanly only at native size — deriving them to
 * other sizes (e.g. 15f/18f) gives uneven, gappy letter spacing. Bold reads as the title tier,
 * the regular font as the (slightly lighter, same-height) subtext tier.
 */
final class Fonts
{
	private Fonts()
	{
	}

	/** Primary / heading text: pet names, move names, headlines. */
	public static final Font TITLE = FontManager.getRunescapeBoldFont();
	/** Secondary text: stats, hints, HP numbers, type chips. */
	public static final Font SUBTEXT = FontManager.getRunescapeFont();
}
