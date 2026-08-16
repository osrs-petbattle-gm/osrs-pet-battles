package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What {@link RosterManager#load()} does to a roster written by an older build.
 *
 * <p>Two things happen on load. A v1 blob is migrated: back then the inventory held only *spares*
 * because equipping a held item decremented it, so every worn copy is credited back to make the
 * count the true total. Then, at any version, every equip item is brought back within its capacity —
 * this is the half that catches a save where one amulet ended up on two pets, which the old
 * consume-on-equip model could produce and could not detect afterwards.
 */
public class RosterManagerEquipRepairTest
{
	private static final String SPECIES = "baby_mole";
	private static final String SPECIES2 = "pet_snakeling";

	/** A manager over a pre-seeded blob, as if it had just been read off disk. */
	private RosterManager managerOver(RosterStore.RosterData seed)
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		RosterStore store = new RosterStore(null, null)
		{
			private RosterStore.RosterData data = seed;

			@Override
			public RosterStore.RosterData load()
			{
				return data;
			}

			@Override
			public void save(RosterStore.RosterData d)
			{
				this.data = d;
			}
		};
		RosterManager roster = new RosterManager(db, store);
		roster.load();
		return roster;
	}

	private static PetInstance petHolding(String itemId)
	{
		PetInstance pet = new PetInstance();
		pet.setHeldItemId(itemId);
		return pet;
	}

	@Test
	public void v1SparesBecomeTotalsByCreditingWornCopies()
	{
		// A healthy v1 save: three sticks bought, one of them worn, so the blob recorded two spares.
		RosterStore.RosterData v1 = new RosterStore.RosterData();
		v1.v = 1;
		v1.ownedSpecies.add(SPECIES);
		v1.pets.put(SPECIES, petHolding("stick"));
		v1.itemInventory.put("stick", 2);

		RosterManager roster = managerOver(v1);
		assertEquals("the worn copy is credited back", 3, roster.itemCount("stick"));
		assertEquals("stick", roster.getPet(SPECIES).getHeldItemId());
		assertEquals(Collections.singletonList(SPECIES), roster.itemWearers("stick"));
	}

	@Test
	public void migrationRunsOnceAndLeavesAV2SaveAlone()
	{
		RosterStore.RosterData v1 = new RosterStore.RosterData();
		v1.v = 1;
		v1.ownedSpecies.add(SPECIES);
		v1.pets.put(SPECIES, petHolding("stick"));
		v1.itemInventory.put("stick", 1);

		RosterManager roster = managerOver(v1);
		assertEquals(2, roster.itemCount("stick"));
		// The blob was rewritten at v2, so a second load must not credit the worn copy again.
		roster.unload();
		roster.load();
		assertEquals(2, roster.itemCount("stick"));
	}

	@Test
	public void twoPetsSharingOneHeldItemAreCreditedAsTwoCopies()
	{
		// The shape the old consume-on-equip build could leave behind: two pets holding one amulet
		// with nothing in the inventory. After crediting, that reads as two owned and two worn — which
		// is consistent — so the repair leaves both alone.
		RosterStore.RosterData v1 = new RosterStore.RosterData();
		v1.v = 1;
		v1.ownedSpecies.addAll(Arrays.asList(SPECIES, SPECIES2));
		v1.pets.put(SPECIES, petHolding("amulet_of_the_rogue"));
		v1.pets.put(SPECIES2, petHolding("amulet_of_the_rogue"));

		RosterManager roster = managerOver(v1);
		assertEquals(2, roster.itemCount("amulet_of_the_rogue"));
		assertEquals(Arrays.asList(SPECIES, SPECIES2), roster.itemWearers("amulet_of_the_rogue"));
	}

	@Test
	public void wearersBeyondTheCountAreStrippedOldestFirst()
	{
		// A v2 save that is simply wrong: one amulet owned, two pets wearing it. The repair keeps the
		// most recent wearer, since the older one has had the longer run of it.
		RosterStore.RosterData v2 = new RosterStore.RosterData();
		v2.ownedSpecies.addAll(Arrays.asList(SPECIES, SPECIES2));
		v2.pets.put(SPECIES, petHolding("amulet_of_the_rogue"));
		v2.pets.put(SPECIES2, petHolding("amulet_of_the_rogue"));
		v2.itemInventory.put("amulet_of_the_rogue", 1);

		RosterManager roster = managerOver(v2);
		assertNull(roster.getPet(SPECIES).getHeldItemId());
		assertEquals("amulet_of_the_rogue", roster.getPet(SPECIES2).getHeldItemId());
		assertEquals(Collections.singletonList(SPECIES2), roster.itemWearers("amulet_of_the_rogue"));
	}

	@Test
	public void aCosmeticWornWithNoCopiesOwnedIsStripped()
	{
		// Cosmetics were never consumed, so they are not credited on migration — a hat worn with an
		// empty inventory is simply not owned, and the repair takes it off.
		RosterStore.RosterData v2 = new RosterStore.RosterData();
		v2.ownedSpecies.add(SPECIES);
		PetInstance pet = new PetInstance();
		pet.setHeadItemId("wizard_hat");
		v2.pets.put(SPECIES, pet);

		RosterManager roster = managerOver(v2);
		assertNull(roster.getPet(SPECIES).getHeadItemId());
	}

	@Test
	public void aConsistentSaveIsLeftExactlyAsItWas()
	{
		RosterStore.RosterData v2 = new RosterStore.RosterData();
		v2.ownedSpecies.addAll(Arrays.asList(SPECIES, SPECIES2));
		v2.pets.put(SPECIES, petHolding("stick"));
		v2.pets.put(SPECIES2, petHolding("stick"));
		v2.itemInventory.put("stick", 2);
		v2.itemInventory.put("wizard_hat", 1);

		RosterManager roster = managerOver(v2);
		assertEquals(2, roster.itemCount("stick"));
		assertEquals(1, roster.itemCount("wizard_hat"));
		assertEquals(Arrays.asList(SPECIES, SPECIES2), roster.itemWearers("stick"));
		assertTrue(roster.itemWearers("wizard_hat").isEmpty());
	}
}
