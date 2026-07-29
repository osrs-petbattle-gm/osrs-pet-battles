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
	 * The foreground chathead for this species' base form, or null if none is bundled.
	 */
	public BufferedImage chathead(String speciesId)
	{
		return chathead(speciesId, null);
	}

	/**
	 * The foreground chathead for a species in its active metamorphosis form: prefers a
	 * variant-specific asset ({@code <species>__<variant>.png}) and falls back to the base
	 * ({@code <species>.png}). A null variant (base form) uses the base asset directly.
	 */
	public BufferedImage chathead(String speciesId, String variantId)
	{
		if (variantId != null)
		{
			BufferedImage variant = load(speciesId + "__" + variantId);
			if (variant != null)
			{
				return variant;
			}
		}
		return load(speciesId);
	}

	/** Load and cache the chathead at {@code <key>.png}, or null (cached) if none is bundled. */
	private BufferedImage load(String key)
	{
		if (cache.containsKey(key))
		{
			return cache.get(key);
		}
		BufferedImage img = null;
		String path = BASE + key + ".png";
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
		cache.put(key, img);
		return img;
	}
}
