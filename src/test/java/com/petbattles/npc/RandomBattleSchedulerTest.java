package com.petbattles.npc;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.TrainerDef;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Random Battle selection: only random-event trainers are ever chosen, and an empty pool is safe.
 */
public class RandomBattleSchedulerTest
{
	@Test
	public void emptyEligiblePoolChoosesNothing()
	{
		assertNull(RandomBattleScheduler.chooseChallenge(new ArrayList<>(), new Random(1)));
	}

	@Test
	public void onlyEligibleTrainersAreChosen()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		List<TrainerDef> eligible = new ArrayList<>();
		for (TrainerDef t : db.allTrainers())
		{
			if (t.isRandomEvent())
			{
				eligible.add(t);
			}
		}
		assertFalse("at least one random-event trainer is authored", eligible.isEmpty());

		Random rng = new Random(42);
		for (int i = 0; i < 50; i++)
		{
			TrainerDef choice = RandomBattleScheduler.chooseChallenge(eligible, rng);
			assertNotNull(choice);
			assertTrue("chosen trainer must be random-event: " + choice.getId(), choice.isRandomEvent());
		}
	}
}
