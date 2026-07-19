package com.petbattles.engine;

import java.util.List;
import java.util.Map;

/**
 * Effectiveness matrix: attacker type -> defender type -> multiplier (2.0 / 1.0 / 0.5).
 * Dual-typed defenders multiply both columns.
 */
public class TypeChart
{
	private static final double S = 2.0; // super effective
	private static final double N = 1.0; // neutral
	private static final double W = 0.5; // not very effective

	// Row = attacking type, column = defending type; order matches PetType.values():
	// MEL   RNG   MAG   FIR   ICE   NAT   UND   DEM   DRG   SKL
	private static final double[][] DEFAULT =
	{
		{N,    S,    W,    N,    S,    N,    W,    N,    W,    S}, // MELEE
		{W,    N,    S,    N,    N,    W,    N,    N,    S,    S}, // RANGED
		{S,    W,    N,    W,    N,    N,    N,    S,    N,    S}, // MAGIC
		{N,    N,    W,    W,    S,    S,    S,    W,    W,    N}, // FIRE
		{N,    W,    N,    W,    W,    S,    N,    N,    S,    N}, // ICE
		{N,    S,    W,    W,    N,    W,    S,    N,    N,    W}, // NATURE
		{N,    N,    N,    W,    N,    S,    W,    W,    N,    S}, // UNDEAD
		{S,    N,    W,    S,    W,    N,    N,    W,    N,    S}, // DEMON
		{S,    W,    N,    W,    W,    S,    N,    S,    W,    S}, // DRAGON
		{W,    W,    W,    N,    N,    S,    N,    N,    N,    N}, // SKILLING
	};

	private final double[][] matrix;

	public TypeChart()
	{
		this(DEFAULT);
	}

	public TypeChart(double[][] matrix)
	{
		if (matrix.length != PetType.values().length)
		{
			throw new IllegalArgumentException("Type chart must have one row per type");
		}
		for (double[] row : matrix)
		{
			if (row.length != PetType.values().length)
			{
				throw new IllegalArgumentException("Type chart must have one column per type");
			}
		}
		this.matrix = matrix;
	}

	/**
	 * Build a chart from a JSON-friendly nested map keyed by type name; missing entries are neutral.
	 */
	public static TypeChart fromMap(Map<String, Map<String, Double>> map)
	{
		PetType[] types = PetType.values();
		double[][] m = new double[types.length][types.length];
		for (int a = 0; a < types.length; a++)
		{
			Map<String, Double> row = map.get(types[a].name());
			for (int d = 0; d < types.length; d++)
			{
				Double v = row == null ? null : row.get(types[d].name());
				m[a][d] = v == null ? N : v;
			}
		}
		return new TypeChart(m);
	}

	public double effectiveness(PetType attack, PetType defend)
	{
		return matrix[attack.ordinal()][defend.ordinal()];
	}

	public double effectiveness(PetType attack, List<PetType> defenderTypes)
	{
		double mult = 1.0;
		for (PetType t : defenderTypes)
		{
			mult *= effectiveness(attack, t);
		}
		return mult;
	}
}
