package com.petbattles.battle;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.MoveDef;
import net.runelite.api.SoundEffectID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The pure move-to-sound matrix. Assertions run against the real bundled moves (whose
 * {@code animation} families are the matrix key); {@link BattleSoundManager#soundIdFor} is static
 * and side-effect free, so no live client is needed.
 */
public class BattleSoundManagerTest
{
	private final PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));

	private int soundFor(String moveId)
	{
		MoveDef move = db.move(moveId);
		assertTrue("no such move: " + moveId, move != null);
		return BattleSoundManager.soundIdFor(BattleEvent.moveUsed(0, move, "used"), move);
	}

	@Test
	public void animationFamiliesMapToTheirCue()
	{
		assertEquals(2515, soundFor("scratch"));      // slash family
		assertEquals(1933, soundFor("toxic_fang"));   // bite family
		assertEquals(220, soundFor("wind_strike"));   // swirl → wind strike
		assertEquals(207, soundFor("water_blast"));   // orb → water blast
		assertEquals(155, soundFor("ember"));         // fireball → fire blast
		assertEquals(585, soundFor("dragon_breath")); // breath → dragonfire
		assertEquals(162, soundFor("inferno"));       // explosion → fire wave
		assertEquals(168, soundFor("ice_barrage"));   // barrage → ice barrage
		assertEquals(2696, soundFor("dart_toss"));    // dart throw
		assertEquals(2695, soundFor("bolt_rack"));    // bolt → crossbow
	}

	@Test
	public void statusAndBuffFamiliesUseVerifiedConstants()
	{
		assertEquals(SoundEffectID.PRAYER_ACTIVATE_RAPID_HEAL, soundFor("lunar_grace"));    // sparkle
		assertEquals(SoundEffectID.PRAYER_ACTIVATE_ULTIMATE_STRENGTH, soundFor("war_cry")); // buff_aura
		assertEquals(SoundEffectID.PRAYER_DEACTIVE_VWOOP, soundFor("haunting_wail"));       // wail
	}

	@Test
	public void perMoveOverridesWinOverTheAnimationMatrix()
	{
		assertEquals(SoundEffectID.MINING_TINK, soundFor("pickaxe_poke")); // lunge → mining tink
		assertEquals(SoundEffectID.TREE_FALLING, soundFor("timber"));      // shockwave → tree falling
		assertEquals(174, soundFor("arcane_blast"));                       // bolt → magic bolt, not crossbow
		assertEquals(2487, soundFor("harpoon"));                           // dart → spear/harpoon
	}

	/** The player's ask: every real move must fire a sound — none may fall through to silence. */
	@Test
	public void everyBundledMoveMakesASound()
	{
		for (MoveDef move : db.allMoves())
		{
			assertTrue("move '" + move.getId() + "' is silent",
				BattleSoundManager.soundIdFor(BattleEvent.moveUsed(0, move, "used"), move) > 0);
		}
	}

	@Test
	public void missWhiffsAndNonMoveEventsAreSilent()
	{
		assertEquals(SoundEffectID.MAGIC_SPLASH_BOING,
			BattleSoundManager.soundIdFor(BattleEvent.of(BattleEvent.Type.MISSED, 0, "missed"), null));
		assertEquals(BattleSoundManager.SILENT,
			BattleSoundManager.soundIdFor(BattleEvent.damage(1, 10, 1.0, "hit"), null));
		assertEquals(BattleSoundManager.SILENT, BattleSoundManager.soundIdFor(null, null));
	}

	@Test
	public void volumePercentMapsToGameScale()
	{
		assertEquals(0, BattleSoundManager.scaleVolume(0));
		assertEquals(127, BattleSoundManager.scaleVolume(100));
		assertEquals(64, BattleSoundManager.scaleVolume(50));
		// Out-of-range values clamp rather than overshoot the 0–127 scale.
		assertEquals(0, BattleSoundManager.scaleVolume(-20));
		assertEquals(127, BattleSoundManager.scaleVolume(250));
	}
}
