package com.petbattles.ui;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ImageUtil;

/**
 * Loads and caches pet chathead renders bundled at
 * {@code /com/petbattles/chatheads/<speciesId>.png} (assets sourced from the OSRS
 * wiki, pre-oriented to face right). The battle scene draws the player's own pet as
 * a foreground chathead — closer to the viewer than the far, full-body opponent — to
 * give the fight a sense of depth. Missing chatheads return null so the scene falls
 * back to the pet's item icon; the feature ships whole even before every asset exists.
 */
@Slf4j
public class PetChatheads
{
	private static final String BASE = "/com/petbattles/chatheads/";

	// Cache holds the loaded image, or null once we've confirmed there's no resource.
	private final Map<String, BufferedImage> cache = new HashMap<>();

	/**
	 * The foreground chathead for this species id, or null if none is bundled.
	 */
	public BufferedImage chathead(String speciesId)
	{
		if (cache.containsKey(speciesId))
		{
			return cache.get(speciesId);
		}
		BufferedImage img = null;
		String path = BASE + speciesId + ".png";
		if (getClass().getResource(path) != null)
		{
			img = ImageUtil.loadImageResource(getClass(), path);
			log.debug("Loaded pet chathead {} ({}x{})", path,
				img != null ? img.getWidth() : -1, img != null ? img.getHeight() : -1);
		}
		else
		{
			log.debug("No pet chathead bundled at {}", path);
		}
		cache.put(speciesId, img);
		return img;
	}
}
