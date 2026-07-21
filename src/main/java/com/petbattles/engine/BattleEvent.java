package com.petbattles.engine;

/**
 * One thing that happened during battle resolution, in order. The overlay drains these
 * for animation/log display; each carries a ready-made log line.
 */
public class BattleEvent
{
	public enum Type
	{
		PET_SENT_OUT,
		MOVE_USED,
		DAMAGE,
		MISSED,
		STATUS_APPLIED,
		STAT_CHANGED,
		STATUS_TICK,
		STATUS_SKIP,
		STATUS_END,
		HEALED,
		FAINTED,
		FLED,
		BATTLE_END,
		XP_GAINED,
		LEVEL_UP,
		MOVE_LEARNED
	}

	private final Type type;
	private final int side;   // BattleState.PLAYER / BattleState.ENEMY, or -1
	private final int value;  // damage, heal, level, xp... depending on type
	private final double effectiveness; // for DAMAGE
	private final String text;
	private final MoveDef move; // for MOVE_USED: drives the attack animation
	// PET_SENT_OUT only: when true, the active-pet swap to team slot `value` is applied by
	// the UI when this event is shown, not during resolution — so the fainted pet stays
	// on screen through its faint animation before the replacement appears.
	private final boolean deferredSwitch;

	public BattleEvent(Type type, int side, int value, double effectiveness, String text)
	{
		this(type, side, value, effectiveness, text, null, false);
	}

	private BattleEvent(Type type, int side, int value, double effectiveness, String text, MoveDef move)
	{
		this(type, side, value, effectiveness, text, move, false);
	}

	private BattleEvent(Type type, int side, int value, double effectiveness, String text, MoveDef move,
		boolean deferredSwitch)
	{
		this.type = type;
		this.side = side;
		this.value = value;
		this.effectiveness = effectiveness;
		this.text = text;
		this.move = move;
		this.deferredSwitch = deferredSwitch;
	}

	public static BattleEvent of(Type type, int side, String text)
	{
		return new BattleEvent(type, side, 0, 1.0, text);
	}

	/**
	 * A send-out whose active-pet swap (to team slot {@code teamIndex}) is deferred until
	 * the UI shows this event — used for the enemy's on-faint replacement.
	 */
	public static BattleEvent deferredSendOut(int side, int teamIndex, String text)
	{
		return new BattleEvent(Type.PET_SENT_OUT, side, teamIndex, 1.0, text, null, true);
	}

	public static BattleEvent moveUsed(int side, MoveDef move, String text)
	{
		return new BattleEvent(Type.MOVE_USED, side, 0, 1.0, text, move);
	}

	public static BattleEvent damage(int side, int amount, double effectiveness, String text)
	{
		return new BattleEvent(Type.DAMAGE, side, amount, effectiveness, text);
	}

	public static BattleEvent value(Type type, int side, int value, String text)
	{
		return new BattleEvent(type, side, value, 1.0, text);
	}

	public Type getType()
	{
		return type;
	}

	public int getSide()
	{
		return side;
	}

	public int getValue()
	{
		return value;
	}

	public double getEffectiveness()
	{
		return effectiveness;
	}

	public String getText()
	{
		return text;
	}

	public MoveDef getMove()
	{
		return move;
	}

	/**
	 * Whether this PET_SENT_OUT applies its active-pet swap when shown rather than during
	 * resolution (see {@link #deferredSendOut}).
	 */
	public boolean isDeferredSwitch()
	{
		return deferredSwitch;
	}

	@Override
	public String toString()
	{
		return type + ": " + text;
	}
}
