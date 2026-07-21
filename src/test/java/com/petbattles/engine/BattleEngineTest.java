package com.petbattles.engine;

import java.util.ArrayList;
import java.util.Arrays;
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
		BattleEvent sendOut = turn1.stream()
			.filter(e -> e.getType() == BattleEvent.Type.PET_SENT_OUT && e.getSide() == BattleState.ENEMY)
			.findFirst().orElse(null);
		assertTrue("second pet sent out", sendOut != null);
		assertTrue("enemy replacement swap is deferred to animation time", sendOut.isDeferredSwitch());
		assertFalse("the replacement never acts with the fainted pet's queued move",
			turn1.stream().anyMatch(e -> e.getType() == BattleEvent.Type.MOVE_USED && e.getSide() == BattleState.ENEMY));
		assertFalse(state.isOver());
		// The swap isn't applied during resolution — the fainted pet stays active so it can
		// play its faint animation; the UI applies the swap when the send-out is shown
		assertEquals("swap deferred, not applied yet", 0, state.activeIndex(BattleState.ENEMY));
		applyDeferredSwitches(state, turn1);
		assertEquals(1, state.activeIndex(BattleState.ENEMY));

		List<BattleEvent> turn2 = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(2));
		assertTrue(turn2.stream().anyMatch(e -> e.getType() == BattleEvent.Type.BATTLE_END));
		assertEquals(BattleState.Phase.PLAYER_WON, state.getPhase());
	}

	/** Mimics the session applying deferred enemy send-outs at animation time. */
	private static void applyDeferredSwitches(BattleState state, List<BattleEvent> events)
	{
		for (BattleEvent e : events)
		{
			if (e.isDeferredSwitch())
			{
				state.setActive(e.getSide(), e.getValue());
			}
		}
	}

	@Test
	public void onlyActivatedPetsAreMarkedAsFought()
	{
		BattlePet lead = TestPets.fastPet("lead", PetType.MELEE, 99,
			new MoveDef("smash", "Smash", PetType.MELEE, 250, 100, MoveEffect.NONE, 0));
		BattlePet bench = TestPets.slowPet("bench", PetType.MELEE, 20, TestPets.TACKLE);
		BattlePet enemy = TestPets.slowPet("enemy", PetType.SKILLING, 1, TestPets.TACKLE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(lead, bench), TestPets.teamOf(enemy), events);
		engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));

		assertEquals(BattleState.Phase.PLAYER_WON, state.getPhase());
		assertTrue("lead that fought is marked", state.hasFought(BattleState.PLAYER, 0));
		assertFalse("bench pet that never came out did not fight", state.hasFought(BattleState.PLAYER, 1));
	}

	@Test
	public void switchingInMarksThePetAsFought()
	{
		BattlePet first = TestPets.fastPet("first", PetType.MELEE, 20, TestPets.TACKLE);
		BattlePet second = TestPets.fastPet("second", PetType.RANGED, 20, TestPets.ARROW);
		BattlePet enemy = TestPets.slowPet("enemy", PetType.MELEE, 20, TestPets.TACKLE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(first, second), TestPets.teamOf(enemy), events);
		assertFalse(state.hasFought(BattleState.PLAYER, 1));

		engine.resolveTurn(state, BattleAction.switchTo(1), BattleAction.move(0), new Random(1));
		assertTrue("switched-in pet counts as having fought", state.hasFought(BattleState.PLAYER, 1));
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
	public void playerFaintPausesForForcedSwitchWithoutAutoSend()
	{
		MoveDef smash = new MoveDef("smash", "Smash", PetType.MELEE, 250, 100, MoveEffect.NONE, 0);
		BattlePet lead = TestPets.slowPet("lead", PetType.SKILLING, 1, TestPets.TACKLE);
		BattlePet bench = TestPets.pet("bench", PetType.RANGED, 20, TestPets.ARROW);
		BattlePet enemy = TestPets.fastPet("enemy", PetType.MELEE, 99, smash);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(lead, bench), TestPets.teamOf(enemy), events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));

		assertTrue("player pet fainted", turn.stream().anyMatch(
			e -> e.getType() == BattleEvent.Type.FAINTED && e.getSide() == BattleState.PLAYER));
		assertTrue("battle pauses for a forced switch", state.awaitingForcedSwitch());
		assertFalse(state.isOver());
		// No auto-advance to the bench, and the replacement neither was sent nor attacked
		assertEquals("lead stays the active slot until the player picks", 0, state.activeIndex(BattleState.PLAYER));
		assertFalse("no player send-out happens automatically", turn.stream().anyMatch(
			e -> e.getType() == BattleEvent.Type.PET_SENT_OUT && e.getSide() == BattleState.PLAYER));
		assertFalse("the incoming pet never inherits the fainted pet's queued move", turn.stream().anyMatch(
			e -> e.getType() == BattleEvent.Type.MOVE_USED && e.getSide() == BattleState.PLAYER));
		assertEquals("bench pet is untouched", bench.getMaxHp(), bench.getCurrentHp());
	}

	@Test
	public void forcedSwitchSendsInChosenPetAndConsumesTheRound()
	{
		MoveDef smash = new MoveDef("smash", "Smash", PetType.MELEE, 250, 100, MoveEffect.NONE, 0);
		BattlePet lead = TestPets.slowPet("lead", PetType.SKILLING, 1, TestPets.TACKLE);
		BattlePet bench = TestPets.pet("bench", PetType.RANGED, 20, TestPets.ARROW);
		BattlePet enemy = TestPets.fastPet("enemy", PetType.MELEE, 99, smash);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(lead, bench), TestPets.teamOf(enemy), events);
		engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));
		assertTrue(state.awaitingForcedSwitch());

		List<BattleEvent> sw = engine.resolveForcedSwitch(state, 1);
		assertTrue("forced switch emits a send-out", sw.stream().anyMatch(
			e -> e.getType() == BattleEvent.Type.PET_SENT_OUT && e.getSide() == BattleState.PLAYER));
		assertEquals(1, state.activeIndex(BattleState.PLAYER));
		assertFalse("play resumes after the pick", state.awaitingForcedSwitch());
		assertTrue(state.hasFought(BattleState.PLAYER, 1));
		// The incoming pet did not counter-attack this round: it and the enemy are unhurt
		assertEquals(bench.getMaxHp(), bench.getCurrentHp());
		assertEquals(enemy.getMaxHp(), enemy.getCurrentHp());
	}

	@Test
	public void dotFaintRoutesThroughForcedSwitch()
	{
		// A no-damage enemy move plus a burn on a near-dead lead: the burn tick at
		// end of turn is what faints the player, and it must pause for a forced switch.
		BattlePet lead = new BattlePet(TestPets.species("lead", PetType.MELEE, 50, 50, 50, 50),
			"lead", 5, Arrays.asList(TestPets.TACKLE), 3);
		BattlePet bench = TestPets.pet("bench", PetType.MELEE, 20, TestPets.TACKLE);
		BattlePet enemy = TestPets.pet("enemy", PetType.MELEE, 5, TestPets.BUFF_MOVE);
		lead.applyStatus(BattlePet.Status.BURN, Integer.MAX_VALUE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(lead, bench), TestPets.teamOf(enemy), events);
		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.move(0), BattleAction.move(0), new Random(1));

		assertTrue("burn chipped the lead", turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.STATUS_TICK));
		assertTrue("the burn faint is the player's", turn.stream().anyMatch(
			e -> e.getType() == BattleEvent.Type.FAINTED && e.getSide() == BattleState.PLAYER));
		assertTrue("a DOT faint still pauses for a forced switch", state.awaitingForcedSwitch());
		assertFalse(state.isOver());
	}

	@Test
	public void switchChangesActivePetAndConsumesTurn()
	{
		BattlePet first = TestPets.fastPet("first", PetType.MELEE, 20, TestPets.TACKLE);
		BattlePet second = TestPets.fastPet("second", PetType.RANGED, 20, TestPets.ARROW);
		BattlePet enemy = TestPets.slowPet("enemy", PetType.MELEE, 20, TestPets.TACKLE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(first, second), TestPets.teamOf(enemy), events);

		List<BattleEvent> turn = engine.resolveTurn(state, BattleAction.switchTo(1), BattleAction.move(0), new Random(1));

		assertEquals(1, state.activeIndex(BattleState.PLAYER));
		assertTrue("switch emits a send-out",
			turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.PET_SENT_OUT && e.getSide() == BattleState.PLAYER));
		// Switching consumed the player's turn: no player move was used
		assertFalse(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.MOVE_USED && e.getSide() == BattleState.PLAYER));
		// The enemy still acted, and its damage landed on the newly sent-out pet
		assertTrue(turn.stream().anyMatch(e -> e.getType() == BattleEvent.Type.MOVE_USED && e.getSide() == BattleState.ENEMY));
		assertTrue(second.getCurrentHp() < second.getMaxHp());
		assertEquals(first.getMaxHp(), first.getCurrentHp());
	}

	@Test
	public void switchToFaintedOrInvalidTargetIsIgnored()
	{
		BattlePet first = TestPets.fastPet("first", PetType.MELEE, 20, TestPets.TACKLE);
		BattlePet downed = TestPets.fastPet("downed", PetType.MELEE, 20, TestPets.TACKLE);
		downed.damage(downed.getMaxHp());
		BattlePet enemy = TestPets.slowPet("enemy", PetType.MELEE, 20, TestPets.TACKLE);

		List<BattleEvent> events = new ArrayList<>();
		BattleState state = start(TestPets.teamOf(first, downed), TestPets.teamOf(enemy), events);

		engine.resolveTurn(state, BattleAction.switchTo(1), BattleAction.move(0), new Random(1));
		assertEquals("cannot switch to a fainted pet", 0, state.activeIndex(BattleState.PLAYER));

		engine.resolveTurn(state, BattleAction.switchTo(0), BattleAction.move(0), new Random(1));
		assertEquals("switching to the active pet is a no-op", 0, state.activeIndex(BattleState.PLAYER));

		engine.resolveTurn(state, BattleAction.switchTo(7), BattleAction.move(0), new Random(1));
		assertEquals("out-of-range switch is a no-op", 0, state.activeIndex(BattleState.PLAYER));
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
