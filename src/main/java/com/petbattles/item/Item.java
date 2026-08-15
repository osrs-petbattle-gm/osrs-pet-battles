package com.petbattles.item;

/**
 * A reward the player has earned and keeps — the plugin's equivalent of a key item. Items are
 * awarded by quests today and will later come from shop rewards; the Items panel lists whichever
 * ones the player currently holds. Each item's icon is bundled at
 * {@code /com/petbattles/items/<id>.png}. Ownership is not stored here — it's derived from the
 * roster state that granted the item (see the Items panel's owned-items check), so an item and the
 * thing it unlocks can never drift apart.
 */
public enum Item
{
	/**
	 * Professor Oddenstein's Remote Battle Device, awarded by completing "Where's the remote?"
	 * ({@link com.petbattles.persist.RosterManager#isRemoteBattlesUnlocked()}). Holding it lets the
	 * player challenge any trainer straight from the panel.
	 */
	REMOTE_BATTLE_DEVICE("remote_battle_device", "Remote Battle Device",
		"Professor Oddenstein's contraption. Challenge any trainer straight from the panel, "
			+ "without standing next to them in the world."),

	/**
	 * The clue wrung from Ambassador Gimblewap in "A Series of Fortunate Events" — instructions to
	 * hide a tax that funds Probita's insurance fees, signed in an elegant hand: "White Beard." Owned
	 * once that chapter is done.
	 */
	SEALED_ENVELOPE("sealed_envelope", "Sealed Envelope",
		"A letter with instructions to Gimblewap to sneak in a tax to pay Probita insurance fees, "
			+ "signed in an elegant hand: \"White Beard\"."),

	/**
	 * The reformed Wise Old Man's parting gift for finishing "A Series of Fortunate Events" — the one
	 * cosmetic no coin can buy. Owned once the capstone is complete.
	 */
	BLUE_PARTY_HAT("blue_party_hat", "Blue party hat",
		"An old thief's treasure, pressed into your hands. Prestige you fought for, not bought.");

	private final String id;
	private final String name;
	private final String description;

	Item(String id, String name, String description)
	{
		this.id = id;
		this.name = name;
		this.description = description;
	}

	/** The item with this id, or null if none matches. */
	public static Item byId(String id)
	{
		for (Item item : values())
		{
			if (item.id.equals(id))
			{
				return item;
			}
		}
		return null;
	}

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	/** What the item is and what holding it grants the player. */
	public String getDescription()
	{
		return description;
	}
}
