package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The equip-item inventory and held-item equipping on {@link RosterManager}. Uses the real bundled
 * {@code items.json} plus an in-memory {@link RosterStore}. Ids: {@code stick} (HELD),
 * {@code wizard_hat} (HEAD cosmetic).
 */
public class RosterManagerItemsTest
{
	private static final String SPECIES = "baby_mole";

	private RosterManager loadedManager()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		RosterStore store = new RosterStore(null, null)
		{
			private RosterData data = new RosterData();

			@Override
			public RosterData load()
			{
				return data;
			}

			@Override
			public void save(RosterData d)
			{
				this.data = d;
			}
		};
		RosterManager roster = new RosterManager(db, store);
		roster.load();
		return roster;
	}

	@Test
	public void inventoryStartsEmpty()
	{
		RosterManager roster = loadedManager();
		assertEquals(0, roster.itemCount("stick"));
		assertFalse(roster.hasItem("stick"));
		assertTrue(roster.getItemInventory().isEmpty());
	}

	@Test
	public void grantAccumulatesAndRejectsUnknownOrNonPositive()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.grantItem("stick", 2));
		assertEquals(2, roster.itemCount("stick"));
		assertTrue(roster.grantItem("stick", 1));
		assertEquals(3, roster.itemCount("stick"));
		// Unknown id and non-positive counts are rejected.
		assertFalse(roster.grantItem("no_such_item", 1));
		assertFalse(roster.grantItem("stick", 0));
		assertFalse(roster.grantItem("stick", -1));
		assertEquals(3, roster.itemCount("stick"));
	}

	@Test
	public void takeRemovesOnlyWhenEnoughAndDropsKeyAtZero()
	{
		RosterManager roster = loadedManager();
		roster.grantItem("stick", 2);
		assertFalse("cannot take more than owned", roster.takeItem("stick", 3));
		assertEquals(2, roster.itemCount("stick"));
		assertTrue(roster.takeItem("stick", 1));
		assertEquals(1, roster.itemCount("stick"));
		assertTrue(roster.takeItem("stick", 1));
		assertEquals(0, roster.itemCount("stick"));
		assertFalse(roster.hasItem("stick"));
		assertTrue("key dropped at zero", roster.getItemInventory().isEmpty());
	}

	@Test
	public void equipRequiresOwnedPetOwnedHeldItem()
	{
		RosterManager roster = loadedManager();
		// Not owned yet -> cannot equip.
		assertFalse(roster.setHeldItem(SPECIES, "stick"));

		roster.unlock(SPECIES);
		// Owned pet, but the item isn't in the inventory.
		assertFalse(roster.setHeldItem(SPECIES, "stick"));

		roster.grantItem("stick", 1);
		assertTrue(roster.setHeldItem(SPECIES, "stick"));
		assertEquals("stick", roster.getPet(SPECIES).getHeldItemId());

		// Re-equipping the same item is a no-op.
		assertFalse(roster.setHeldItem(SPECIES, "stick"));
	}

	@Test
	public void cannotEquipCosmeticOrUnknownAsHeld()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);
		// wizard_hat is a HEAD cosmetic, not a HELD item.
		assertFalse(roster.setHeldItem(SPECIES, "wizard_hat"));
		assertFalse(roster.setHeldItem(SPECIES, "no_such_item"));
		// Both equips bailed before the pet record was lazily created — nothing was equipped.
		assertNull(roster.getPet(SPECIES));
	}

	@Test
	public void clearHeldItemRemovesIt()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("stick", 1);
		roster.setHeldItem(SPECIES, "stick");

		assertTrue(roster.clearHeldItem(SPECIES));
		assertNull(roster.getPet(SPECIES).getHeldItemId());
		// Nothing to clear now.
		assertFalse(roster.clearHeldItem(SPECIES));
	}
}
