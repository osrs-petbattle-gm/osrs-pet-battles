package com.petbattles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pet species loaded from species.json.
 */
public class SpeciesDef
{
	private String id;
	private String name;
	private int itemId;
	private List<Integer> altItemIds = new ArrayList<>();
	private List<Integer> npcIds = new ArrayList<>();
	private List<String> npcXpTags = new ArrayList<>();
	private List<PetType> types = new ArrayList<>();
	private Stats base;
	private List<LearnsetEntry> learnset = new ArrayList<>();
	private List<EasterEggDef> easterEggs = new ArrayList<>();
	// Unlocked by possessing the pet in-game (item in inventory/bank, or as a follower)
	// rather than via the collection log — for the OSRS cat, which isn't a log pet.
	private boolean itemUnlock;
	// Optional case-insensitive regex; when a game message matches it, the player has
	// traded this pet away in-game (e.g. a grown cat for death runes) and it is revoked.
	private String tradeInMessage;
	// Optional evolution stages (kitten -> cat -> ...); empty for non-growing species.
	private List<GrowthStage> growthStages = new ArrayList<>();
	// Which way the sprite art faces. Defaults true (most icons face left) so battle pets are
	// oriented toward their opponent: the player's pet is mirrored to face right, the enemy's
	// is left as-is to face the player. Set false in species.json for art that already faces right.
	private boolean spriteFacesLeft = true;
	// Optional metamorphosis forms (e.g. the three snakeling colours). A variant overrides only
	// what it names (sprite/name in tier 1; optionally types/base/learnset later) and falls through
	// to the species default for everything else, exactly like a GrowthStage. null = base form only.
	private List<Variant> variants = new ArrayList<>();

	/**
	 * A metamorphosis form of a species. Distinguished from the base form (and other variants) by
	 * its own item/npc ids, which the follower/inventory trackers observe to detect the active form.
	 * Overrides are all optional — an absent field falls through to the species default.
	 */
	public static class Variant
	{
		private String id;
		private String name;
		private int itemId;
		private List<Integer> npcIds = new ArrayList<>();
		// Item ids whose presence in the inventory/bank proves this form is the active one.
		private List<Integer> unlockItemIds = new ArrayList<>();
		// Boxed so "unset" (null) is distinguishable from an explicit false override.
		private Boolean spriteFacesLeft;
		private List<PetType> types = new ArrayList<>();
		private Stats base;
		private List<LearnsetEntry> learnset = new ArrayList<>();
		private List<GrowthStage> growthStages = new ArrayList<>();

		public String getId()
		{
			return id;
		}

		public String getName()
		{
			return name;
		}

		public int getItemId()
		{
			return itemId;
		}

		public List<Integer> getNpcIds()
		{
			return npcIds == null ? Collections.emptyList() : npcIds;
		}

		public List<Integer> getUnlockItemIds()
		{
			return unlockItemIds == null ? Collections.emptyList() : unlockItemIds;
		}

		public Boolean getSpriteFacesLeft()
		{
			return spriteFacesLeft;
		}

		public List<PetType> getTypes()
		{
			return types == null ? Collections.emptyList() : types;
		}

		public Stats getBase()
		{
			return base;
		}

		public List<LearnsetEntry> getLearnset()
		{
			return learnset == null ? Collections.emptyList() : learnset;
		}

		public List<GrowthStage> getGrowthStages()
		{
			return growthStages == null ? Collections.emptyList() : growthStages;
		}

		/**
		 * The variant-specific growth stage active at the given level, or null if this variant
		 * declares no stage art (in which case its flat name/itemId, then the base, apply).
		 */
		GrowthStage stageAt(int level)
		{
			GrowthStage current = null;
			for (GrowthStage stage : getGrowthStages())
			{
				if (stage.getLevel() <= level)
				{
					current = stage;
				}
			}
			return current;
		}
	}

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public int getItemId()
	{
		return itemId;
	}

	public List<Integer> getAltItemIds()
	{
		return altItemIds == null ? Collections.emptyList() : altItemIds;
	}

	public List<Integer> getNpcIds()
	{
		return npcIds == null ? Collections.emptyList() : npcIds;
	}

	public List<String> getNpcXpTags()
	{
		return npcXpTags == null ? Collections.emptyList() : npcXpTags;
	}

	public List<PetType> getTypes()
	{
		return types == null ? Collections.emptyList() : types;
	}

	public Stats getBase()
	{
		return base;
	}

	public List<LearnsetEntry> getLearnset()
	{
		return learnset == null ? Collections.emptyList() : learnset;
	}

	public List<EasterEggDef> getEasterEggs()
	{
		return easterEggs == null ? Collections.emptyList() : easterEggs;
	}

	/**
	 * Whether this pet is unlocked by possessing it in-game — the item in the player's
	 * inventory or bank, or the pet following them — instead of via the collection log.
	 */
	public boolean isItemUnlock()
	{
		return itemUnlock;
	}

	/**
	 * Case-insensitive regex whose match in a game message means this pet was traded away
	 * in-game and should be revoked, or null if the pet can't be traded away.
	 */
	public String getTradeInMessage()
	{
		return tradeInMessage;
	}

	public List<GrowthStage> getGrowthStages()
	{
		return growthStages == null ? Collections.emptyList() : growthStages;
	}

	/**
	 * Whether the sprite art faces left (the default). Used to orient battle pets toward their
	 * opponent: a left-facing pet is mirrored on the player's side to face right.
	 */
	public boolean isSpriteFacesLeft()
	{
		return spriteFacesLeft;
	}

	/**
	 * The growth stage active at the given level (the highest stage whose level threshold
	 * has been reached), or null if this species doesn't evolve.
	 */
	public GrowthStage stageAt(int level)
	{
		GrowthStage current = null;
		for (GrowthStage stage : getGrowthStages())
		{
			if (stage.getLevel() <= level)
			{
				current = stage;
			}
		}
		return current;
	}

	/**
	 * Display name at the given level: the active growth stage's name, else the base name.
	 */
	public String nameAt(int level)
	{
		GrowthStage stage = stageAt(level);
		return stage != null && stage.getName() != null ? stage.getName() : name;
	}

	/**
	 * Sprite item id at the given level: the active growth stage's item, else the base item.
	 */
	public int itemIdAt(int level)
	{
		GrowthStage stage = stageAt(level);
		return stage != null && stage.getItemId() > 0 ? stage.getItemId() : itemId;
	}

	public List<Variant> getVariants()
	{
		return variants == null ? Collections.emptyList() : variants;
	}

	/**
	 * The variant with this id, or null (including for a null id = base form).
	 */
	public Variant variant(String variantId)
	{
		if (variantId == null)
		{
			return null;
		}
		for (Variant v : getVariants())
		{
			if (variantId.equals(v.getId()))
			{
				return v;
			}
		}
		return null;
	}

	/**
	 * All item ids whose presence proves ownership: the base item, its alternates, and every
	 * variant's item + unlock ids. Folding the variant ids in keeps a form id counting as an
	 * unlock signal after it has been moved out of {@code altItemIds} into a variant (roadmap §0.2).
	 */
	public List<Integer> getAllItemIds()
	{
		// LinkedHashSet: stable order, and a variant's itemId may also appear in its unlockItemIds
		// (the sprite item is itself an ownership signal) — dedup so it isn't listed twice.
		java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
		ids.add(itemId);
		ids.addAll(getAltItemIds());
		for (Variant v : getVariants())
		{
			if (v.getItemId() > 0)
			{
				ids.add(v.getItemId());
			}
			ids.addAll(v.getUnlockItemIds());
		}
		return new ArrayList<>(ids);
	}

	/**
	 * Moves known at the given level (learnset entries at or below it), newest first.
	 */
	public List<String> movesKnownAt(int level)
	{
		List<String> moves = new ArrayList<>();
		for (LearnsetEntry e : getLearnset())
		{
			if (e.getLevel() <= level)
			{
				moves.add(e.getMove());
			}
		}
		Collections.reverse(moves);
		return moves;
	}

	// --- Variant-aware resolvers -------------------------------------------------------------
	// Every owned-pet display/combat site routes its name/sprite/type/stat/move lookup through
	// these, passing the pet's active variant id (null = base form). Resolution order is
	// "variant wins": a variant's own value (its stage art, then its flat value) takes precedence,
	// and only what the variant does not name falls through to the base form — mirroring how a
	// GrowthStage overrides only name + sprite. So while a variant is active the base growth stages
	// are bypassed; a variant may declare its own growthStages if it wants stage-specific art.

	/**
	 * Display name for the given variant at the given level.
	 */
	public String nameFor(String variantId, int level)
	{
		Variant v = variant(variantId);
		if (v != null)
		{
			GrowthStage stage = v.stageAt(level);
			if (stage != null && stage.getName() != null)
			{
				return stage.getName();
			}
			if (v.getName() != null)
			{
				return v.getName();
			}
		}
		return nameAt(level);
	}

	/**
	 * Sprite item id for the given variant at the given level.
	 */
	public int itemIdFor(String variantId, int level)
	{
		Variant v = variant(variantId);
		if (v != null)
		{
			GrowthStage stage = v.stageAt(level);
			if (stage != null && stage.getItemId() > 0)
			{
				return stage.getItemId();
			}
			if (v.getItemId() > 0)
			{
				return v.getItemId();
			}
		}
		return itemIdAt(level);
	}

	/**
	 * Types for the given variant (tier 2): the variant's own types if it declares any, else the
	 * species types.
	 */
	public List<PetType> typesFor(String variantId)
	{
		Variant v = variant(variantId);
		return v != null && !v.getTypes().isEmpty() ? v.getTypes() : getTypes();
	}

	/**
	 * Base stats for the given variant (tier 2): the variant's own stats if it declares them, else
	 * the species base.
	 */
	public Stats baseFor(String variantId)
	{
		Variant v = variant(variantId);
		return v != null && v.getBase() != null ? v.getBase() : getBase();
	}

	/**
	 * Sprite orientation for the given variant: its own override if set, else the species default.
	 */
	public boolean spriteFacesLeftFor(String variantId)
	{
		Variant v = variant(variantId);
		return v != null && v.getSpriteFacesLeft() != null ? v.getSpriteFacesLeft() : isSpriteFacesLeft();
	}

	/**
	 * Learnset for the given variant (tier 3): the variant's own learnset if it declares one, else
	 * the species learnset. (When a variant learnset lands, whether it layers additively over the
	 * base is a per-pet decision — see roadmap §0; today no variant declares one, so this is dormant.)
	 */
	public List<LearnsetEntry> learnsetFor(String variantId)
	{
		Variant v = variant(variantId);
		return v != null && !v.getLearnset().isEmpty() ? v.getLearnset() : getLearnset();
	}

	/**
	 * Moves known at the given level for the given variant, newest first.
	 */
	public List<String> movesKnownFor(String variantId, int level)
	{
		List<String> moves = new ArrayList<>();
		for (LearnsetEntry e : learnsetFor(variantId))
		{
			if (e.getLevel() <= level)
			{
				moves.add(e.getMove());
			}
		}
		Collections.reverse(moves);
		return moves;
	}
}
