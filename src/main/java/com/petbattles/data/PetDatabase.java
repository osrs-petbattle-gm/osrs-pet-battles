package com.petbattles.data;

import com.petbattles.engine.MoveDef;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Indexed, immutable view of the loaded content.
 */
public class PetDatabase
{
	private final Map<String, SpeciesDef> speciesById;
	private final Map<String, MoveDef> movesById;
	private final Map<String, TrainerDef> trainersById;
	private final Map<Integer, SpeciesDef> speciesByItemId;
	private final Map<Integer, SpeciesDef> speciesByNpcId;
	private final TypeChart typeChart;

	public PetDatabase(Collection<SpeciesDef> species, Collection<MoveDef> moves,
		Collection<TrainerDef> trainers, TypeChart typeChart)
	{
		Map<String, SpeciesDef> sById = new LinkedHashMap<>();
		Map<Integer, SpeciesDef> sByItem = new LinkedHashMap<>();
		Map<Integer, SpeciesDef> sByNpc = new LinkedHashMap<>();
		for (SpeciesDef s : species)
		{
			sById.put(s.getId(), s);
			for (int itemId : s.getAllItemIds())
			{
				sByItem.put(itemId, s);
			}
			for (int npcId : s.getNpcIds())
			{
				sByNpc.put(npcId, s);
			}
		}
		Map<String, MoveDef> mById = new LinkedHashMap<>();
		for (MoveDef m : moves)
		{
			mById.put(m.getId(), m);
		}
		Map<String, TrainerDef> tById = new LinkedHashMap<>();
		for (TrainerDef t : trainers)
		{
			tById.put(t.getId(), t);
		}
		this.speciesById = Collections.unmodifiableMap(sById);
		this.movesById = Collections.unmodifiableMap(mById);
		this.trainersById = Collections.unmodifiableMap(tById);
		this.speciesByItemId = Collections.unmodifiableMap(sByItem);
		this.speciesByNpcId = Collections.unmodifiableMap(sByNpc);
		this.typeChart = typeChart;
	}

	public static PetDatabase load(ContentLoader loader)
	{
		return new PetDatabase(loader.loadSpecies(), loader.loadMoves(),
			loader.loadTrainers(), loader.loadTypeChart());
	}

	public Collection<SpeciesDef> allSpecies()
	{
		return speciesById.values();
	}

	public Collection<MoveDef> allMoves()
	{
		return movesById.values();
	}

	public Collection<TrainerDef> allTrainers()
	{
		return trainersById.values();
	}

	public SpeciesDef species(String id)
	{
		return speciesById.get(id);
	}

	public MoveDef move(String id)
	{
		return movesById.get(id);
	}

	public TrainerDef trainer(String id)
	{
		return trainersById.get(id);
	}

	public SpeciesDef speciesByItemId(int itemId)
	{
		return speciesByItemId.get(itemId);
	}

	public SpeciesDef speciesByNpcId(int npcId)
	{
		return speciesByNpcId.get(npcId);
	}

	public TypeChart getTypeChart()
	{
		return typeChart;
	}
}
