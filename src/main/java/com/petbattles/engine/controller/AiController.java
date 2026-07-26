package com.petbattles.engine.controller;

import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattlePet;
import com.petbattles.engine.BattleState;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.MoveEffect;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import java.util.List;
import java.util.Random;

/**
 * Trainer AI. EASY picks randomly; MEDIUM maximizes expected damage;
 * HARD adds healing when low and status spreading when ahead.
 */
public class AiController implements OpponentController
{
	private final TrainerDef.Difficulty difficulty;
	private final TypeChart typeChart;

	public AiController(TrainerDef.Difficulty difficulty, TypeChart typeChart)
	{
		this.difficulty = difficulty;
		this.typeChart = typeChart;
	}

	@Override
	public BattleAction chooseAction(BattleState state, int side, Random rng)
	{
		BattlePet self = state.active(side);
		List<MoveDef> moves = self.getMoves();
		if (moves.isEmpty())
		{
			return BattleAction.flee();
		}
		if (difficulty == TrainerDef.Difficulty.EASY)
		{
			return BattleAction.move(rng.nextInt(moves.size()));
		}

		BattlePet target = state.active(BattleState.opponent(side));

		if (difficulty == TrainerDef.Difficulty.HARD)
		{
			// Heal when below 35% if we have a heal move
			if (self.getCurrentHp() * 100 < self.getMaxHp() * 35)
			{
				int healIdx = indexOfEffect(moves, MoveEffect.HEAL);
				if (healIdx >= 0)
				{
					return BattleAction.move(healIdx);
				}
			}
			// Spread a status if the target is clean and we have a reliable status move
			if (target.getStatus() == BattlePet.Status.NONE)
			{
				for (int i = 0; i < moves.size(); i++)
				{
					MoveDef m = moves.get(i);
					if (m.isStatusMove() && m.getEffect().isStatus() && m.getEffectChance() >= 75)
					{
						return BattleAction.move(i);
					}
				}
			}
		}

		// MEDIUM (and HARD fallback): argmax expected damage
		int bestIdx = 0;
		double bestScore = -1;
		for (int i = 0; i < moves.size(); i++)
		{
			MoveDef m = moves.get(i);
			double eff = typeChart.effectiveness(m.getType(), target.getTypes());
			double stab = self.hasStab(m.getType()) ? 1.5 : 1.0;
			double score = m.getPower() * eff * stab * (m.getAccuracy() / 100.0);
			if (score > bestScore)
			{
				bestScore = score;
				bestIdx = i;
			}
		}
		return BattleAction.move(bestIdx);
	}

	private static int indexOfEffect(List<MoveDef> moves, MoveEffect effect)
	{
		for (int i = 0; i < moves.size(); i++)
		{
			if (moves.get(i).getEffect() == effect)
			{
				return i;
			}
		}
		return -1;
	}
}
