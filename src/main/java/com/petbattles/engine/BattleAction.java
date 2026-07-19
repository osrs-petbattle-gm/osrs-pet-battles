package com.petbattles.engine;

/**
 * One side's chosen action for a turn.
 */
public class BattleAction
{
	public enum Kind
	{
		MOVE,
		SWITCH,
		FLEE
	}

	private final Kind kind;
	private final int index; // move index for MOVE, team index for SWITCH

	private BattleAction(Kind kind, int index)
	{
		this.kind = kind;
		this.index = index;
	}

	public static BattleAction move(int index)
	{
		return new BattleAction(Kind.MOVE, index);
	}

	/**
	 * Switch the active pet to the given team index. Consumes the turn.
	 */
	public static BattleAction switchTo(int teamIndex)
	{
		return new BattleAction(Kind.SWITCH, teamIndex);
	}

	public static BattleAction flee()
	{
		return new BattleAction(Kind.FLEE, -1);
	}

	public Kind getKind()
	{
		return kind;
	}

	public int getMoveIndex()
	{
		return index;
	}

	public int getSwitchIndex()
	{
		return index;
	}
}
