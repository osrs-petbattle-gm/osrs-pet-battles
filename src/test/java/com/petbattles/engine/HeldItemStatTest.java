package com.petbattles.engine;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Held-item stat modifiers on {@link BattlePet}. A pet with base stats 50 at level 50 derives each
 * stat to exactly 100 ({@code statAtLevel(50,50)}), so a percent item lands on a clean number.
 */
public class HeldItemStatTest
{
	private static BattlePet pet(ItemEffect effect)
	{
		SpeciesDef sp = TestPets.species("t", PetType.MELEE, 50, 50, 50, 50);
		return new BattlePet(sp, "t", 50, Arrays.asList(TestPets.TACKLE), null, null, effect);
	}

	@Test
	public void multiplierAppliesOnlyToItsOwnStat()
	{
		ItemEffect atk = new ItemEffect(ItemEffect.Stat.ATK, 15);
		assertEquals(1.15, atk.multiplierFor(ItemEffect.Stat.ATK), 1e-9);
		assertEquals(1.0, atk.multiplierFor(ItemEffect.Stat.DEF), 1e-9);
		assertEquals(1.0, atk.multiplierFor(ItemEffect.Stat.SPD), 1e-9);
		assertEquals(1.0, atk.multiplierFor(ItemEffect.Stat.HP), 1e-9);
	}

	@Test
	public void noItemLeavesStatsUnmodified()
	{
		BattlePet plain = pet(null);
		assertEquals(100, plain.effectiveAtk());
		assertEquals(100, plain.effectiveDef());
		assertEquals(100, plain.effectiveSpd());
	}

	@Test
	public void atkItemBoostsOnlyAttack()
	{
		BattlePet plain = pet(null);
		BattlePet held = pet(new ItemEffect(ItemEffect.Stat.ATK, 15));
		assertEquals(115, held.effectiveAtk());
		// The other stats are untouched.
		assertEquals(plain.effectiveDef(), held.effectiveDef());
		assertEquals(plain.effectiveSpd(), held.effectiveSpd());
		assertEquals(plain.getMaxHp(), held.getMaxHp());
	}

	@Test
	public void defAndSpdItemsBoostTheirStat()
	{
		assertEquals(115, pet(new ItemEffect(ItemEffect.Stat.DEF, 15)).effectiveDef());
		assertEquals(115, pet(new ItemEffect(ItemEffect.Stat.SPD, 15)).effectiveSpd());
	}

	@Test
	public void hpItemBoostsMaxHpAndStartsFull()
	{
		BattlePet plain = pet(null);
		BattlePet held = pet(new ItemEffect(ItemEffect.Stat.HP, 12));
		assertEquals(Math.round(plain.getMaxHp() * 1.12), held.getMaxHp());
		assertTrue("HP item raises the ceiling", held.getMaxHp() > plain.getMaxHp());
		// A fresh battler (null startingHp) begins at its boosted full.
		assertEquals(held.getMaxHp(), held.getCurrentHp());
	}

	@Test
	public void hpItemBoostSurvivesLevelUp()
	{
		BattlePet plain = pet(null);
		BattlePet held = pet(new ItemEffect(ItemEffect.Stat.HP, 12));
		plain.growTo(60);
		held.growTo(60);
		// growTo recomputes max HP through the same held-item hook, so the boost still holds.
		assertEquals(Math.round(plain.getMaxHp() * 1.12), held.getMaxHp());
	}
}
