package com.petbattles.persist;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The soft-currency wallet on {@link RosterManager}. Uses the real bundled content plus an
 * in-memory {@link RosterStore} so no RuneLite ConfigManager is needed.
 */
public class RosterManagerCoinsTest
{
	private RosterManager loadedManager()
	{
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		RosterStore store = new RosterStore(null, null)
		{
			private RosterData data = new RosterData();

			@Override
			public RosterData load()
			{
				return data;
			}

			@Override
			public void save(RosterData d)
			{
				this.data = d;
			}
		};
		RosterManager roster = new RosterManager(db, store);
		roster.load();
		return roster;
	}

	@Test
	public void walletStartsEmpty()
	{
		assertEquals(0, loadedManager().getCoins());
	}

	@Test
	public void addCoinsAccumulatesAndIgnoresNonPositive()
	{
		RosterManager roster = loadedManager();
		assertEquals(30, roster.addCoins(30));
		assertEquals(50, roster.addCoins(20));
		// Non-positive amounts are no-ops (never drain the wallet).
		assertEquals(50, roster.addCoins(0));
		assertEquals(50, roster.addCoins(-15));
		assertEquals(50, roster.getCoins());
	}

	@Test
	public void spendCoinsDeductsOnlyWhenAffordable()
	{
		RosterManager roster = loadedManager();
		roster.addCoins(100);
		assertTrue(roster.spendCoins(40));
		assertEquals(60, roster.getCoins());
		// Can't overspend, and the balance is untouched on a failed spend.
		assertFalse(roster.spendCoins(61));
		assertEquals(60, roster.getCoins());
		// Non-positive spends are rejected too.
		assertFalse(roster.spendCoins(0));
		assertFalse(roster.spendCoins(-5));
		assertEquals(60, roster.getCoins());
		// Spending the exact balance is allowed.
		assertTrue(roster.spendCoins(60));
		assertEquals(0, roster.getCoins());
	}
}
