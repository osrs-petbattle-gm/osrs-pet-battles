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
		// Mirror the scheduler's eligibility: random-event AND easy (an accessible wild challenger).
		List<TrainerDef> eligible = new ArrayList<>();
		for (TrainerDef t : db.allTrainers())
		{
			if (t.isRandomEvent() && t.getDifficulty() == TrainerDef.Difficulty.EASY)
			{
				eligible.add(t);
			}
		}
		assertFalse("at least one easy random-event trainer is authored", eligible.isEmpty());

		Random rng = new Random(42);
		for (int i = 0; i < 50; i++)
		{
			TrainerDef choice = RandomBattleScheduler.chooseChallenge(eligible, rng);
			assertNotNull(choice);
			assertTrue("chosen trainer must be random-event: " + choice.getId(), choice.isRandomEvent());
			assertTrue("chosen trainer must be easy: " + choice.getId(),
				choice.getDifficulty() == TrainerDef.Difficulty.EASY);
		}
	}

	@Test
	public void noHardTrainerIsFlaggedAsARandomEvent()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		for (TrainerDef t : db.allTrainers())
		{
			if (t.isRandomEvent())
			{
				assertTrue("random-event trainers must be easy so the cadence stays accessible: " + t.getId(),
					t.getDifficulty() == TrainerDef.Difficulty.EASY);
			}
		}
	}
}
