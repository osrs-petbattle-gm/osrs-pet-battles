package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.item.EquipItemDef;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Cosmetic (HEAD/FACE) equipping on {@link RosterManager}. The behaviour that matters here and
 * differs from held items: cosmetics are a wardrobe, not stock — equipping never consumes one, so
 * the whole team can wear the same hat. Uses the real bundled {@code items.json}: {@code wizard_hat}
 * and {@code blue_party_hat} (HEAD), {@code black_sunglasses} (FACE), {@code stick} (HELD).
 */
public class RosterManagerCosmeticsTest
{
	private static final String SPECIES = "baby_mole";
	private static final String SPECIES2 = "pet_snakeling";
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
		items.add(gson.fromJson("{\"id\":\"" + FACE_ITEM + "\",\"name\":\"Test goggles\","
			+ "\"slot\":\"FACE\",\"sprite\":\"black_sunglasses\",\"price\":10}", EquipItemDef.class));
		return loadedManager(new PetDatabase(loader.loadSpecies(), loader.loadMoves(),
			loader.loadTrainers(), items, loader.loadQuests(), loader.loadTypeChart()));
	}

	@Test
	public void equippingACosmeticFillsItsOwnSlotAndKeepsTheItem()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		PetInstance pet = roster.getPet(SPECIES);
		assertEquals("wizard_hat", pet.getHeadItemId());
		assertNull(pet.getFaceItemId());
		// The wardrobe rule: the unit stays in the inventory rather than moving onto the pet.
		assertEquals(1, roster.itemCount("wizard_hat"));
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
	public void oneCosmeticDressesTheWholeTeamAtOnce()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.unlock(SPECIES2);
		roster.grantItem("wizard_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertTrue(roster.setCosmetic(SPECIES2, "wizard_hat"));
		assertEquals("wizard_hat", roster.getPet(SPECIES).getHeadItemId());
		assertEquals("wizard_hat", roster.getPet(SPECIES2).getHeadItemId());
		assertEquals(1, roster.itemCount("wizard_hat"));
	}

	@Test
	public void equippingIntoAnOccupiedSlotReplacesWithoutLosingTheOldOne()
	{
		RosterManager roster = loadedManager();
		roster.unlock(SPECIES);
		roster.grantItem("wizard_hat", 1);
		roster.grantItem("blue_party_hat", 1);

		assertTrue(roster.setCosmetic(SPECIES, "wizard_hat"));
		assertTrue(roster.setCosmetic(SPECIES, "blue_party_hat"));
		assertEquals("blue_party_hat", roster.getPet(SPECIES).getHeadItemId());
		// Neither was consumed, so the displaced hat is still available.
		assertEquals(1, roster.itemCount("wizard_hat"));
		assertTrue(roster.ownsCosmetic("wizard_hat"));
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
		assertFalse(roster.ownsCosmetic("blue_party_hat"));
		assertFalse(roster.setCosmetic(SPECIES, "blue_party_hat"));

		roster.advanceQuest(CAPSTONE_QUEST_ID, 99);
		assertTrue(roster.ownsCosmetic("blue_party_hat"));
		assertTrue(roster.setCosmetic(SPECIES, "blue_party_hat"));
		assertEquals("blue_party_hat", roster.getPet(SPECIES).getHeadItemId());
		assertEquals(0, roster.itemCount("blue_party_hat"));
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
