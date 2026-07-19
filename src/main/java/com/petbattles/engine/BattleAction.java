package com.petbattles.engine;

/**
 * One side's chosen action for a turn.
 */
public class BattleAction
{
	public enum Kind
	{
		MOVE,
		FLEE
	}

	private final Kind kind;
	private final int moveIndex;

	private BattleAction(Kind kind, int moveIndex)
	{
		this.kind = kind;
		this.moveIndex = moveIndex;
	}

	public static BattleAction move(int index)
	{
		return new BattleAction(Kind.MOVE, index);
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
		return moveIndex;
	}
}
