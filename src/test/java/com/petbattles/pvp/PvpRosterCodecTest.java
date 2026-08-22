package com.petbattles.pvp;

import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.Leveling;
import com.petbattles.engine.SpeciesDef;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The peer on the other end of a pet battle is another copy of this plugin with nothing between us,
 * so a hand-edited roster is the obvious way to cheat. These are the cases that must not get through
 * — and the honest one that must.
 */
public class PvpRosterCodecTest
{
	private static PetDatabase db;
	private static SpeciesDef species;
	private static String moveAtLevelOne;

	@BeforeClass
	public static void loadContent()
	{
		db = PetDatabase.load(new ContentLoader(new Gson()));
		// Any species with a level-1 move will do; the rules under test aren't species-specific.
		for (SpeciesDef candidate : db.allSpecies())
		{
			List<String> known = candidate.movesKnownFor(null, 1);
			if (!known.isEmpty() && candidate.getBase() != null)
			{
				species = candidate;
				moveAtLevelOne = known.get(0);
				break;
			}
		}
		assertNotNull("content should contain a species with a level-1 move", species);
	}

	private static WirePet honest()
	{
		return new WirePet(species.getId(), null, "Rex", 5, 0,
			Collections.singletonList(moveAtLevelOne), null, null, null);
	}

	@Test
	public void acceptsAnHonestTeam()
	{
		List<BattlePet> team = PvpRosterCodec.decodeTeam(Collections.singletonList(honest()), db);
		assertNotNull(team);
		assertEquals(1, team.size());
		assertEquals("Rex", team.get(0).getDisplayName());
		assertEquals(5, team.get(0).getLevel());
	}

	@Test
	public void zeroHpMeansFullyRestedRatherThanADeadPet()
	{
		BattlePet pet = PvpRosterCodec.decodeTeam(Collections.singletonList(honest()), db).get(0);
		assertEquals("0 on the wire is the roster's null HP: start at full",
			pet.getMaxHp(), pet.getCurrentHp());
	}

	@Test
	public void rejectsAnUnknownSpecies()
	{
		WirePet fake = new WirePet("not_a_real_pet", null, "Ghost", 5, 0,
			Collections.singletonList(moveAtLevelOne), null, null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(fake), db));
	}

	@Test
	public void rejectsAnImpossibleLevel()
	{
		WirePet overLevelled = new WirePet(species.getId(), null, "Rex", Leveling.MAX_LEVEL + 1, 0,
			Collections.singletonList(moveAtLevelOne), null, null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(overLevelled), db));
	}

	@Test
	public void rejectsAMoveTheSpeciesCannotLearn()
	{
		String foreign = null;
		for (com.petbattles.engine.MoveDef move : db.allMoves())
		{
			if (!species.movesKnownFor(null, Leveling.MAX_LEVEL).contains(move.getId()))
			{
				foreign = move.getId();
				break;
			}
		}
		assertNotNull("content should have a move this species never learns", foreign);
		WirePet cheat = new WirePet(species.getId(), null, "Rex", 5, 0,
			Collections.singletonList(foreign), null, null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(cheat), db));
	}

	@Test
	public void rejectsAMoveNotYetReachedAtThatLevel()
	{
		String lateMove = null;
		for (com.petbattles.engine.LearnsetEntry entry : species.getLearnset())
		{
			if (entry.getLevel() > 5)
			{
				lateMove = entry.getMove();
				break;
			}
		}
		if (lateMove == null)
		{
			return; // this species learns everything by level 5; nothing to prove here
		}
		WirePet cheat = new WirePet(species.getId(), null, "Rex", 5, 0,
			Collections.singletonList(lateMove), null, null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(cheat), db));
	}

	@Test
	public void rejectsMoreMovesThanAPetCanHold()
	{
		List<String> tooMany = new ArrayList<>(species.movesKnownFor(null, Leveling.MAX_LEVEL));
		if (tooMany.size() <= com.petbattles.engine.PetInstance.MAX_EQUIPPED_MOVES)
		{
			return;
		}
		WirePet cheat = new WirePet(species.getId(), null, "Rex", Leveling.MAX_LEVEL, 0,
			tooMany, null, null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(cheat), db));
	}

	@Test
	public void rejectsAnOversizedTeam()
	{
		List<WirePet> tooMany = Arrays.asList(honest(), honest(), honest(), honest());
		assertNull(PvpRosterCodec.decodeTeam(tooMany, db));
	}

	@Test
	public void rejectsTheSameSpeciesTwice()
	{
		assertNull(PvpRosterCodec.decodeTeam(Arrays.asList(honest(), honest()), db));
	}

	@Test
	public void rejectsAnEmptyTeam()
	{
		assertNull(PvpRosterCodec.decodeTeam(Collections.emptyList(), db));
		assertNull(PvpRosterCodec.decodeTeam(null, db));
	}

	@Test
	public void rejectsAnInventedHeldItem()
	{
		WirePet cheat = new WirePet(species.getId(), null, "Rex", 5, 0,
			Collections.singletonList(moveAtLevelOne), "amulet_of_winning", null, null);
		assertNull(PvpRosterCodec.decodeTeam(Collections.singletonList(cheat), db));
	}

	@Test
	public void clampsHpAboveTheMaximum()
	{
		WirePet inflated = new WirePet(species.getId(), null, "Rex", 5, 99999,
			Collections.singletonList(moveAtLevelOne), null, null, null);
		BattlePet pet = PvpRosterCodec.decodeTeam(Collections.singletonList(inflated), db).get(0);
		assertEquals(pet.getMaxHp(), pet.getCurrentHp());
	}

	@Test
	public void stripsMarkupAndControlCharactersFromPeerText()
	{
		// Battle lines reach the chatbox, which reads <col=…> tags; stripping the brackets leaves the
		// text inert without throwing away a name that merely happens to contain one.
		String cleaned = PvpRosterCodec.sanitise("<col=ff0000>hi</col>");
		assertNotNull(cleaned);
		assertTrue("no angle brackets survive", cleaned.indexOf('<') < 0 && cleaned.indexOf('>') < 0);
		assertTrue("the readable part survives", cleaned.contains("hi"));
		assertEquals("plain names are left alone", "Rex", PvpRosterCodec.sanitise("Rex"));
		assertNull(PvpRosterCodec.sanitise("   "));
		assertNull(PvpRosterCodec.sanitise(null));
		assertNull("absurdly long names are dropped, not truncated",
			PvpRosterCodec.sanitise(new String(new char[200]).replace('\0', 'x')));
	}

	@Test
	public void fallsBackToTheRealNameWhenTheNicknameIsUnusable()
	{
		WirePet nasty = new WirePet(species.getId(), null, "<<<>>>", 5, 0,
			Collections.singletonList(moveAtLevelOne), null, null, null);
		BattlePet pet = PvpRosterCodec.decodeTeam(Collections.singletonList(nasty), db).get(0);
		assertEquals(species.nameFor(null, 5), pet.getDisplayName());
		assertTrue(pet.getDisplayName().indexOf('<') < 0);
	}
}
