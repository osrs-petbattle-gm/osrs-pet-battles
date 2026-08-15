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
	 * The foreground chathead for a species in its active metamorphosis form, ignoring growth stage.
	 */
	public BufferedImage chathead(String speciesId, String variantId)
	{
		return chathead(speciesId, variantId, null);
	}

	/**
	 * The foreground chathead for a species in its active form and growth stage. Resolution prefers
	 * the most specific asset and degrades gracefully:
	 * <ol>
	 *   <li>{@code <species>__<variant>__<stage>.png} — this colour/form at this stage
	 *   <li>{@code <species>__<variant>.png} — this colour/form (its adult art)
	 *   <li>{@code <species>__<stage>.png} — the base form at this stage
	 *   <li>{@code <species>.png} — the base chathead
	 * </ol>
	 * A same-colour adult (step 2) is preferred over a wrong-colour stage image, so a missing
	 * stage asset never shows the wrong colour. Null variant/stage simply skip their steps.
	 */
	public BufferedImage chathead(String speciesId, String variantId, String stageKey)
	{
		Chathead resolved = resolve(speciesId, variantId, stageKey);
		return resolved == null ? null : resolved.getImage();
	}

	/**
	 * As {@link #chathead(String, String, String)}, but reporting which key the art resolved from
	 * alongside it — cosmetic placement is measured per image and cached under that key, and a
	 * hand-tuned override can name it to correct one variant or growth stage on its own.
	 *
	 * @return the resolved chathead, or null if no art is bundled for this pet at all
	 */
	public Chathead resolve(String speciesId, String variantId, String stageKey)
	{
		if (variantId != null)
		{
			if (stageKey != null)
			{
				Chathead staged = at(speciesId + "__" + variantId + "__" + stageKey);
				if (staged != null)
				{
					return staged;
				}
			}
			Chathead variant = at(speciesId + "__" + variantId);
			if (variant != null)
			{
				return variant;
			}
		}
		if (stageKey != null)
		{
			Chathead baseStage = at(speciesId + "__" + stageKey);
			if (baseStage != null)
			{
				return baseStage;
			}
		}
		return at(speciesId);
	}

	/** The chathead bundled at this exact key, or null if there is none. */
	private Chathead at(String key)
	{
		BufferedImage img = load(key);
		return img == null ? null : new Chathead(key, img);
	}

	/** A resolved chathead: the art, and the key it was found under. */
	public static class Chathead
	{
		private final String key;
		private final BufferedImage image;

		Chathead(String key, BufferedImage image)
		{
			this.key = key;
			this.image = image;
		}

		/** The chathead key this art resolved from, e.g. {@code cat__black__kitten}. */
		public String getKey()
		{
			return key;
		}

		public BufferedImage getImage()
		{
			return image;
		}
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
