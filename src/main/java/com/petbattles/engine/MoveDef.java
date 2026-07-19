package com.petbattles.engine;

/**
 * A move definition loaded from moves.json. power == 0 means a pure status/utility move.
 */
public class MoveDef
{
	private String id;
	private String name;
	private PetType type;
	private int power;
	private int accuracy;
	private MoveEffect effect = MoveEffect.NONE;
	private int effectChance;
	// Optional hand-authored animation id ("lunge", "whip", "projectile", "grow",
	// "shake", "flash", "sparkle"); null falls back to a category default.
	private String animation;

	public MoveDef()
	{
	}

	public MoveDef(String id, String name, PetType type, int power, int accuracy, MoveEffect effect, int effectChance)
	{
		this.id = id;
		this.name = name;
		this.type = type;
		this.power = power;
		this.accuracy = accuracy;
		this.effect = effect == null ? MoveEffect.NONE : effect;
		this.effectChance = effectChance;
	}

	public String getId()
	{
		return id;
	}

	public String getName()
	{
		return name;
	}

	public PetType getType()
	{
		return type;
	}

	public int getPower()
	{
		return power;
	}

	public int getAccuracy()
	{
		return accuracy;
	}

	public MoveEffect getEffect()
	{
		return effect == null ? MoveEffect.NONE : effect;
	}

	public int getEffectChance()
	{
		return effectChance;
	}

	public String getAnimation()
	{
		return animation;
	}

	public boolean isStatusMove()
	{
		return power <= 0;
	}
}
