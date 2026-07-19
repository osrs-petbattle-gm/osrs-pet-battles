package com.petbattles.engine;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TypeChartTest
{
	private final TypeChart chart = new TypeChart();

	@Test
	public void combatTriangle()
	{
		assertEquals(2.0, chart.effectiveness(PetType.MELEE, PetType.RANGED), 0.001);
		assertEquals(2.0, chart.effectiveness(PetType.RANGED, PetType.MAGIC), 0.001);
		assertEquals(2.0, chart.effectiveness(PetType.MAGIC, PetType.MELEE), 0.001);
		assertEquals(0.5, chart.effectiveness(PetType.RANGED, PetType.MELEE), 0.001);
		assertEquals(0.5, chart.effectiveness(PetType.MAGIC, PetType.RANGED), 0.001);
		assertEquals(0.5, chart.effectiveness(PetType.MELEE, PetType.MAGIC), 0.001);
	}

	@Test
	public void osrsFlavors()
	{
		// Dragon hunter crossbow: Ranged > Dragon
		assertEquals(2.0, chart.effectiveness(PetType.RANGED, PetType.DRAGON), 0.001);
		// Demonbane spells: Magic > Demon
		assertEquals(2.0, chart.effectiveness(PetType.MAGIC, PetType.DEMON), 0.001);
		// Fire cremates the Undead but Dragons shrug it off
		assertEquals(2.0, chart.effectiveness(PetType.FIRE, PetType.UNDEAD), 0.001);
		assertEquals(0.5, chart.effectiveness(PetType.FIRE, PetType.DRAGON), 0.001);
		// Ice freezes the firebreather
		assertEquals(2.0, chart.effectiveness(PetType.ICE, PetType.DRAGON), 0.001);
		// Skilling pets chop down Nature
		assertEquals(2.0, chart.effectiveness(PetType.SKILLING, PetType.NATURE), 0.001);
	}

	@Test
	public void dualTypesMultiply()
	{
		// Fire vs [Undead, Nature] = 2.0 * 2.0 = 4.0
		assertEquals(4.0, chart.effectiveness(PetType.FIRE,
			Arrays.asList(PetType.UNDEAD, PetType.NATURE)), 0.001);
		// Melee vs [Ranged, Magic] = 2.0 * 0.5 = 1.0
		assertEquals(1.0, chart.effectiveness(PetType.MELEE,
			Arrays.asList(PetType.RANGED, PetType.MAGIC)), 0.001);
	}

	@Test
	public void everyMatchupIsDefined()
	{
		for (PetType atk : PetType.values())
		{
			for (PetType def : PetType.values())
			{
				double eff = chart.effectiveness(atk, def);
				org.junit.Assert.assertTrue(atk + " vs " + def + " = " + eff,
					eff == 0.5 || eff == 1.0 || eff == 2.0);
			}
		}
	}
}
