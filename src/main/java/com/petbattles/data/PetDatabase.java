package com.petbattles.data;

import com.petbattles.engine.MoveDef;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import com.petbattles.item.EquipItemDef;
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
	// item/npc id -> the variant id it identifies (base-form ids never appear here)
	private final Map<Integer, String> variantIdByItemId;
	private final Map<Integer, String> variantIdByNpcId;
	private final Map<String, EquipItemDef> equipItemsById;
	private final TypeChart typeChart;

	public PetDatabase(Collection<SpeciesDef> species, Collection<MoveDef> moves,
		Collection<TrainerDef> trainers, Collection<EquipItemDef> equipItems, TypeChart typeChart)
	{
		Map<String, SpeciesDef> sById = new LinkedHashMap<>();
		Map<Integer, SpeciesDef> sByItem = new LinkedHashMap<>();
		Map<Integer, SpeciesDef> sByNpc = new LinkedHashMap<>();
		Map<Integer, String> vByItem = new LinkedHashMap<>();
		Map<Integer, String> vByNpc = new LinkedHashMap<>();
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
			// Variant ids resolve to the same species (for ownership) but also tag which form is
			// active, so the follower/inventory trackers can flip the pet's variant on detection.
			for (SpeciesDef.Variant v : s.getVariants())
			{
				for (int itemId : v.getUnlockItemIds())
				{
					sByItem.put(itemId, s);
					vByItem.put(itemId, v.getId());
				}
				if (v.getItemId() > 0)
				{
					sByItem.put(v.getItemId(), s);
					vByItem.put(v.getItemId(), v.getId());
				}
				for (int npcId : v.getNpcIds())
				{
					sByNpc.put(npcId, s);
					vByNpc.put(npcId, v.getId());
				}
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
		Map<String, EquipItemDef> eById = new LinkedHashMap<>();
		for (EquipItemDef e : equipItems)
		{
			eById.put(e.getId(), e);
		}
		this.speciesById = Collections.unmodifiableMap(sById);
		this.movesById = Collections.unmodifiableMap(mById);
		this.trainersById = Collections.unmodifiableMap(tById);
		this.speciesByItemId = Collections.unmodifiableMap(sByItem);
		this.speciesByNpcId = Collections.unmodifiableMap(sByNpc);
		this.variantIdByItemId = Collections.unmodifiableMap(vByItem);
		this.variantIdByNpcId = Collections.unmodifiableMap(vByNpc);
		this.equipItemsById = Collections.unmodifiableMap(eById);
		this.typeChart = typeChart;
	}

	public static PetDatabase load(ContentLoader loader)
	{
		return new PetDatabase(loader.loadSpecies(), loader.loadMoves(),
			loader.loadTrainers(), loader.loadEquipItems(), loader.loadTypeChart());
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

	public EquipItemDef equipItem(String id)
	{
		return equipItemsById.get(id);
	}

	public Collection<EquipItemDef> allEquipItems()
	{
		return equipItemsById.values();
	}

	public SpeciesDef speciesByItemId(int itemId)
	{
		return speciesByItemId.get(itemId);
	}

	public SpeciesDef speciesByNpcId(int npcId)
	{
		return speciesByNpcId.get(npcId);
	}

	/**
	 * The variant id this item id identifies, or null if the id is a base-form (or unknown) id.
	 */
	public String variantByItemId(int itemId)
	{
		return variantIdByItemId.get(itemId);
	}

	/**
	 * The variant id this npc id identifies, or null if the id is a base-form (or unknown) id.
	 */
	public String variantByNpcId(int npcId)
	{
		return variantIdByNpcId.get(npcId);
	}

	public TypeChart getTypeChart()
	{
		return typeChart;
	}
}
