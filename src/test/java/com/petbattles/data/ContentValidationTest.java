package com.petbattles.data;

import com.google.gson.Gson;
import com.petbattles.engine.EasterEggDef;
import com.petbattles.engine.GrowthStage;
import com.petbattles.engine.LearnsetEntry;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetType;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cross-reference validation of the bundled content: every id referenced anywhere must exist,
 * schemas must be complete, and the JSON type chart must match the engine's built-in default.
 */
public class ContentValidationTest
{
	private static PetDatabase db;

	@BeforeClass
	public static void load()
	{
		db = PetDatabase.load(new ContentLoader(new Gson()));
	}

	@Test
	public void contentIsNonEmpty()
	{
		assertTrue("at least 20 species", db.allSpecies().size() >= 20);
		assertTrue("at least 40 moves", db.allMoves().size() >= 40);
		assertTrue("at least 8 trainers", db.allTrainers().size() >= 8);
	}

	@Test
	public void everySpeciesIsComplete()
	{
		Set<String> ids = new HashSet<>();
		for (SpeciesDef s : db.allSpecies())
		{
			String ctx = "species " + s.getId();
			assertNotNull(ctx + " id", s.getId());
			assertTrue(ctx + " unique id", ids.add(s.getId()));
			assertNotNull(ctx + " name", s.getName());
			assertTrue(ctx + " itemId", s.getItemId() > 0);
			// Possession-gated pets (the cat) unlock from a held item, so follower npcIds
			// are optional for them
			if (!s.isItemUnlock())
			{
				assertFalse(ctx + " needs npcIds for follower detection", s.getNpcIds().isEmpty());
			}
			assertFalse(ctx + " needs at least one type", s.getTypes().isEmpty());
			assertTrue(ctx + " at most two types", s.getTypes().size() <= 2);
			assertNotNull(ctx + " base stats", s.getBase());
			assertTrue(ctx + " hp", s.getBase().getHp() > 0);
			assertTrue(ctx + " atk", s.getBase().getAtk() > 0);
			assertTrue(ctx + " def", s.getBase().getDef() > 0);
			assertTrue(ctx + " spd", s.getBase().getSpd() > 0);
			assertFalse(ctx + " needs a learnset", s.getLearnset().isEmpty());
		}
	}

	@Test
	public void learnsetsReferenceRealMovesAndStartAtLevelOne()
	{
		for (SpeciesDef s : db.allSpecies())
		{
			int prevLevel = 0;
			boolean hasLevelOne = false;
			for (LearnsetEntry e : s.getLearnset())
			{
				assertNotNull("species " + s.getId() + " references missing move " + e.getMove(),
					db.move(e.getMove()));
				assertTrue("species " + s.getId() + " learnset must be sorted by level",
					e.getLevel() >= prevLevel);
				prevLevel = e.getLevel();
				if (e.getLevel() == 1)
				{
					hasLevelOne = true;
				}
			}
			assertTrue("species " + s.getId() + " needs a level-1 move", hasLevelOne);
		}
	}

	@Test
	public void easterEggsReferenceRealMovesWithValidTriggers()
	{
		int eggCount = 0;
		for (SpeciesDef s : db.allSpecies())
		{
			for (EasterEggDef egg : s.getEasterEggs())
			{
				eggCount++;
				String ctx = "species " + s.getId() + " egg " + egg.getMove();
				assertNotNull(ctx + " references missing move", db.move(egg.getMove()));
				assertNotNull(ctx + " needs a trigger", egg.getTrigger());
				assertNotNull(ctx + " trigger kind", egg.getTrigger().getKind());
				assertNotNull(ctx + " needs a description", egg.getTrigger().getDesc());
				switch (egg.getTrigger().getKind())
				{
					case EMOTE:
						assertTrue(ctx + " needs animId", egg.getTrigger().getAnimId() > 0);
						break;
					case LOCATION:
						assertTrue(ctx + " needs regionId", egg.getTrigger().getRegionId() > 0);
						break;
					case STAT:
						assertNotNull(ctx + " needs skill", egg.getTrigger().getSkill());
						break;
				}
			}
		}
		assertTrue("at least 5 easter eggs authored", eggCount >= 5);
	}

	@Test
	public void growthStagesAreWellFormed()
	{
		for (SpeciesDef s : db.allSpecies())
		{
			int prev = 0;
			for (GrowthStage stage : s.getGrowthStages())
			{
				String ctx = "species " + s.getId() + " stage " + stage.getName();
				assertNotNull(ctx + " name", stage.getName());
				assertTrue(ctx + " itemId", stage.getItemId() > 0);
				assertTrue(ctx + " stages sorted by level", stage.getLevel() >= prev);
				prev = stage.getLevel();
			}
			if (!s.getGrowthStages().isEmpty())
			{
				assertEquals("species " + s.getId() + " first growth stage at level 1",
					1, s.getGrowthStages().get(0).getLevel());
				assertEquals(s.getGrowthStages().get(0).getName(), s.nameAt(1));
			}
		}
	}

	@Test
	public void variantsAreWellFormedAndOwnershipPreserved()
	{
		for (SpeciesDef s : db.allSpecies())
		{
			Set<String> variantIds = new HashSet<>();
			Set<Integer> ownable = new HashSet<>(s.getAllItemIds());
			for (SpeciesDef.Variant v : s.getVariants())
			{
				String ctx = "species " + s.getId() + " variant " + v.getId();
				assertNotNull(ctx + " id", v.getId());
				assertTrue(ctx + " unique id", variantIds.add(v.getId()));
				assertTrue(ctx + " at most two types", v.getTypes().size() <= 2);
				for (LearnsetEntry e : v.getLearnset())
				{
					assertNotNull(ctx + " references missing move " + e.getMove(), db.move(e.getMove()));
				}
				// Migration guard: a form id moved out of altItemIds into a variant must still be an
				// ownership signal (reachable from getAllItemIds) and resolve back to this species.
				if (v.getItemId() > 0)
				{
					assertTrue(ctx + " itemId is an ownership id", ownable.contains(v.getItemId()));
					assertEquals(ctx + " item resolves to species", s.getId(),
						db.speciesByItemId(v.getItemId()).getId());
					assertEquals(ctx + " item tags this variant", v.getId(), db.variantByItemId(v.getItemId()));
				}
				for (int id : v.getUnlockItemIds())
				{
					assertTrue(ctx + " unlock id is an ownership id", ownable.contains(id));
					assertEquals(ctx + " unlock resolves to species", s.getId(), db.speciesByItemId(id).getId());
				}
				for (int npcId : v.getNpcIds())
				{
					assertEquals(ctx + " npc resolves to species", s.getId(), db.speciesByNpcId(npcId).getId());
					assertEquals(ctx + " npc tags this variant", v.getId(), db.variantByNpcId(npcId));
				}
			}
		}
	}

	@Test
	public void kalphitePrincessSecondFormIsASpriteVariant()
	{
		SpeciesDef kp = db.species("kalphite_princess");
		assertNotNull(kp);
		assertEquals("second_form", db.variantByItemId(12654));
		// Ownership preserved after moving 12654 out of altItemIds into the variant
		assertEquals("kalphite_princess", db.speciesByItemId(12654).getId());
		// Sprite-only: same display name, the form's own icon
		assertEquals(kp.getName(), kp.nameFor("second_form", 30));
		assertEquals(12654, kp.itemIdFor("second_form", 30));
	}

	@Test
	public void catIsPossessionGatedAndEvolves()
	{
		SpeciesDef cat = db.species("cat");
		assertNotNull(cat);
		// Possession-gated (held item / follower), not an always-owned freebie
		assertTrue("cat unlocks by possession", cat.isItemUnlock());
		assertFalse("cat evolves", cat.getGrowthStages().isEmpty());
		assertEquals("Kitten", cat.nameAt(1));
		assertEquals(1555, cat.itemIdAt(1));
		assertEquals("Cat", cat.nameAt(15));
		assertEquals("Overgrown Cat", cat.nameAt(40));
		// Its held-item ids resolve back to the cat for the inventory/bank scan
		assertEquals("cat", db.speciesByItemId(1555).getId());
		assertEquals("cat", db.speciesByItemId(1561).getId());
	}

	@Test
	public void tradeInMessagesAreValidAndSpecific()
	{
		for (SpeciesDef s : db.allSpecies())
		{
			if (s.getTradeInMessage() == null)
			{
				continue;
			}
			// Compiling throws on an invalid regex; only possession pets can be traded away
			Pattern.compile(s.getTradeInMessage(), Pattern.CASE_INSENSITIVE);
			assertTrue("only possession-gated pets trade away: " + s.getId(), s.isItemUnlock());
		}
		// The cat's pattern catches the death-rune trade (either word order) but not
		// unrelated messages that mention only one of the two
		Pattern cat = Pattern.compile(db.species("cat").getTradeInMessage(), Pattern.CASE_INSENSITIVE);
		assertTrue(cat.matcher("You hand over the cat and receive 100 death runes.").find());
		assertTrue(cat.matcher("In exchange for death runes you give up your cat.").find());
		assertFalse(cat.matcher("You received 100 death runes.").find());
		assertFalse(cat.matcher("Your cat looks happy.").find());
	}

	@Test
	public void everyMoveIsComplete()
	{
		Set<String> ids = new HashSet<>();
		for (MoveDef m : db.allMoves())
		{
			String ctx = "move " + m.getId();
			assertTrue(ctx + " unique id", ids.add(m.getId()));
			assertNotNull(ctx + " name", m.getName());
			assertNotNull(ctx + " type", m.getType());
			assertTrue(ctx + " accuracy in range", m.getAccuracy() >= 0 && m.getAccuracy() <= 100);
			assertTrue(ctx + " effectChance in range", m.getEffectChance() >= 0 && m.getEffectChance() <= 100);
			if (m.isStatusMove())
			{
				assertTrue(ctx + " status moves need an effect",
					m.getEffect() != com.petbattles.engine.MoveEffect.NONE);
			}
		}
	}

	@Test
	public void trainersReferenceRealSpecies()
	{
		for (TrainerDef t : db.allTrainers())
		{
			assertNotNull("trainer " + t.getId() + " name", t.getName());
			assertFalse("trainer " + t.getId() + " needs a party", t.getParty().isEmpty());
			for (TrainerDef.PartyEntry entry : t.getParty())
			{
				assertNotNull("trainer " + t.getId() + " references missing species " + entry.getSpecies(),
					db.species(entry.getSpecies()));
				assertTrue("trainer " + t.getId() + " party levels valid",
					entry.getLevel() >= 1 && entry.getLevel() <= 99);
			}
		}
	}

	@Test
	public void bundledTrainerPortraitsAreRealPngs() throws java.io.IOException
	{
		// A random-event challenger must be an EASY fight (roadmap §1.2 / the cadence pool), and any
		// portrait shipped for a trainer must be an actual PNG (AGENTS.md), not a renamed JPEG/ICO.
		byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		int randomEventTrainers = 0;
		for (TrainerDef t : db.allTrainers())
		{
			if (t.isRandomEvent())
			{
				randomEventTrainers++;
				assertEquals("random-event trainer must be easy: " + t.getId(),
					TrainerDef.Difficulty.EASY, t.getDifficulty());
			}
			try (java.io.InputStream in =
				getClass().getResourceAsStream("/com/petbattles/portraits/" + t.getId() + ".png"))
			{
				if (in == null)
				{
					continue; // portrait optional — Portraits falls back to the lead-pet icon
				}
				byte[] header = new byte[8];
				assertEquals("portrait too small: " + t.getId(), 8, in.read(header));
				assertArrayEquals("portrait " + t.getId() + ".png is not a PNG", pngMagic, header);
			}
		}
		assertTrue("random-event trainers are authored", randomEventTrainers >= 8);
	}

	@Test
	public void noDuplicateItemOrNpcIdsAcrossSpecies()
	{
		Set<Integer> itemIds = new HashSet<>();
		Set<Integer> npcIds = new HashSet<>();
		for (SpeciesDef s : db.allSpecies())
		{
			for (int id : s.getAllItemIds())
			{
				assertTrue("item id " + id + " claimed twice (" + s.getId() + ")", itemIds.add(id));
			}
			for (int id : s.getNpcIds())
			{
				assertTrue("npc id " + id + " claimed twice (" + s.getId() + ")", npcIds.add(id));
			}
		}
	}

	@Test
	public void jsonTypeChartMatchesEngineDefault()
	{
		TypeChart json = db.getTypeChart();
		TypeChart builtin = new TypeChart();
		for (PetType atk : PetType.values())
		{
			for (PetType def : PetType.values())
			{
				assertEquals("chart mismatch at " + atk + " vs " + def,
					builtin.effectiveness(atk, def), json.effectiveness(atk, def), 0.001);
			}
		}
	}

	@Test
	public void everyTypeHasAtLeastOneSpecies()
	{
		Set<PetType> used = new HashSet<>();
		for (SpeciesDef s : db.allSpecies())
		{
			used.addAll(s.getTypes());
		}
		for (PetType t : PetType.values())
		{
			assertTrue("no species has type " + t, used.contains(t));
		}
	}
}
