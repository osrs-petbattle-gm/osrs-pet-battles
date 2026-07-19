package com.petbattles.engine.controller;

import com.petbattles.engine.BattleAction;
import com.petbattles.engine.BattleState;
import java.util.Random;

/**
 * Chooses a side's action each turn. The AI implements this today; a future PvP
 * controller can implement it over RuneLite's Party service without engine changes.
 */
public interface OpponentController
{
	BattleAction chooseAction(BattleState state, int side, Random rng);
}
