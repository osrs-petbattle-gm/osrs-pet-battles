package com.petbattles.pvp;

import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattleEngine;
import com.petbattles.engine.BattleEvent;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.PetType;
import com.petbattles.engine.TestPets;
import com.petbattles.engine.TypeChart;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The one thing PvP cannot get wrong: two clients holding mirrored battle states must resolve every
 * turn to the same outcome from the same seed, with no server to arbitrate.
 *
 * <p>The mirroring is what makes this delicate. On the challenger's client the challenger's team is
 * {@code PLAYER}; on the accepter's it is {@code ENEMY}. Anything the engine decides by side rather
 * than by pet would send the two runs opposite ways — which is exactly what
 * {@code setSpeedTieSide} and {@code setAutoReplace} exist to prevent. This plays whole battles out
 * both ways and checks they stay identical, move by move.
 */
public class LockstepDeterminismTest
{
	private static final TypeChart TYPE_CHART = new TypeChart();

	/** One client's view of the battle, plus which physical player is its {@code PLAYER} side. */
	private static final class Client
	{
		final BattleState state;
		final BattleEngine engine = new BattleEngine(TYPE_CHART);
		final Random rng;
		final boolean challengerIsPlayer;

		Client(List<BattlePet> playerTeam, List<BattlePet> enemyTeam, long seed, boolean challengerIsPlayer)
		{
			this.state = new BattleState(playerTeam, enemyTeam);
			this.rng = new Random(seed);
			this.challengerIsPlayer = challengerIsPlayer;
			// Both clients pin ties to the challenger's pet and auto-replace on both sides — the two
			// rules startPvpBattle sets.
			state.setSpeedTieSide(challengerIsPlayer ? BattleState.PLAYER : BattleState.ENEMY);
			state.setAutoReplace(true);
			engine.start(state, new ArrayList<>());
		}

		/** Resolve a turn from the two players' actions, mapped onto this client's sides. */
		List<BattleEvent> turn(BattleAction challengerAction, BattleAction accepterAction)
		{
			BattleAction mine = challengerIsPlayer ? challengerAction : accepterAction;
			BattleAction theirs = challengerIsPlayer ? accepterAction : challengerAction;
			List<BattleEvent> events = engine.resolveTurn(state, mine, theirs, rng);
			// The session applies deferred send-outs when it draws their line; do the same here so
			// both clients enter the next turn with the same pets on the field.
			for (BattleEvent e : events)
			{
				if (e.isDeferredSwitch())
				{
					state.setActive(e.getSide(), e.getValue());
				}
			}
			return events;
		}

		long checksum()
		{
			return PvpProtocol.checksum(state, challengerIsPlayer);
		}
	}

	/** Turns to play before calling it a stalemate; a healer can stall a fight indefinitely. */
	private static final int TURN_CAP = 400;

	@Test
	public void mirroredClientsResolveEveryTurnIdentically()
	{
		int finished = 0;
		for (int trial = 0; trial < 40; trial++)
		{
			finished += playOneBattle(0x5EEDL + trial * 7919L) ? 1 : 0;
		}
		// Stalemates are legitimate (a healer can outpace the damage), but if nothing ever ends then
		// the knockout, auto-replace and battle-over paths went untested and this proves nothing.
		assertTrue("no battle reached a knockout — the end-of-battle paths went untested",
			finished > 20);
	}

	/** Play one battle out on both clients; returns whether it reached an end rather than stalling. */
	private boolean playOneBattle(long seed)
	{
		Client challenger = new Client(challengerTeam(), accepterTeam(), seed, true);
		Client accepter = new Client(accepterTeam(), challengerTeam(), seed, false);

		assertEquals("initial states must agree, seed " + seed,
			challenger.checksum(), accepter.checksum());

		// The players' choices are their own business, not the battle RNG's: a separate stream keeps
		// the shared one untouched, so the actions are the only input that varies.
		Random choices = new Random(seed ^ 0xA5A5A5A5L);
		int turn = 0;
		while (!challenger.state.isOver() && turn < TURN_CAP)
		{
			BattleAction challengerAction = randomAction(challenger.state, BattleState.PLAYER, choices);
			BattleAction accepterAction = randomAction(accepter.state, BattleState.PLAYER, choices);

			List<BattleEvent> a = challenger.turn(challengerAction, accepterAction);
			List<BattleEvent> b = accepter.turn(challengerAction, accepterAction);

			assertEquals("event count diverged on turn " + turn + ", seed " + seed, a.size(), b.size());
			for (int i = 0; i < a.size(); i++)
			{
				assertEquals("event " + i + " type on turn " + turn + ", seed " + seed,
					a.get(i).getType(), b.get(i).getType());
				assertEquals("event " + i + " value on turn " + turn + ", seed " + seed,
					a.get(i).getValue(), b.get(i).getValue());
				// Sides are mirrored between the two clients, so they must be opposites (or both
				// the -1 that marks a battle-wide line).
				int sideA = a.get(i).getSide();
				int sideB = b.get(i).getSide();
				assertEquals("event " + i + " side on turn " + turn + ", seed " + seed,
					sideA < 0 ? sideA : BattleState.opponent(sideA), sideB);
			}
			assertEquals("state diverged after turn " + turn + ", seed " + seed,
				challenger.checksum(), accepter.checksum());
			turn++;
		}

		// Whether it ended or stalled, both clients must have reached the same verdict on that too.
		assertEquals("one client thinks the battle is over and the other doesn't, seed " + seed,
			challenger.state.isOver(), accepter.state.isOver());
		if (!challenger.state.isOver())
		{
			return false;
		}
		// Both sides agree who won — from opposite points of view.
		assertEquals("outcome diverged, seed " + seed,
			challenger.state.getPhase() == BattleState.Phase.PLAYER_WON,
			accepter.state.getPhase() == BattleState.Phase.ENEMY_WON);
		return true;
	}

	/**
	 * A plausible player: usually attacks, occasionally switches (sometimes to an illegal slot,
	 * which both clients must ignore in the same way).
	 */
	private static BattleAction randomAction(BattleState state, int side, Random choices)
	{
		int roll = choices.nextInt(10);
		if (roll == 0)
		{
			return BattleAction.switchTo(choices.nextInt(state.team(side).size() + 1));
		}
		int moves = state.active(side).getMoves().size();
		return BattleAction.move(choices.nextInt(Math.max(1, moves)));
	}

	/**
	 * Equal speeds on the leads so every early turn goes through the tie-break — the mirrored coin
	 * flip this test exists to rule out.
	 */
	private static List<BattlePet> challengerTeam()
	{
		return Arrays.asList(
			TestPets.pet("alpha", PetType.MELEE, 12, TestPets.TACKLE, TestPets.EMBER, TestPets.MISS_MOVE),
			TestPets.fastPet("bravo", PetType.RANGED, 10, TestPets.ARROW, TestPets.STUN_MOVE),
			TestPets.slowPet("charlie", PetType.NATURE, 14, TestPets.HEAL_MOVE, TestPets.TACKLE));
	}

	private static List<BattlePet> accepterTeam()
	{
		return Arrays.asList(
			TestPets.pet("delta", PetType.FIRE, 12, TestPets.EMBER, TestPets.TACKLE, TestPets.BUFF_MOVE),
			TestPets.slowPet("echo", PetType.MELEE, 13, TestPets.TACKLE, TestPets.MISS_MOVE),
			TestPets.fastPet("foxtrot", PetType.SKILLING, 11, TestPets.TACKLE, TestPets.STUN_MOVE));
	}
}
