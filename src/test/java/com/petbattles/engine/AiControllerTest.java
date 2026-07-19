package com.petbattles.engine;

import com.petbattles.engine.controller.AiController;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AiControllerTest
{
	private final TypeChart chart = new TypeChart();
	private final BattleEngine engine = new BattleEngine(chart);

	@Test
	public void easyAlwaysPicksALegalMove()
	{
		AiController ai = new AiController(TrainerDef.Difficulty.EASY, chart);
		BattleState state = newState();
		Random rng = new Random(9);
		for (int i = 0; i < 50; i++)
		{
			BattleAction action = ai.chooseAction(state, BattleState.ENEMY, rng);
			assertEquals(BattleAction.Kind.MOVE, action.getKind());
			int idx = action.getMoveIndex();
			assertTrue(idx >= 0 && idx < state.active(BattleState.ENEMY).getMoves().size());
		}
	}

	@Test
	public void mediumPicksSuperEffectiveMove()
	{
		// Enemy has Tackle (melee, neutral into melee) and Arrow (ranged, weak into melee)...
		// vs a RANGED player pet: melee Tackle is super effective, Arrow is neutral.
		AiController ai = new AiController(TrainerDef.Difficulty.MEDIUM, chart);
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = engine.start(
			TestPets.teamOf(TestPets.pet("target", PetType.RANGED, 20, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("ai", PetType.MELEE, 20, TestPets.ARROW, TestPets.TACKLE)),
			events);
		BattleAction action = ai.chooseAction(state, BattleState.ENEMY, new Random(1));
		// Tackle: 40 power * 2.0 eff * 1.5 stab = 120 > Arrow: 40 * 1.0 * 1.0 = 40
		assertEquals(1, action.getMoveIndex());
	}

	@Test
	public void hardHealsWhenLow()
	{
		AiController ai = new AiController(TrainerDef.Difficulty.HARD, chart);
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = engine.start(
			TestPets.teamOf(TestPets.pet("player", PetType.MELEE, 20, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("ai", PetType.NATURE, 20, TestPets.TACKLE, TestPets.HEAL_MOVE)),
			events);
		BattlePet aiPet = state.active(BattleState.ENEMY);
		aiPet.damage(aiPet.getMaxHp() - aiPet.getMaxHp() / 5); // drop to 20%
		BattleAction action = ai.chooseAction(state, BattleState.ENEMY, new Random(1));
		assertEquals("should pick the heal move", 1, action.getMoveIndex());
	}

	private BattleState newState()
	{
		List<BattleEvent> events = new ArrayList<>();
		return engine.start(
			TestPets.teamOf(TestPets.pet("p", PetType.MELEE, 10, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("e", PetType.MELEE, 10, TestPets.TACKLE, TestPets.EMBER)),
			events);
	}
}
