package com.petbattles.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BattleEngineTest
{
	private final BattleEngine engine = new BattleEngine(new TypeChart());

	private BattleState start(List<BattlePet> player, List<BattlePet> enemy, List<BattleEvent> events)
	{
		return engine.start(player, enemy, events);
	}

	@Test
	public void startEmitsSendOutForBothSides()
	{
		List<BattleEvent> events = new ArrayList<>();
		start(
			TestPets.teamOf(TestPets.pet("a", PetType.MELEE, 10, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("b", PetType.MELEE, 10, TestPets.TACKLE)),
			events);
		assertEquals(2, events.size());
		assertEquals(BattleEvent.Type.PET_SENT_OUT, events.get(0).getType());
		assertEquals(BattleEvent.Type.PET_SENT_OUT, events.get(1).getType());
	}

	@Test
	public void fasterPetActsFirst()
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.fastPet("fast", PetType.MELEE, 10, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.slowPet("slow", PetType.MELEE, 10, TestPets.TACKLE)),
			events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));
		// First MOVE_USED must belong to the player (the fast side)
		for (BattleEvent e : turn)
		{
			if (e.getType() == BattleEvent.Type.MOVE_USED)
			{
				assertEquals(BattleState.PLAYER, e.getSide());
				break;
			}
		}
	}

	@Test
	public void damageIsDealtAndDeterministic()
	{
		List<BattleEvent> a = runOneTurn(new Random(7));
		List<BattleEvent> b = runOneTurn(new Random(7));
		assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++)
		{
			assertEquals(a.get(i).getType(), b.get(i).getType());
			assertEquals(a.get(i).getValue(), b.get(i).getValue());
		}
		assertTrue(a.stream().anyMatch(e -> e.getType() == BattleEvent.Type.DAMAGE));
	}

	private List<BattleEvent> runOneTurn(Random rng)
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.fastPet("a", PetType.MELEE, 20, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.slowPet("b", PetType.MELEE, 20, TestPets.TACKLE)),
			events);
		return engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), rng);
	}

	@Test
	public void zeroAccuracyAlwaysMisses()
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.fastPet("a", PetType.MELEE, 20, TestPets.MISS_MOVE)),
			TestPets.teamOf(TestPets.slowPet("b", PetType.MELEE, 20, TestPets.TACKLE)),
			events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));
		assertTrue(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.MISSED));
		// The missing side dealt no damage: only one DAMAGE event (from the enemy's tackle)
		long damageEvents = turn.stream().filter(e -> e.getType() == BattleEvent.Type.DAMAGE).count();
		assertEquals(1, damageEvents);
	}

	@Test
	public void burnAppliesAndChips()
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.fastPet("burner", PetType.FIRE, 20, TestPets.EMBER)),
			TestPets.teamOf(TestPets.slowPet("victim", PetType.MELEE, 20, TestPets.TACKLE)),
			events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(3));
		assertTrue(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.STATUS_APPLIED));
		assertEquals(BattlePet.Status.BURN, state.active(BattleState.ENEMY).getStatus());
		assertTrue("burn should chip at end of turn",
			turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.STATUS_TICK));
	}

	@Test
	public void faintTriggersAutoSwitchThenBattleEnd()
	{
		BattlePet strong = TestPets.fastPet("strong", PetType.MELEE, 99,
			new MoveDef("smash", "Smash", PetType.MELEE, 250, 100, MoveEffect.NONE, 0));
		BattlePet weak1 = TestPets.slowPet("weak1", PetType.SKILLING, 1, TestPets.TACKLE);
		BattlePet weak2 = TestPets.slowPet("weak2", PetType.SKILLING, 1, TestPets.TACKLE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(strong), TestPets.teamOf(weak1, weak2), events);

		List<BattleEvent> turn1 = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));
		assertTrue(turn1.stream().anyMatch(e -> e.getType() == BattleEvent.Type.FAINTED));
		assertTrue("second pet sent out",
			turn1.stream().anyMatch(e -> e.getType() == BattleEvent.Type.PET_SENT_OUT));
		assertFalse(state.isOver());
		assertEquals(1, state.activeIndex(BattleState.ENEMY));

		List<BattleEvent> turn2 = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(2));
		assertTrue(turn2.stream().anyMatch(e -> e.getType() == BattleEvent.Type.BATTLE_END));
		assertEquals(BattleState.Phase.PLAYER_WON, state.getPhase());
	}

	@Test
	public void fleeEndsBattleImmediately()
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.pet("a", PetType.MELEE, 10, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("b", PetType.MELEE, 10, TestPets.TACKLE)),
			events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.flee(), BattleAction.move(0), new Random(1));
		assertEquals(BattleState.Phase.FLED, state.getPhase());
		assertTrue(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.FLED));
		// Enemy gets no move after a flee
		assertFalse(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.DAMAGE));
	}

	@Test
	public void healRestoresHp()
	{
		BattlePet healer = TestPets.slowPet("healer", PetType.NATURE, 20, TestPets.HEAL_MOVE);
		BattlePet attacker = TestPets.fastPet("attacker", PetType.MELEE, 20, TestPets.TACKLE);
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(healer), TestPets.teamOf(attacker), events);

		// Turn 1: attacker hits healer, healer heals back
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(5));
		assertTrue(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.HEALED));
	}

	@Test
	public void buffRaisesStageAndCaps()
	{
		BattlePet buffer = TestPets.pet("buffer", PetType.SKILLING, 20, TestPets.BUFF_MOVE);
		int baseAtk = buffer.effectiveAtk();
		for (int i = 0; i < 6; i++)
		{
			buffer.changeStage(MoveEffect.ATK_UP, +1);
		}
		assertEquals(BattlePet.MAX_STAGE, buffer.getAtkStage());
		assertTrue(buffer.effectiveAtk() > baseAtk);
	}

	@Test
	public void resolveTurnOnFinishedBattleDoesNothing()
	{
		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(
			TestPets.teamOf(TestPets.pet("a", PetType.MELEE, 10, TestPets.TACKLE)),
			TestPets.teamOf(TestPets.pet("b", PetType.MELEE, 10, TestPets.TACKLE)),
			events);
		state.setPhase(BattleState.Phase.PLAYER_WON);
		assertTrue(engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1)).isEmpty());
	}
}
