package com.petbattles.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Deterministic turn resolver. No timing, no RuneLite — feed it actions and a Random,
 * get back an ordered event list. All randomness flows through the injected Random.
 */
public class BattleEngine
{
	private static final int STATUS_DURATION_TURNS = 3;
	private static final int FREEZE_THAW_PERCENT = 30;
	private static final int STUN_SKIP_PERCENT = 25;

	private final TypeChart typeChart;

	public BattleEngine(TypeChart typeChart)
	{
		this.typeChart = typeChart;
	}

	/**
	 * Create the opening state and its send-out events.
	 */
	public BattleState start(List<BattlePet> playerTeam, List<BattlePet> enemyTeam, List<BattleEvent> events)
	{
		BattleState state = new BattleState(playerTeam, enemyTeam);
		events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, BattleState.PLAYER,
			"Go, " + state.active(BattleState.PLAYER).getDisplayName() + "!"));
		events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, BattleState.ENEMY,
			"Enemy sends out " + state.active(BattleState.ENEMY).getDisplayName() + "!"));
		return state;
	}

	/**
	 * Resolve one full turn. Returns the ordered events that occurred.
	 */
	public List<BattleEvent> resolveTurn(BattleState state, BattleAction playerAction, BattleAction enemyAction, Random rng)
	{
		List<BattleEvent> events = new ArrayList<>();
		if (state.isOver() || state.awaitingForcedSwitch())
		{
			return events;
		}
		state.nextTurn();

		// Fleeing (forfeit) resolves before anything else
		if (playerAction.getKind() == BattleAction.Kind.FLEE)
		{
			state.setPhase(BattleState.Phase.FLED);
			events.add(BattleEvent.of(BattleEvent.Type.FLED, BattleState.PLAYER, "You fled from the battle!"));
			events.add(BattleEvent.of(BattleEvent.Type.BATTLE_END, -1, "The battle is over."));
			return events;
		}

		// Switches resolve before moves: the switch consumes the turn and the
		// opponent's attack lands on the incoming pet
		handleSwitch(state, BattleState.PLAYER, playerAction, events);
		handleSwitch(state, BattleState.ENEMY, enemyAction, events);

		// Speed decides order; ties are a coin flip
		int playerSpd = state.active(BattleState.PLAYER).effectiveSpd();
		int enemySpd = state.active(BattleState.ENEMY).effectiveSpd();
		int first;
		if (playerSpd != enemySpd)
		{
			first = playerSpd > enemySpd ? BattleState.PLAYER : BattleState.ENEMY;
		}
		else
		{
			first = rng.nextBoolean() ? BattleState.PLAYER : BattleState.ENEMY;
		}
		int second = BattleState.opponent(first);

		// If the first attacker faints the second side's pet, that side gets a fresh pet:
		// the enemy auto-picks (below), the player is prompted (PLAYER_MUST_SWITCH). Either
		// way the replacement does not act with the fainted pet's queued move this turn.
		int secondActiveBefore = state.activeIndex(second);
		act(state, first, actionFor(first, playerAction, enemyAction), events, rng);
		if (haltAfterStep(state))
		{
			return events;
		}
		boolean secondReplaced = state.activeIndex(second) != secondActiveBefore;
		if (!secondReplaced)
		{
			act(state, second, actionFor(second, playerAction, enemyAction), events, rng);
			if (haltAfterStep(state))
			{
				return events;
			}
		}

		endOfTurn(state, first, events);
		if (haltAfterStep(state))
		{
			return events;
		}
		endOfTurn(state, second, events);
		return events;
	}

	/**
	 * Whether turn resolution must stop here: the battle ended, or the player owes a
	 * forced switch. In the forced-switch case the round is over; the incoming pet plays
	 * from the next turn.
	 */
	private static boolean haltAfterStep(BattleState state)
	{
		return state.isOver() || state.awaitingForcedSwitch();
	}

	/**
	 * Resolve the player's forced replacement after a mid-turn faint. Consumes the round:
	 * the incoming pet does not attack now; normal play resumes next turn.
	 */
	public List<BattleEvent> resolveForcedSwitch(BattleState state, int teamIndex)
	{
		List<BattleEvent> events = new ArrayList<>();
		if (!state.awaitingForcedSwitch())
		{
			return events;
		}
		List<BattlePet> team = state.team(BattleState.PLAYER);
		if (teamIndex < 0 || teamIndex >= team.size() || team.get(teamIndex).isFainted())
		{
			// Invalid target: the UI only offers legal ones, so just keep waiting
			return events;
		}
		state.setActive(BattleState.PLAYER, teamIndex);
		BattlePet incoming = state.active(BattleState.PLAYER);
		state.setPhase(BattleState.Phase.IN_PROGRESS);
		events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, BattleState.PLAYER,
			"Go, " + incoming.getDisplayName() + "!"));
		return events;
	}

	private static BattleAction actionFor(int side, BattleAction playerAction, BattleAction enemyAction)
	{
		return side == BattleState.PLAYER ? playerAction : enemyAction;
	}

	/**
	 * Validate and apply a SWITCH action; invalid targets (out of range, fainted,
	 * already active) are silently ignored.
	 */
	private void handleSwitch(BattleState state, int side, BattleAction action, List<BattleEvent> events)
	{
		if (action.getKind() != BattleAction.Kind.SWITCH)
		{
			return;
		}
		int target = action.getSwitchIndex();
		List<BattlePet> team = state.team(side);
		if (target < 0 || target >= team.size() || target == state.activeIndex(side)
			|| team.get(target).isFainted())
		{
			return;
		}
		BattlePet outgoing = state.active(side);
		state.setActive(side, target);
		BattlePet incoming = state.active(side);
		String text = side == BattleState.PLAYER
			? outgoing.getDisplayName() + ", come back! Go, " + incoming.getDisplayName() + "!"
			: "Enemy withdraws " + outgoing.getDisplayName() + " and sends out " + incoming.getDisplayName() + "!";
		events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, side, text));
	}

	private void act(BattleState state, int side, BattleAction action, List<BattleEvent> events, Random rng)
	{
		BattlePet attacker = state.active(side);
		if (attacker.isFainted())
		{
			return;
		}
		if (action.getKind() != BattleAction.Kind.MOVE)
		{
			return;
		}

		// Status pre-checks: frozen pets may thaw, stunned pets may flinch
		if (attacker.getStatus() == BattlePet.Status.FREEZE)
		{
			if (rng.nextInt(100) < FREEZE_THAW_PERCENT)
			{
				attacker.cureStatus();
				events.add(BattleEvent.of(BattleEvent.Type.STATUS_END, side,
					attacker.getDisplayName() + " thawed out!"));
			}
			else
			{
				events.add(BattleEvent.of(BattleEvent.Type.STATUS_SKIP, side,
					attacker.getDisplayName() + " is frozen solid!"));
				return;
			}
		}
		else if (attacker.getStatus() == BattlePet.Status.STUN && rng.nextInt(100) < STUN_SKIP_PERCENT)
		{
			events.add(BattleEvent.of(BattleEvent.Type.STATUS_SKIP, side,
				attacker.getDisplayName() + " is stunned and can't move!"));
			return;
		}

		List<MoveDef> moves = attacker.getMoves();
		int idx = action.getMoveIndex();
		if (idx < 0 || idx >= moves.size())
		{
			return;
		}
		MoveDef move = moves.get(idx);
		BattlePet defender = state.active(BattleState.opponent(side));

		events.add(BattleEvent.moveUsed(side,
			move, attacker.getDisplayName() + " used " + move.getName() + "!"));

		if (rng.nextInt(100) >= move.getAccuracy())
		{
			events.add(BattleEvent.of(BattleEvent.Type.MISSED, side, "The attack missed!"));
			return;
		}

		if (!move.isStatusMove())
		{
			double eff = typeChart.effectiveness(move.getType(), defender.getSpecies().getTypes());
			int dmg = DamageCalc.damage(attacker.getLevel(), move.getPower(),
				attacker.effectiveAtk(), defender.effectiveDef(),
				attacker.hasStab(move.getType()), eff, rng);
			defender.damage(dmg);
			events.add(BattleEvent.damage(BattleState.opponent(side), dmg, eff,
				defender.getDisplayName() + " took " + dmg + " damage" + effectivenessSuffix(eff)));
		}

		// Secondary effect
		MoveEffect effect = move.getEffect();
		if (effect != MoveEffect.NONE && !defender.isFainted() && rng.nextInt(100) < move.getEffectChance())
		{
			applyEffect(state, side, attacker, defender, effect, events);
		}

		if (defender.isFainted())
		{
			handleFaint(state, BattleState.opponent(side), events);
		}
	}

	private void applyEffect(BattleState state, int side, BattlePet attacker, BattlePet defender,
		MoveEffect effect, List<BattleEvent> events)
	{
		if (effect.isStatus())
		{
			BattlePet.Status status = toStatus(effect);
			// Burn and poison last until the battle ends; freeze/stun are timed
			int turns = (status == BattlePet.Status.FREEZE || status == BattlePet.Status.STUN)
				? STATUS_DURATION_TURNS : Integer.MAX_VALUE;
			if (defender.applyStatus(status, turns))
			{
				events.add(BattleEvent.of(BattleEvent.Type.STATUS_APPLIED, BattleState.opponent(side),
					defender.getDisplayName() + " was " + statusVerb(status) + "!"));
			}
		}
		else if (effect == MoveEffect.HEAL)
		{
			int healed = attacker.heal(attacker.getMaxHp() / 2);
			if (healed > 0)
			{
				events.add(BattleEvent.value(BattleEvent.Type.HEALED, side, healed,
					attacker.getDisplayName() + " restored " + healed + " HP!"));
			}
		}
		else if (effect.isSelfBuff())
		{
			int applied = attacker.changeStage(effect, +1);
			if (applied != 0)
			{
				events.add(BattleEvent.of(BattleEvent.Type.STAT_CHANGED, side,
					attacker.getDisplayName() + "'s " + statName(effect) + " rose!"));
			}
		}
		else if (effect.isEnemyDebuff())
		{
			int applied = defender.changeStage(effect, -1);
			if (applied != 0)
			{
				events.add(BattleEvent.of(BattleEvent.Type.STAT_CHANGED, BattleState.opponent(side),
					defender.getDisplayName() + "'s " + statName(effect) + " fell!"));
			}
		}
	}

	private void endOfTurn(BattleState state, int side, List<BattleEvent> events)
	{
		BattlePet pet = state.active(side);
		if (pet.isFainted())
		{
			return;
		}
		BattlePet.Status status = pet.getStatus();
		if (status == BattlePet.Status.BURN || status == BattlePet.Status.POISON)
		{
			int chip = Math.max(1, pet.getMaxHp() / (status == BattlePet.Status.BURN ? 16 : 8));
			pet.damage(chip);
			events.add(BattleEvent.value(BattleEvent.Type.STATUS_TICK, side, chip,
				pet.getDisplayName() + " is hurt by " + statusNoun(status) + "! (" + chip + ")"));
			if (pet.isFainted())
			{
				handleFaint(state, side, events);
				return;
			}
		}
		if (status == BattlePet.Status.FREEZE || status == BattlePet.Status.STUN)
		{
			pet.tickStatus();
			if (pet.getStatus() == BattlePet.Status.NONE)
			{
				events.add(BattleEvent.of(BattleEvent.Type.STATUS_END, side,
					pet.getDisplayName() + " recovered!"));
			}
		}
	}

	private void handleFaint(BattleState state, int faintedSide, List<BattleEvent> events)
	{
		BattlePet fainted = state.active(faintedSide);
		events.add(BattleEvent.of(BattleEvent.Type.FAINTED, faintedSide,
			fainted.getDisplayName() + " fainted!"));
		if (state.allFainted(faintedSide))
		{
			boolean playerWon = faintedSide == BattleState.ENEMY;
			state.setPhase(playerWon ? BattleState.Phase.PLAYER_WON : BattleState.Phase.ENEMY_WON);
			events.add(BattleEvent.of(BattleEvent.Type.BATTLE_END, -1,
				playerWon ? "You won the battle!" : "You were defeated..."));
		}
		else if (faintedSide == BattleState.PLAYER)
		{
			// Stop and let the player pick a replacement; the forced switch consumes the
			// round, so the incoming pet never inherits the fainted pet's queued move.
			state.setPhase(BattleState.Phase.PLAYER_MUST_SWITCH);
		}
		else
		{
			// Enemy auto-sends its next pet; its queued action is spent by the send-out
			state.sendNext(BattleState.ENEMY);
			BattlePet next = state.active(BattleState.ENEMY);
			events.add(BattleEvent.of(BattleEvent.Type.PET_SENT_OUT, BattleState.ENEMY,
				"Enemy sends out " + next.getDisplayName() + "!"));
		}
	}

	private static BattlePet.Status toStatus(MoveEffect effect)
	{
		switch (effect)
		{
			case BURN:
				return BattlePet.Status.BURN;
			case FREEZE:
				return BattlePet.Status.FREEZE;
			case POISON:
				return BattlePet.Status.POISON;
			case STUN:
				return BattlePet.Status.STUN;
			default:
				return BattlePet.Status.NONE;
		}
	}

	private static String statusVerb(BattlePet.Status status)
	{
		switch (status)
		{
			case BURN:
				return "burned";
			case FREEZE:
				return "frozen";
			case POISON:
				return "poisoned";
			case STUN:
				return "stunned";
			default:
				return "afflicted";
		}
	}

	private static String statusNoun(BattlePet.Status status)
	{
		return status == BattlePet.Status.BURN ? "its burn" : "poison";
	}

	private static String statName(MoveEffect effect)
	{
		switch (effect)
		{
			case ATK_UP:
			case ATK_DOWN:
				return "Attack";
			case DEF_UP:
			case DEF_DOWN:
				return "Defence";
			case SPD_UP:
			case SPD_DOWN:
				return "Speed";
			default:
				return "stat";
		}
	}

	private static String effectivenessSuffix(double eff)
	{
		if (eff >= 2.0)
		{
			return " — it's super effective!";
		}
		if (eff <= 0.5)
		{
			return " — it's not very effective...";
		}
		return "!";
	}

	public TypeChart getTypeChart()
	{
		return typeChart;
	}
}
