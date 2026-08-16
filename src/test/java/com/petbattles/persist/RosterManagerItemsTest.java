package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The equip-item inventory and held-item equipping on {@link RosterManager}. Uses the real bundled
 * {@code items.json} plus an in-memory {@link RosterStore}. Ids: {@code stick} (HELD),
 * {@code wizard_hat} (HEAD cosmetic).
 *
 * <p>The rule under test: the inventory counts every copy owned, worn or not, and an item may be
 * held by at most that many pets. Equipping consumes nothing — see
 * {@link RosterManagerEquipRepairTest} for what happens to a save that breaks the rule.
 */
public class RosterManagerItemsTest
{
	private static final String SPECIES = "baby_mole";
	private static final String SPECIES2 = "pet_snakeling";

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

	@Test
	public void equippingConsumesNothingAndTheCountStaysTheTotalOwned()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("stick", 2);
		assertTrue(roster.setHeldItem(SPECIES, "stick"));
		// The inventory is the total owned, worn copies included — nothing is decremented.
		assertEquals(2, roster.itemCount("stick"));
		assertEquals(2, roster.itemCapacity("stick"));
		assertEquals(Collections.singletonList(SPECIES), roster.itemWearers("stick"));
	}

	@Test
	public void oneCopyIsHeldByOnePetAndEquippingItAgainMovesIt()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("stick", 1);
		assertTrue(roster.setHeldItem(SPECIES, "stick"));

		// One stick, so arming the second pet takes it off the first rather than cloning the bonus.
		assertTrue(roster.setHeldItem(SPECIES2, "stick"));
		assertNull(roster.getPet(SPECIES).getHeldItemId());
		assertEquals("stick", roster.getPet(SPECIES2).getHeldItemId());
		assertEquals(Collections.singletonList(SPECIES2), roster.itemWearers("stick"));
		assertEquals(1, roster.itemCount("stick"));
	}

	@Test
	public void ownedQuantityIsHowManyPetsCanHoldIt()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("stick", 2);

		assertTrue(roster.setHeldItem(SPECIES, "stick"));
		assertTrue(roster.setHeldItem(SPECIES2, "stick"));
		// Two bought, two armed, neither displaced.
		assertEquals(Arrays.asList(SPECIES, SPECIES2), roster.itemWearers("stick"));
		assertEquals("stick", roster.getPet(SPECIES).getHeldItemId());
	}

	@Test
	public void unequippingFreesTheCopyWithoutChangingTheCount()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("stick", 1);
		roster.setHeldItem(SPECIES, "stick");
		assertEquals(1, roster.itemCount("stick"));

		assertTrue(roster.clearHeldItem(SPECIES));
		assertEquals("the count never moved", 1, roster.itemCount("stick"));
		assertTrue(roster.itemWearers("stick").isEmpty());
		// Freed, so the other pet takes it with nothing displaced.
		assertTrue(roster.setHeldItem(SPECIES2, "stick"));
	}

	@Test
	public void swappingReleasesThePreviousItemForAnotherPet()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("stick", 1);
		roster.grantItem("sturdy_bracer", 1);
		roster.setHeldItem(SPECIES, "stick");

		assertTrue(roster.setHeldItem(SPECIES, "sturdy_bracer"));
		assertEquals("sturdy_bracer", roster.getPet(SPECIES).getHeldItemId());
		// Both are still owned, and the displaced stick is now free for the other pet.
		assertEquals(1, roster.itemCount("stick"));
		assertEquals(1, roster.itemCount("sturdy_bracer"));
		assertTrue(roster.itemWearers("stick").isEmpty());
		assertTrue(roster.setHeldItem(SPECIES2, "stick"));
	}

	@Test
	public void givingAnItemAwayTakesItOffTheWearer()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("stick", 1);
		roster.setHeldItem(SPECIES, "stick");

		// Losing the only copy has to take it off the pet too, or the pet would hold what isn't owned.
		assertTrue(roster.takeItem("stick", 1));
		assertEquals(0, roster.itemCount("stick"));
		assertNull(roster.getPet(SPECIES).getHeldItemId());
	}
}
