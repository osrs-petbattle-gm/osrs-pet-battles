package com.petbattles.item;

import com.petbattles.engine.ItemEffect;

/**
 * A battle equip item loaded from {@code items.json}: either a {@code HELD} item (a passive stat
 * modifier a pet carries) or a cosmetic ({@code HEAD}/{@code FACE}, drawn on the sprite, no combat
 * effect). Data-shaped like {@code SpeciesDef}/{@code MoveDef}.
 *
 * <p>Deliberately distinct from the two pre-existing "item" concepts: the {@link Item} key-item
 * enum (quest rewards like the Remote Battle Device) and {@code follower.HeldItemTracker}
 * (possession-gated pet unlocks). See the naming note in the roadmap.
 */
public class EquipItemDef
{
	public enum Slot
	{
		/** A battle item a pet holds; carries a stat {@link #effect}. */
		HELD,
		/** Cosmetic head accessory (drawn on the sprite; no effect). */
		HEAD,
		/** Cosmetic face accessory (drawn on the sprite; no effect). */
		FACE
	}

	/** JSON shape of a held item's stat effect (absent for cosmetics). */
	public static class EffectData
	{
		private ItemEffect.Stat stat;
		private int magnitude;

		public EffectData()
		{
		}

		public ItemEffect.Stat getStat()
		{
			return stat;
		}

		public int getMagnitude()
		{
			return magnitude;
		}
	}

	private String id;
	private String name;
	private Slot slot = Slot.HELD;
	// Icon basename under /com/petbattles/items/<sprite>.png (used by the later cosmetic-render slice).
	private String sprite;
	private String examine;
	// Present only for HELD stat items; null for cosmetics.
	private EffectData effect;

	public EquipItemDef()
	{
	}

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public Slot getSlot()
	{
		return slot == null ? Slot.HELD : slot;
	}

	public String getSprite()
	{
		return sprite;
	}

	public String getExamine()
	{
		return examine;
	}

	/**
	 * The passive stat modifier for a HELD item, or {@code null} for cosmetics (and any HELD item
	 * that declares no effect). A fresh {@link ItemEffect} is built per call from the JSON data.
	 */
	public ItemEffect getEffect()
	{
		return effect == null ? null : new ItemEffect(effect.getStat(), effect.getMagnitude());
	}

	/** Whether this is a cosmetic (drawn on the sprite, no combat effect) rather than a held item. */
	public boolean isCosmetic()
	{
		return getSlot() != Slot.HELD;
	}
}
