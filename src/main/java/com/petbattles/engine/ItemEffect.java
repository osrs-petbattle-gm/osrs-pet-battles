package com.petbattles.engine;

/**
 * A held item's passive stat modifier: a percent boost to one of the holder's derived stats,
 * applied for the whole battle. Unlike {@link MoveEffect} (a staged, temporary in-battle buff that
 * clears on switch), this is a persistent multiplier — its single hook is {@link BattlePet}'s
 * {@code effective*()} / max-HP derivation.
 *
 * <p>v1 models untyped, holder-targeting boosts (amulets/bracelets/plushies/the Stick). A future
 * {@code typeFilter} (restricting an ATK boost to one move type) is a data-and-damage-time addition
 * on top of this, not a change to it.
 */
public final class ItemEffect
{
	public enum Stat
	{
		ATK,
		DEF,
		SPD,
		HP
	}

	private final Stat stat;
	// Percent boost to the affected stat, e.g. 15 => +15%. May be negative for a malus.
	private final int magnitude;

	public ItemEffect(Stat stat, int magnitude)
	{
		this.stat = stat;
		this.magnitude = magnitude;
	}

	public Stat getStat()
	{
		return stat;
	}

	public int getMagnitude()
	{
		return magnitude;
	}

	/**
	 * The multiplier this effect applies to {@code target}: {@code 1 + magnitude/100} when it
	 * targets that stat, else {@code 1.0} (no change).
	 */
	public double multiplierFor(Stat target)
	{
		return target == stat ? 1.0 + magnitude / 100.0 : 1.0;
	}
}
