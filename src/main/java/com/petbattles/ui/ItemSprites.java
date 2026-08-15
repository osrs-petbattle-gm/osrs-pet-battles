package com.petbattles.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;

/**
 * Loads and caches the bundled equip-item art at {@code /com/petbattles/items/<sprite>.png} for
 * drawing onto pets.
 *
 * <p>Images come back <em>trimmed</em> to their opaque bounds. These are inventory icons, so each
 * sits in a differently-sized pocket of transparent padding; scaling the raw file to a target width
 * would make that padding part of the measurement and every cosmetic would land at its own
 * arbitrary size. Trimming first means a requested width is the width of the hat.
 */
@Slf4j
public class ItemSprites
{
	private static final String BASE = "/com/petbattles/items/";

	/** Alpha at or below this counts as padding, matching {@link ChatheadAnchors}. */
	private static final int ALPHA_FLOOR = 8;

	// Cache holds the trimmed image, or null once we've confirmed there's no resource.
	private final Map<String, BufferedImage> cache = new HashMap<>();

	/**
	 * The trimmed art for an item sprite name, or null if none is bundled (callers skip drawing).
	 */
	public synchronized BufferedImage sprite(String name)
	{
		if (name == null)
		{
			return null;
		}
		if (cache.containsKey(name))
		{
			return cache.get(name);
		}
		BufferedImage trimmed = null;
		String path = BASE + name + ".png";
		if (getClass().getResource(path) != null)
		{
			BufferedImage raw = ImageUtil.loadImageResource(getClass(), path);
			trimmed = raw == null ? null : trim(raw);
		}
		else
		{
			log.debug("No item sprite bundled at {}", path);
		}
		cache.put(name, trimmed);
		return trimmed;
	}

	/** Crop an image to its opaque bounds, or return it unchanged if it's blank or already tight. */
	private static BufferedImage trim(BufferedImage img)
	{
		int w = img.getWidth();
		int h = img.getHeight();
		int minX = w;
		int minY = h;
		int maxX = -1;
		int maxY = -1;
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((img.getRGB(x, y) >>> 24) <= ALPHA_FLOOR)
				{
					continue;
				}
				minX = Math.min(minX, x);
				maxX = Math.max(maxX, x);
				minY = Math.min(minY, y);
				maxY = y;
			}
		}
		if (maxX < 0 || (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1))
		{
			return img;
		}
		return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
	}
}
