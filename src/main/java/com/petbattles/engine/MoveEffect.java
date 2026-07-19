package com.petbattles.engine;

/**
 * Secondary effect of a move. Status effects target the enemy; stat-up and heal target self.
 */
public enum MoveEffect
{
	NONE,
	BURN,   // enemy: chip damage each turn, attack halved
	FREEZE, // enemy: skips turns until thawed
	POISON, // enemy: chip damage each turn
	STUN,   // enemy: chance to skip each turn, speed halved
	ATK_UP,
	DEF_UP,
	SPD_UP,
	ATK_DOWN,
	DEF_DOWN,
	SPD_DOWN,
	HEAL;   // self: restore 50% max HP

	public boolean isStatus()
	{
		return this == BURN || this == FREEZE || this == POISON || this == STUN;
	}

	public boolean isSelfBuff()
	{
		return this == ATK_UP || this == DEF_UP || this == SPD_UP || this == HEAL;
	}

	public boolean isEnemyDebuff()
	{
		return this == ATK_DOWN || this == DEF_DOWN || this == SPD_DOWN;
	}
}
