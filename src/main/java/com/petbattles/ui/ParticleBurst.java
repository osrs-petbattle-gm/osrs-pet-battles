package com.petbattles.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * Stateless firework/sparkle burst: particles sit on parametric arcs driven purely by
 * an externally supplied 0→1 progress, so rendering is cheap and deterministic —
 * no per-frame state, no allocation beyond the loop locals.
 */
public final class ParticleBurst
{
	/** OSRS level-up firework palette. */
	public static final Color[] FIREWORKS = {
		new Color(255, 80, 60),
		new Color(255, 210, 60),
		new Color(90, 220, 90),
		new Color(90, 150, 255),
		new Color(240, 240, 240),
	};

	/** Soft white/gold sparkle, for heals and rests. */
	public static final Color[] SPARKLE = {
		new Color(255, 250, 210),
		new Color(255, 225, 130),
		new Color(220, 240, 255),
	};

	private ParticleBurst()
	{
	}

	/**
	 * Draw a burst centred on the target rect. Particles fly out on arcs with a little
	 * gravity droop and fade near the end. Same seed → same burst shape every frame.
	 */
	public static void render(Graphics2D g, Rectangle target, float progress, int seed, Color[] palette)
	{
		if (progress <= 0f || progress >= 1f)
		{
			return;
		}
		int cx = target.x + target.width / 2;
		int cy = target.y + target.height / 2;
		int count = 18;
		float alpha = progress > 0.6f ? 1f - (progress - 0.6f) / 0.4f : 1f;
		for (int i = 0; i < count; i++)
		{
			// Cheap deterministic per-particle variation from the seed
			int h = (seed * 31 + i) * 0x9E3779B9;
			double angle = 2 * Math.PI * i / count + ((h & 0xFF) / 255.0 - 0.5) * 0.5;
			double speed = 26 + ((h >>> 8) & 0x3F);
			double px = cx + Math.cos(angle) * speed * progress;
			double py = cy + Math.sin(angle) * speed * progress + 18 * progress * progress;
			Color base = palette[Math.floorMod(h >>> 16, palette.length)];
			g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), (int) (255 * alpha)));
			int size = ((h >>> 24) & 1) == 0 ? 3 : 2;
			g.fillRect((int) px, (int) py, size, size);
		}
	}
}
