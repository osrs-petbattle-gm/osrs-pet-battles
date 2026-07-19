package com.petbattles.engine;

/**
 * The ten OSRS-flavored battle types. The combat triangle (Melee > Ranged > Magic > Melee)
 * forms the core; the rest are thematic (dragonbane weapons, demonbane spells, etc.).
 */
public enum PetType
{
	MELEE("Melee", 0xC0392B),
	RANGED("Ranged", 0x27AE60),
	MAGIC("Magic", 0x2980B9),
	FIRE("Fire", 0xE67E22),
	ICE("Ice", 0x85C1E9),
	NATURE("Nature", 0x145A32),
	UNDEAD("Undead", 0x7D6608),
	DEMON("Demon", 0x6C3483),
	DRAGON("Dragon", 0x922B21),
	SKILLING("Skilling", 0xB7950B);

	private final String displayName;
	private final int colorRgb;

	PetType(String displayName, int colorRgb)
	{
		this.displayName = displayName;
		this.colorRgb = colorRgb;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public int getColorRgb()
	{
		return colorRgb;
	}
}
