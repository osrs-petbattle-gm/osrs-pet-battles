package com.petbattles.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Short, purely cosmetic "your pets are resting" toast played after a bank heal:
 * a zZz card with a sparkle burst, gone after a couple of seconds.
 */
public class RestOverlay extends Overlay
{
	private static final int DURATION_MS = 2500;
	private static final int WIDTH = 200;
	private static final int HEIGHT = 56;

	private volatile long startMs;

	public RestOverlay()
	{
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/**
	 * Start (or restart) the animation. Safe from any thread.
	 */
	public void play()
	{
		startMs = System.currentTimeMillis();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long elapsed = System.currentTimeMillis() - startMs;
		if (startMs == 0 || elapsed >= DURATION_MS)
		{
			return null;
		}
		float progress = elapsed / (float) DURATION_MS;
		float alpha = progress > 0.8f ? (1f - progress) / 0.2f : 1f;

		g.setColor(new Color(20, 24, 28, (int) (225 * alpha)));
		g.fillRoundRect(0, 0, WIDTH, HEIGHT, 10, 10);
		g.setColor(new Color(90, 75, 40, (int) (255 * alpha)));
		g.setStroke(new BasicStroke(2));
		g.drawRoundRect(0, 0, WIDTH, HEIGHT, 10, 10);

		g.setFont(FontManager.getRunescapeFont());
		g.setColor(new Color(230, 225, 210, (int) (255 * alpha)));
		g.drawString("Your pets are resting…", 14, 24);
		g.setFont(FontManager.getRunescapeBoldFont());
		g.setColor(new Color(240, 220, 120, (int) (255 * alpha)));
		// A drowsy zZz drifting up as the toast plays
		g.drawString("z", 150, 40 - (int) (6 * progress));
		g.drawString("Z", 160, 34 - (int) (10 * progress));
		g.drawString("z", 172, 28 - (int) (14 * progress));

		ParticleBurst.render(g, new Rectangle(0, 8, WIDTH, HEIGHT - 16),
			progress, 7, ParticleBurst.SPARKLE);
		return new Dimension(WIDTH, HEIGHT);
	}
}
