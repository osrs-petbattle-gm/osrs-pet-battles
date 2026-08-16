package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.item.EquipItemDef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Cosmetic (HEAD/FACE) equipping on {@link RosterManager}. The behaviour that matters here and
 * differs from held items: equipping a cosmetic never consumes it, yet it still can't be duplicated
 * — a cosmetic may be worn by at most as many pets as the player owns copies of it, and equipping
 * one that's fully out moves it. Uses the real bundled {@code items.json}: {@code wizard_hat} and
 * {@code blue_party_hat} (HEAD), {@code stick} (HELD). Content defines no FACE item at present, so
 * the tests that need one supply their own — see {@link #loadedManagerWithFaceCosmetic()}.
 */
public class RosterManagerCosmeticsTest
{
	private static final String SPECIES = "baby_mole";
	private static final String SPECIES2 = "pet_snakeling";
	private static final String SPECIES3 = "baby_chinchompa";
	/** The capstone quest that grants the blue party hat; its own constant is private to the manager. */
	private static final String CAPSTONE_QUEST_ID = "series_of_fortunate_events";
	/** A FACE cosmetic that exists only in this test's content; see loadedManagerWithFaceCosmetic. */
	private static final String FACE_ITEM = "test_goggles";

	private RosterManager loadedManager()
	{
		return loadedManager(PetDatabase.load(new ContentLoader(new Gson())));
	}

	private RosterManager loadedManager(PetDatabase db)
	{
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

	/**
	 * The real content plus one synthetic FACE cosmetic.
	 *
	 * <p>The shipped {@code items.json} currently defines no FACE item — the sunglasses were pulled
	 * because a front-facing icon doesn't read on a three-quarter chathead — but the slot is live in
	 * code, and slot routing is exactly where a copy-paste bug would hide. Supplying our own item
	 * keeps that path covered without putting one back in the game.
	 */
	private RosterManager loadedManagerWithFaceCosmetic()
	{
		Gson gson = new Gson();
		ContentLoader loader = new ContentLoader(gson);
		List<EquipItemDef> items = new ArrayList<>(loader.loadEquipItems());
		// No sprite: nothing here renders, and naming a real PNG would imply otherwise.
		items.add(gson.fromJson("{\"id\":\"" + FACE_ITEM + "\",\"name\":\"Test goggles\","
			+ "\"slot\":\"FACE\",\"price\":10}", EquipItemDef.class));
		return loadedManager(new PetDatabase(loader.loadSpecies(), loader.loadMoves(),
			loader.loadTrainers(), items, loader.loadQuests(), loader.loadTypeChart()));
	}

	@Test
	public void equippingACosmeticFillsItsOwnSlotAndConsumesNothing()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		PetInstance pet = roster.getPet(SPECIES);
		assertEquals("wizard_hat", pet.getHeadItemId());
		assertNull(pet.getFaceItemId());
		// Unlike a held item, the unit is not decremented — the wear limit is enforced by counting
		// wearers (below), not by moving stock, so the inventory still reads one hat owned.
		assertEquals(1, roster.itemCount("wizard_hat"));
		assertEquals(1, roster.itemCapacity("wizard_hat"));
	}

	@Test
	public void headAndFaceSlotsAreIndependent()
	{
		RosterManager roster = loadedManagerWithFaceCosmetic();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);
		roster.grantItem(FACE_ITEM, 1);

		// Each item routes to the slot it declares, and neither displaces the other.
		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertTrue(roster.setCosmetic(SPECIES, FACE_ITEM));
		PetInstance pet = roster.getPet(SPECIES);
		assertEquals("wizard_hat", pet.getHeadItemId());
		assertEquals(FACE_ITEM, pet.getFaceItemId());

		assertTrue(roster.clearCosmetic(SPECIES, EquipItemDef.Slot.FACE));
		assertEquals("wizard_hat", pet.getHeadItemId());
		assertNull(pet.getFaceItemId());
	}

	@Test
	public void shippedContentDefinesNoFaceCosmetic()
	{
		// Guards the state this repo is deliberately in: the FACE slot is live in code but empty in
		// content. If a FACE item is added back, delete this test — and check it reads well on a
		// three-quarter chathead first (./gradlew previewCosmetics).
		RosterManager roster = loadedManager();
		for (EquipItemDef item : roster.ownedCosmetics())
		{
			assertEquals(EquipItemDef.Slot.HEAD, item.getSlot());
		}
		roster.advanceQuest(CAPSTONE_QUEST_ID, 99);
		roster.grantItem("wizard_hat", 1);
		assertEquals(2, roster.ownedCosmetics().size());
	}

	@Test
	public void oneCopyDressesOnePetAtATimeAndEquippingItAgainMovesIt()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("wizard_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertEquals(Collections.singletonList(SPECIES), roster.itemWearers("wizard_hat"));

		// One hat, so dressing the second pet takes it off the first rather than cloning it.
		assertTrue(roster.setCosmetic(SPECIES2, "wizard_hat"));
		assertNull(roster.getPet(SPECIES).getHeadItemId());
		assertEquals("wizard_hat", roster.getPet(SPECIES2).getHeadItemId());
		assertEquals(Collections.singletonList(SPECIES2), roster.itemWearers("wizard_hat"));
		// Still nothing consumed: the hat is owned exactly once throughout.
		assertEquals(1, roster.itemCount("wizard_hat"));

		// Freeing the slot frees the copy, so the first pet can have it back.
		assertTrue(roster.clearCosmetic(SPECIES2, EquipItemDef.Slot.HEAD));
		assertTrue(roster.itemWearers("wizard_hat").isEmpty());
		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertEquals("wizard_hat", roster.getPet(SPECIES).getHeadItemId());
	}

	@Test
	public void ownedQuantityRaisesHowManyPetsCanWearIt()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("wizard_hat", 2);
		assertEquals(2, roster.itemCapacity("wizard_hat"));

		// Two hats bought, two pets dressed — the second equip displaces nobody.
		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertTrue(roster.setCosmetic(SPECIES2, "wizard_hat"));
		assertEquals("wizard_hat", roster.getPet(SPECIES).getHeadItemId());
		assertEquals("wizard_hat", roster.getPet(SPECIES2).getHeadItemId());
		assertEquals(Arrays.asList(SPECIES, SPECIES2), roster.itemWearers("wizard_hat"));

		// A third pet with only two hats owned takes one off the longest-standing wearer.
		roster.unlock(SPECIES3);
		assertTrue(roster.setCosmetic(SPECIES3, "wizard_hat"));
		assertNull(roster.getPet(SPECIES).getHeadItemId());
		assertEquals(Arrays.asList(SPECIES2, SPECIES3), roster.itemWearers("wizard_hat"));
	}

	@Test
	public void equippingIntoAnOccupiedSlotReplacesWithoutConsumingEither()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);
		roster.grantItem("blue_party_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertTrue(roster.setCosmetic(SPECIES, "blue_party_hat"));
		assertEquals("blue_party_hat", roster.getPet(SPECIES).getHeadItemId());
		// Neither was consumed, so the displaced hat is owned, unworn, and free to equip again.
		assertEquals(1, roster.itemCount("wizard_hat"));
		assertTrue(roster.ownsEquipItem("wizard_hat"));
		assertTrue(roster.itemWearers("wizard_hat").isEmpty());
	}

	@Test
	public void rejectsUnownedPetsUnownedItemsHeldItemsAndRepeats()
	{
		RosterManager roster = loadedManager();
		roster.grantItem("wizard_hat", 1);
		// Pet not unlocked yet.
		assertFalse(roster.setCosmetic(SPECIES, "wizard_hat"));

		roster.unlock(SPECIES);
		// Cosmetic the player doesn't own.
		assertFalse(roster.setCosmetic(SPECIES, "blue_party_hat"));
		// A HELD item is not a cosmetic and must not land in a cosmetic slot.
		roster.grantItem("stick", 1);
		assertFalse(roster.setCosmetic(SPECIES, "stick"));
		// Unknown id.
		assertFalse(roster.setCosmetic(SPECIES, "no_such_item"));
		// Nothing was written: a rejected equip must not even bring the pet instance into being.
		PetInstance beforeAnySuccess = roster.getPet(SPECIES);
		assertTrue(beforeAnySuccess == null || beforeAnySuccess.getHeadItemId() == null);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		// Already worn — no redundant save.
		assertFalse(roster.setCosmetic(SPECIES, "wizard_hat"));
	}

	@Test
	public void clearingAnEmptySlotIsANoOp()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		assertFalse(roster.clearCosmetic(SPECIES, EquipItemDef.Slot.HEAD));
		assertFalse(roster.clearCosmetic(SPECIES, EquipItemDef.Slot.FACE));
	}

	@Test
	public void bluePartyHatIsWearableOffDerivedOwnershipWithNoInventoryUnit()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		// The trophy is earned from the capstone, so it never enters the item inventory. Before the
		// quest is done it is neither owned nor equippable.
		assertEquals(0, roster.itemCount("blue_party_hat"));
		assertFalse(roster.ownsEquipItem("blue_party_hat"));
		assertFalse(roster.setCosmetic(SPECIES, "blue_party_hat"));

		roster.advanceQuest(CAPSTONE_QUEST_ID, 99);
		assertTrue(roster.ownsEquipItem("blue_party_hat"));
		assertTrue(roster.setCosmetic(SPECIES, "blue_party_hat"));
		assertEquals("blue_party_hat", roster.getPet(SPECIES).getHeadItemId());
		assertEquals(0, roster.itemCount("blue_party_hat"));
	}

	@Test
	public void theTrophyIsOneHatEvenThoughItHasNoInventoryUnit()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.advanceQuest(CAPSTONE_QUEST_ID, 99);

		// Derived ownership gives capacity 1 with nothing in the inventory to count, so the trophy
		// moves between pets exactly like a bought hat rather than dressing the whole team.
		assertEquals(0, roster.itemCount("blue_party_hat"));
		assertEquals(1, roster.itemCapacity("blue_party_hat"));

		assertTrue(roster.setCosmetic(SPECIES, "blue_party_hat"));
		assertTrue(roster.setCosmetic(SPECIES2, "blue_party_hat"));
		assertNull(roster.getPet(SPECIES).getHeadItemId());
		assertEquals(Collections.singletonList(SPECIES2), roster.itemWearers("blue_party_hat"));
	}

	@Test
	public void aHeldItemIsNeverAcceptedIntoACosmeticSlot()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("stick", 1);

		// Capacity and wearers are slot-agnostic — every item is counted the same way — but the slot
		// an item declares is still the only one it can go into.
		assertFalse(roster.setCosmetic(SPECIES, "stick"));
		assertTrue(roster.setHeldItem(SPECIES, "stick"));
		assertEquals(Collections.singletonList(SPECIES), roster.itemWearers("stick"));
		assertNull(roster.getPet(SPECIES).getHeadItemId());

		assertTrue(roster.itemWearers("no_such_item").isEmpty());
		assertEquals(0, roster.itemCapacity("wizard_hat"));
	}

	@Test
	public void ownedCosmeticsListsOnlyOwnedCosmeticsNeverHeldItems()
	{
		RosterManager roster = loadedManager();
		assertTrue(roster.ownedCosmetics().isEmpty());

		roster.grantItem("stick", 1);
		assertTrue(roster.ownedCosmetics().isEmpty());

		roster.grantItem("wizard_hat", 1);
		List<String> ids = roster.ownedCosmetics().stream()
			.map(EquipItemDef::getId).collect(Collectors.toList());
		assertEquals(1, ids.size());
		assertTrue(ids.contains("wizard_hat"));
	}
}
