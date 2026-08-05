package com.petbattles.ui;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.TrainerDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.persist.RosterManager;
import java.util.function.Consumer;

/**
 * Maps a hub button's action string to a state change on a {@link HubView} or a roster mutation /
 * callback. Rendering-agnostic and thread-agnostic: {@link RosterManager} is fully synchronised, so
 * this is safe to call from the overlay's client thread or the panel's EDT. The client-touching
 * callbacks ({@code fight} / {@code locate} / {@code examine}) are expected to marshal themselves
 * onto the client thread. Shared by both hub surfaces ({@link HubInputHandler} and {@link HubPanel}).
 */
public class HubActions
{
	private final HubView view;
	private final PetDatabase db;
	private final RosterManager roster;
	private final Consumer<String> fightAction;
	private final Consumer<String> locateAction;
	private final Consumer<String> examineAction;
	private final Runnable onRest;

	public HubActions(HubView view, PetDatabase db, RosterManager roster, Consumer<String> fightAction,
		Consumer<String> locateAction, Consumer<String> examineAction, Runnable onRest)
	{
		this.view = view;
		this.db = db;
		this.roster = roster;
		this.fightAction = fightAction;
		this.locateAction = locateAction;
		this.examineAction = examineAction;
		this.onRest = onRest;
	}

	public void dispatch(String action)
	{
		// Any hub action other than typing into / clearing the search field drops its focus.
		if (!"trainers.search.focus".equals(action) && !"trainers.search.clear".equals(action))
		{
			view.blurSearch();
		}
		if ("chip".equals(action) || "menu".equals(action))
		{
			view.openMenu();
		}
		else if ("collapse".equals(action))
		{
			view.collapse();
		}
		else if ("open:team".equals(action))
		{
			view.openPane(HubView.Pane.TEAM);
		}
		else if ("open:challenge".equals(action))
		{
			view.openPane(HubView.Pane.CHALLENGE);
		}
		else if ("open:trainers".equals(action))
		{
			view.openPane(HubView.Pane.TRAINERS);
		}
		else if ("open:quests".equals(action))
		{
			view.openPane(HubView.Pane.QUESTS);
		}
		else if ("open:items".equals(action))
		{
			view.openPane(HubView.Pane.ITEMS);
		}
		else if ("open:store".equals(action))
		{
			view.openPane(HubView.Pane.STORE);
		}
		else if ("open:dev".equals(action))
		{
			view.openPane(HubView.Pane.DEV);
		}
		else if ("open:devpets".equals(action))
		{
			view.openPane(HubView.Pane.DEVPETS);
		}
		else if ("devpets.page:1".equals(action))
		{
			view.devPetsPage(+1);
		}
		else if ("devpets.page:-1".equals(action))
		{
			view.devPetsPage(-1);
		}
		else if (action.startsWith("open:pet:"))
		{
			view.openPet(action.substring("open:pet:".length()));
		}
		else if (action.startsWith("equip.open:"))
		{
			view.openEquip(action.substring("equip.open:".length()));
		}
		else if (action.startsWith("store.buy:"))
		{
			buy(action.substring("store.buy:".length()));
		}
		else if ("store.page:1".equals(action))
		{
			view.storePage(+1);
		}
		else if ("store.page:-1".equals(action))
		{
			view.storePage(-1);
		}
		else if (action.startsWith("pet.held.clear:"))
		{
			roster.clearHeldItem(action.substring("pet.held.clear:".length()));
		}
		else if (action.startsWith("pet.held:"))
		{
			String[] parts = action.substring("pet.held:".length()).split(":", 2);
			if (parts.length == 2)
			{
				roster.setHeldItem(parts[0], parts[1]);
			}
		}
		else if (action.startsWith("pet.move:"))
		{
			String[] parts = action.substring("pet.move:".length()).split(":", 2);
			if (parts.length == 2)
			{
				toggleMove(parts[0], parts[1]);
			}
		}
		else if (action.startsWith("dev.unlock:"))
		{
			roster.devUnlock(action.substring("dev.unlock:".length()));
		}
		else if (action.startsWith("dev.lock:"))
		{
			roster.devLock(action.substring("dev.lock:".length()));
		}
		else if (action.startsWith("dev.confirm:"))
		{
			view.armConfirm(action.substring("dev.confirm:".length()));
		}
		else if ("dev.cancel".equals(action))
		{
			view.clearConfirm();
		}
		else if (action.startsWith("dev.reset:"))
		{
			String key = action.substring("dev.reset:".length());
			if ("progression".equals(key))
			{
				roster.resetProgression();
			}
			else if ("quests".equals(key))
			{
				roster.resetQuests();
			}
			view.clearConfirm();
		}
		else if (action.startsWith("quest:"))
		{
			view.toggleQuest(action.substring(6));
		}
		else if (action.startsWith("trainers.filter:"))
		{
			String value = action.substring(16);
			if ("RANDOM".equals(value))
			{
				view.setRandomFilter();
			}
			else
			{
				view.setTrainerFilter("ALL".equals(value) ? null : TrainerDef.Difficulty.valueOf(value));
			}
		}
		else if ("trainers.page:1".equals(action))
		{
			view.trainersPage(+1);
		}
		else if ("trainers.page:-1".equals(action))
		{
			view.trainersPage(-1);
		}
		else if ("trainers.search.focus".equals(action))
		{
			view.focusSearch();
		}
		else if ("trainers.search.clear".equals(action))
		{
			view.clearSearch();
		}
		else if (action.startsWith("locate:"))
		{
			locateAction.accept(action.substring(7));
		}
		else if (action.startsWith("item.examine:"))
		{
			examineAction.accept(action.substring("item.examine:".length()));
		}
		else if (action.startsWith("team.remove:"))
		{
			roster.removeFromTeam(action.substring(12));
		}
		else if (action.startsWith("team.add:"))
		{
			roster.addToTeam(action.substring(9));
		}
		else if ("team.page:1".equals(action))
		{
			view.addPage(+1);
		}
		else if ("team.page:-1".equals(action))
		{
			view.addPage(-1);
		}
		else if ("rest".equals(action))
		{
			if (roster.restAllPets())
			{
				onRest.run();
			}
		}
		else if (action.startsWith("fight:"))
		{
			fightAction.accept(action.substring(6));
		}
		else if ("chal.page:1".equals(action))
		{
			view.challengePageDelta(+1);
		}
		else if ("chal.page:-1".equals(action))
		{
			view.challengePageDelta(-1);
		}
	}

	/** Buy one of a sold equip item: spend its price, then grant it. No-op if unknown/unaffordable. */
	private void buy(String itemId)
	{
		EquipItemDef item = db.equipItem(itemId);
		if (item != null && item.isSold() && roster.spendCoins(item.getPrice()))
		{
			roster.grantItem(item.getId(), 1);
		}
	}

	/** Toggle a move in the pet's loadout, keeping at least one equipped and at most the cap. */
	private void toggleMove(String speciesId, String moveId)
	{
		PetInstance pet = roster.getOrCreatePet(speciesId);
		if (pet == null)
		{
			return;
		}
		if (pet.getEquippedMoves().contains(moveId))
		{
			if (pet.getEquippedMoves().size() > 1)
			{
				pet.unequipMove(moveId);
				roster.petChanged();
			}
		}
		else if (pet.equipMove(moveId))
		{
			roster.petChanged();
		}
	}
}
