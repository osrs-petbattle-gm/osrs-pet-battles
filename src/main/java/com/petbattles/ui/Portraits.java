package com.petbattles.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;

/**
 * Loads and caches trainer chathead portraits bundled at
 * {@code /com/petbattles/portraits/<trainerId>.png} (assets sourced from the OSRS
 * wiki). Missing portraits return null so the challenge card can fall back to the
 * trainer's lead-pet icon — the feature ships whole even before every asset is added.
 */
@Slf4j
public class Portraits
{
	private static final String BASE = "/com/petbattles/portraits/";

	// Cache holds the loaded image, or null once we've confirmed there's no resource.
	private final Map<String, BufferedImage> cache = new HashMap<>();

	/**
	 * The chathead for this trainer id, or null if none is bundled.
	 */
	public BufferedImage portrait(String trainerId)
	{
		if (cache.containsKey(trainerId))
		{
			return cache.get(trainerId);
		}
		BufferedImage img = null;
		String path = BASE + trainerId + ".png";
		if (getClass().getResource(path) != null)
		{
			img = ImageUtil.loadImageResource(getClass(), path);
			log.debug("Loaded trainer portrait {} ({}x{})", path,
				img != null ? img.getWidth() : -1, img != null ? img.getHeight() : -1);
		}
		else
		{
			log.debug("No trainer portrait bundled at {}", path);
		}
		cache.put(trainerId, img);
		return img;
	}
}
