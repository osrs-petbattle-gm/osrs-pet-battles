package com.petbattles.ui;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.ItemEffect;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.item.EquipItemDef;
import com.petbattles.persist.RosterManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: sync hint, active-team drawer (reorder anywhere, composition bank-gated),
 * trainer picker, and the searchable roster of pets not currently on the team.
 */
public class PetBattlesPanel extends PluginPanel
{
	static final String BANK_HINT = "Visit a bank to change your team";

	private final PetDatabase db;
	private final RosterManager roster;
	private final Sprites sprites;
	private final Consumer<String> fightAction;
	private final Runnable onRest;
	// "Is the player currently standing near this trainer in the world?" — unlocks the
	// first panel Fight against an undefeated trainer.
	private final Predicate<String> isNearTrainer;

	private final JLabel statusLabel = new JLabel();
	private final JLabel walletLabel = new JLabel();
	private final JButton storeButton = new JButton("Store");
	private final JLabel teamTitle = new JLabel();
	private final JPanel teamPanel = new JPanel();
	private final JLabel bankHintLabel = new JLabel();
	private final JButton restButton = new JButton("Rest pets");
	private final JButton resetProgressionButton = new JButton("Reset progression (dev)");
	private final JButton resetQuestsButton = new JButton("Reset quests (dev)");
	private final JComboBox<TrainerItem> trainerBox = new JComboBox<>();
	private final JButton fightButton = new JButton("Fight!");
	private final JTextField searchField = new JTextField();
	private final JPanel rosterList = new JPanel();

	private static class TrainerItem
	{
		final TrainerDef trainer;

		TrainerItem(TrainerDef trainer)
		{
			this.trainer = trainer;
		}

		@Override
		public String toString()
		{
			int maxLevel = trainer.getParty().stream().mapToInt(TrainerDef.PartyEntry::getLevel).max().orElse(1);
			return trainer.getName() + " (Lv " + maxLevel + ", " + trainer.getDifficulty() + ")";
		}
	}

	public PetBattlesPanel(PetDatabase db, RosterManager roster, Sprites sprites,
		Consumer<String> fightAction, Runnable onRest, Predicate<String> isNearTrainer)
	{
		this.db = db;
		this.roster = roster;
		this.sprites = sprites;
		this.fightAction = fightAction;
		this.onRest = onRest;
		this.isNearTrainer = isNearTrainer;

		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);

		JLabel title = new JLabel("Pet Battles");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(title);
		north.add(Box.createVerticalStrut(4));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(ColorScheme.BRAND_ORANGE);
		statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(statusLabel);
		north.add(Box.createVerticalStrut(4));

		JPanel wallet = new JPanel(new BorderLayout(6, 0));
		wallet.setOpaque(false);
		wallet.setAlignmentX(Component.LEFT_ALIGNMENT);
		wallet.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		walletLabel.setFont(FontManager.getRunescapeSmallFont());
		walletLabel.setForeground(new Color(255, 210, 90));
		wallet.add(walletLabel, BorderLayout.CENTER);
		storeButton.setFont(FontManager.getRunescapeSmallFont());
		storeButton.setMargin(new java.awt.Insets(1, 6, 1, 6));
		storeButton.setToolTipText("Spend coins on items and cosmetics");
		storeButton.addActionListener(e -> openStore());
		wallet.add(storeButton, BorderLayout.EAST);
		north.add(wallet);
		north.add(Box.createVerticalStrut(8));

		teamTitle.setFont(FontManager.getRunescapeBoldFont());
		teamTitle.setForeground(Color.WHITE);
		teamTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(teamTitle);

		teamPanel.setLayout(new BoxLayout(teamPanel, BoxLayout.Y_AXIS));
		teamPanel.setOpaque(false);
		teamPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(teamPanel);

		bankHintLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.ITALIC, 12f));
		bankHintLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		bankHintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(bankHintLabel);
		north.add(Box.createVerticalStrut(2));

		restButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		restButton.setFont(FontManager.getRunescapeSmallFont());
		restButton.addActionListener(e ->
		{
			if (roster.restAllPets())
			{
				onRest.run();
			}
			refresh();
		});
		north.add(restButton);

		resetProgressionButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		resetProgressionButton.setFont(FontManager.getRunescapeSmallFont());
		resetProgressionButton.setToolTipText("Testing: wipe every pet's level, XP and moveset back to level 1. "
			+ "Keeps which pets you own and your team.");
		resetProgressionButton.setVisible(false);
		resetProgressionButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(this,
				"Reset ALL pets to level 1?\nThis wipes every pet's XP, level and moveset.\n"
					+ "Owned pets and your team are kept.",
				"Reset progression", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (confirm == JOptionPane.YES_OPTION)
			{
				roster.resetProgression();
				refresh();
			}
		});
		north.add(Box.createVerticalStrut(2));
		north.add(resetProgressionButton);

		resetQuestsButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		resetQuestsButton.setFont(FontManager.getRunescapeSmallFont());
		resetQuestsButton.setToolTipText("Testing: wipe all quest progress back to not-started. "
			+ "Relocks quest rewards (e.g. remote battles / the Remote Battle Device).");
		resetQuestsButton.setVisible(false);
		resetQuestsButton.addActionListener(e ->
		{
			int confirm = JOptionPane.showConfirmDialog(this,
				"Reset ALL quest progress?\nThis sets every quest back to not-started and\n"
					+ "relocks their rewards (e.g. remote battles).",
				"Reset quests", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (confirm == JOptionPane.YES_OPTION)
			{
				roster.resetQuests();
				refresh();
			}
		});
		north.add(Box.createVerticalStrut(2));
		north.add(resetQuestsButton);
		north.add(Box.createVerticalStrut(6));

		trainerBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		trainerBox.setFont(FontManager.getRunescapeSmallFont());
		trainerBox.addActionListener(e -> refresh());
		north.add(trainerBox);
		north.add(Box.createVerticalStrut(4));

		fightButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		fightButton.setFont(FontManager.getRunescapeSmallFont());
		fightButton.addActionListener(e ->
		{
			TrainerItem item = (TrainerItem) trainerBox.getSelectedItem();
			if (item != null)
			{
				fightAction.accept(item.trainer.getId());
			}
		});
		north.add(fightButton);
		north.add(Box.createVerticalStrut(8));

		JLabel rosterTitle = new JLabel("Available pets");
		rosterTitle.setFont(FontManager.getRunescapeBoldFont());
		rosterTitle.setForeground(Color.WHITE);
		rosterTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(rosterTitle);
		north.add(Box.createVerticalStrut(4));

		searchField.setFont(FontManager.getRunescapeSmallFont());
		searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
		searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		searchField.setToolTipText("Search pets by name");
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				refresh();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				refresh();
			}
		});
		north.add(searchField);

		add(north, BorderLayout.NORTH);

		rosterList.setLayout(new BoxLayout(rosterList, BoxLayout.Y_AXIS));
		rosterList.setOpaque(false);
		add(rosterList, BorderLayout.CENTER);

		DefaultComboBoxModel<TrainerItem> model = new DefaultComboBoxModel<>();
		for (TrainerDef trainer : db.allTrainers())
		{
			model.addElement(new TrainerItem(trainer));
		}
		trainerBox.setModel(model);

		refresh();
	}

	/**
	 * Rebuild all dynamic content. Safe to call from any thread. The search field
	 * itself is never rebuilt, so the user's query survives refreshes.
	 */
	public void refresh()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refresh);
			return;
		}

		boolean loggedIn = roster.isLoaded();
		long ownedCount = db.allSpecies().stream().filter(s -> roster.isOwned(s.getId())).count();
		if (!loggedIn)
		{
			statusLabel.setText("<html>Log in to load your pets.</html>");
		}
		else if (ownedCount == 0)
		{
			statusLabel.setText("<html>Open your Collection Log (Other &gt; All Pets) to sync your pets.</html>");
		}
		else
		{
			statusLabel.setText(ownedCount + " / " + db.allSpecies().size() + " pets unlocked");
		}

		walletLabel.setText(loggedIn ? "Coins: " + roster.getCoins() : " ");
		storeButton.setEnabled(loggedIn);

		List<String> team = roster.getTeam();
		boolean canEditTeam = loggedIn && roster.canEditTeam();
		teamTitle.setText("Active team (" + team.size() + "/" + RosterManager.MAX_TEAM_SIZE + ")");
		bankHintLabel.setText(canEditTeam || !loggedIn ? " " : BANK_HINT);

		rebuildTeamRows(team, canEditTeam);

		resetProgressionButton.setVisible(loggedIn && roster.isDevSelectEnabled());
		resetQuestsButton.setVisible(loggedIn && roster.isDevSelectEnabled());

		boolean injured = loggedIn && roster.anyPetInjured();
		restButton.setEnabled(canEditTeam && injured);
		restButton.setToolTipText(!loggedIn ? null
			: !canEditTeam ? "Visit a bank to rest your pets"
			: !injured ? "All pets are rested" : "Restore every pet to full HP");

		// The first fight against a trainer must be earned in the world: stand near them,
		// or beat them once (remote re-fights then unlock permanently). The dev remote-battles
		// toggle bypasses this entirely.
		TrainerItem selected = (TrainerItem) trainerBox.getSelectedItem();
		boolean unlocked = selected != null
			&& (roster.isTrainerDefeated(selected.trainer.getId())
				|| roster.isRemoteBattlesUnlocked()
				|| isNearTrainer.test(selected.trainer.getId()));
		boolean canFight = loggedIn && !team.isEmpty() && roster.teamCanFight() && unlocked;
		fightButton.setEnabled(canFight);
		fightButton.setToolTipText(team.isEmpty() ? "Add a pet to your team first"
			: loggedIn && !roster.teamCanFight() ? "Your team is knocked out — rest at a bank"
			: loggedIn && !unlocked && selected != null
				? "Get near " + selected.trainer.getName() + " to challenge them for the first time"
			: null);

		rebuildAvailableList(team, loggedIn, canEditTeam);
	}

	private void rebuildTeamRows(List<String> team, boolean canEditTeam)
	{
		teamPanel.removeAll();
		if (team.isEmpty())
		{
			JLabel empty = new JLabel("No pets on the team yet");
			empty.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.ITALIC, 12f));
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			teamPanel.add(empty);
		}
		for (int i = 0; i < team.size(); i++)
		{
			String speciesId = team.get(i);
			SpeciesDef species = db.species(speciesId);
			if (species == null)
			{
				continue;
			}
			teamPanel.add(teamRow(speciesId, species, i, team.size(), canEditTeam));
		}
		teamPanel.revalidate();
		teamPanel.repaint();
	}

	private JPanel teamRow(String speciesId, SpeciesDef species, int index, int teamSize, boolean canEditTeam)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setOpaque(true);
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 2));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

		PetInstance pet = roster.getPet(speciesId);
		boolean fainted = pet != null && pet.isFainted();
		boolean hurt = pet != null && !fainted && pet.getCurrentHp() != null;
		String rowName = pet != null ? species.nameFor(pet.getActiveVariantId(), pet.getLevel()) : species.getName();
		JLabel name = new JLabel((index + 1) + ". " + rowName
			+ (pet != null ? "  Lv " + pet.getLevel() : "")
			+ (fainted ? "  — KO" : hurt ? "  (" + pet.getCurrentHp() + " hp)" : ""));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(fainted ? ColorScheme.PROGRESS_ERROR_COLOR
			: hurt ? ColorScheme.PROGRESS_INPROGRESS_COLOR : Color.WHITE);
		name.setToolTipText(fainted ? "Fainted — rest at a bank before battling" : null);
		row.add(name, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		buttons.setOpaque(false);

		String held = pet != null ? heldItemName(pet) : null;
		JButton item = smallButton("◈", held != null ? "Held: " + held + " — click to change" : "Equip a held item");
		item.setForeground(held != null ? new Color(255, 210, 90) : Color.WHITE);
		item.addActionListener(e -> openHeldItemEditor(speciesId));
		buttons.add(item);

		JButton up = smallButton("↑", "Send out earlier");
		up.setEnabled(index > 0);
		up.addActionListener(e ->
		{
			roster.moveTeamMember(speciesId, -1);
			refresh();
		});
		buttons.add(up);

		JButton down = smallButton("↓", "Send out later");
		down.setEnabled(index < teamSize - 1);
		down.addActionListener(e ->
		{
			roster.moveTeamMember(speciesId, +1);
			refresh();
		});
		buttons.add(down);

		JButton remove = smallButton("✕", canEditTeam ? "Remove from team" : BANK_HINT);
		remove.setEnabled(canEditTeam);
		remove.addActionListener(e ->
		{
			roster.removeFromTeam(speciesId);
			refresh();
		});
		buttons.add(remove);

		row.add(buttons, BorderLayout.EAST);
		return row;
	}

	private static JButton smallButton(String label, String tooltip)
	{
		JButton button = new JButton(label);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setMargin(new java.awt.Insets(0, 4, 0, 4));
		button.setToolTipText(tooltip);
		return button;
	}

	private void rebuildAvailableList(List<String> team, boolean loggedIn, boolean canEditTeam)
	{
		rosterList.removeAll();
		boolean teamFull = team.size() >= RosterManager.MAX_TEAM_SIZE;
		boolean canJoin = loggedIn && canEditTeam && !teamFull;
		String joinTooltip = !canEditTeam ? BANK_HINT : teamFull ? "Team is full" : null;
		String query = searchField.getText().trim().toLowerCase(Locale.ROOT);

		PetCard.Listener listener = new PetCard.Listener()
		{
			@Override
			public void onJoinTeam(String speciesId)
			{
				roster.addToTeam(speciesId);
				refresh();
			}

			@Override
			public void onEditMoves(String speciesId)
			{
				openMoveEditor(speciesId);
			}

			@Override
			public void onEditHeldItem(String speciesId)
			{
				openHeldItemEditor(speciesId);
			}

			@Override
			public void onToggleDevUnlock(String speciesId)
			{
				if (roster.isDevUnlocked(speciesId))
				{
					roster.devLock(speciesId);
				}
				else
				{
					roster.devUnlock(speciesId);
				}
				refresh();
			}
		};
		boolean devMode = loggedIn && roster.isDevSelectEnabled();
		for (SpeciesDef species : db.allSpecies())
		{
			if (team.contains(species.getId()))
			{
				continue; // promoted to the team drawer above
			}
			if (!query.isEmpty() && !species.getName().toLowerCase(Locale.ROOT).contains(query))
			{
				continue;
			}
			boolean owned = loggedIn && roster.isOwned(species.getId());
			boolean devUnlocked = owned && roster.isDevUnlocked(species.getId());
			PetInstance pet = owned ? roster.getOrCreatePet(species.getId()) : null;
			rosterList.add(new PetCard(species, pet, owned,
				canJoin, joinTooltip, devMode, devUnlocked, heldItemName(pet), sprites, listener));
		}
		rosterList.revalidate();
		rosterList.repaint();
	}

	private void openMoveEditor(String speciesId)
	{
		SpeciesDef species = db.species(speciesId);
		PetInstance pet = roster.getOrCreatePet(speciesId);
		if (species == null || pet == null)
		{
			return;
		}

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		JLabel hint = new JLabel("Equip up to " + PetInstance.MAX_EQUIPPED_MOVES + " moves:");
		hint.setFont(FontManager.getRunescapeSmallFont());
		content.add(hint);
		content.add(Box.createVerticalStrut(6));

		List<String> available = pet.availableMoves(species);
		for (String moveId : available)
		{
			MoveDef move = db.move(moveId);
			if (move == null)
			{
				continue;
			}
			String label = move.getName() + "  [" + move.getType().getDisplayName()
				+ (move.getPower() > 0 ? ", " + move.getPower() + " pow" : ", status")
				+ ", " + move.getAccuracy() + "% acc]";
			JCheckBox box = new JCheckBox(label, pet.getEquippedMoves().contains(moveId));
			box.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 12f));
			box.addActionListener(e ->
			{
				if (box.isSelected())
				{
					if (!pet.equipMove(moveId))
					{
						box.setSelected(false);
					}
				}
				else
				{
					// Never allow an empty moveset
					if (pet.getEquippedMoves().size() <= 1)
					{
						box.setSelected(true);
					}
					else
					{
						pet.unequipMove(moveId);
					}
				}
				roster.petChanged();
			});
			content.add(box);
		}

		JOptionPane.showMessageDialog(this, content,
			species.nameFor(pet.getActiveVariantId(), pet.getLevel()) + " — Moves", JOptionPane.PLAIN_MESSAGE);
		refresh();
	}

	/** Display name of the pet's equipped held item, or null if it holds nothing / the id is stale. */
	private String heldItemName(PetInstance pet)
	{
		if (pet == null || pet.getHeldItemId() == null)
		{
			return null;
		}
		EquipItemDef item = db.equipItem(pet.getHeldItemId());
		return item == null ? null : item.getName();
	}

	/** Short description of a held item's stat effect, e.g. "+15% SPD" (or a cosmetic note). */
	private static String effectText(EquipItemDef item)
	{
		ItemEffect effect = item.getEffect();
		if (effect == null)
		{
			return item.isCosmetic() ? "cosmetic" : "no effect";
		}
		String sign = effect.getMagnitude() >= 0 ? "+" : "";
		return sign + effect.getMagnitude() + "% " + effect.getStat();
	}

	/**
	 * Pick or clear the pet's held item from the items it owns. Held items boost a stat in battle;
	 * only one may be worn at a time. Mirrors {@link #openMoveEditor} as a simple modal chooser.
	 */
	private void openHeldItemEditor(String speciesId)
	{
		SpeciesDef species = db.species(speciesId);
		PetInstance pet = roster.getOrCreatePet(speciesId);
		if (species == null || pet == null)
		{
			return;
		}

		List<EquipItemDef> owned = new ArrayList<>();
		for (Map.Entry<String, Integer> e : roster.getItemInventory().entrySet())
		{
			EquipItemDef item = db.equipItem(e.getKey());
			if (item != null && item.getSlot() == EquipItemDef.Slot.HELD && e.getValue() > 0)
			{
				owned.add(item);
			}
		}

		String title = species.nameFor(pet.getActiveVariantId(), pet.getLevel()) + " — Held item";
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		if (owned.isEmpty())
		{
			JLabel none = new JLabel("<html>You own no held items yet.<br>Buy one from the Store.</html>");
			none.setFont(FontManager.getRunescapeSmallFont());
			content.add(none);
			JOptionPane.showMessageDialog(this, content, title, JOptionPane.PLAIN_MESSAGE);
			return;
		}

		JLabel hint = new JLabel("Hold one item (boosts a stat in battle):");
		hint.setFont(FontManager.getRunescapeSmallFont());
		content.add(hint);
		content.add(Box.createVerticalStrut(6));

		ButtonGroup group = new ButtonGroup();
		String current = pet.getHeldItemId();

		JRadioButton noneBtn = new JRadioButton("None", current == null);
		noneBtn.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 12f));
		noneBtn.addActionListener(e ->
		{
			roster.clearHeldItem(speciesId);
			refresh();
		});
		group.add(noneBtn);
		content.add(noneBtn);

		for (EquipItemDef item : owned)
		{
			JRadioButton box = new JRadioButton(item.getName() + "  [" + effectText(item) + "]",
				item.getId().equals(current));
			box.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 12f));
			box.addActionListener(e ->
			{
				roster.setHeldItem(speciesId, item.getId());
				refresh();
			});
			group.add(box);
			content.add(box);
		}

		JOptionPane.showMessageDialog(this, content, title, JOptionPane.PLAIN_MESSAGE);
		refresh();
	}

	/**
	 * Spend coins on items and cosmetics. The coin sink that closes the reward loop. A modal list of
	 * everything currently sold; each row buys one and updates the wallet + affordability live.
	 */
	private void openStore()
	{
		if (!roster.isLoaded())
		{
			return;
		}

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel wallet = new JLabel();
		wallet.setFont(FontManager.getRunescapeSmallFont());
		wallet.setForeground(new Color(255, 210, 90));
		content.add(wallet);
		content.add(Box.createVerticalStrut(6));

		List<Runnable> syncers = new ArrayList<>();
		Runnable syncWallet = () -> wallet.setText("Coins: " + roster.getCoins());

		for (EquipItemDef item : db.allEquipItems())
		{
			if (!item.isSold())
			{
				continue;
			}
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));

			String kind = item.isCosmetic()
				? item.getSlot().name().toLowerCase(Locale.ROOT) + " cosmetic"
				: effectText(item);
			JLabel label = new JLabel(item.getName() + "  [" + kind + "]");
			label.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 12f));
			row.add(label, BorderLayout.CENTER);

			JButton buy = new JButton(item.getPrice() + " gp");
			buy.setFont(FontManager.getRunescapeSmallFont());
			buy.setMargin(new java.awt.Insets(1, 6, 1, 6));
			buy.addActionListener(e ->
			{
				if (roster.spendCoins(item.getPrice()))
				{
					roster.grantItem(item.getId(), 1);
					syncWallet.run();
					syncers.forEach(Runnable::run);
					refresh();
				}
			});
			row.add(buy, BorderLayout.EAST);
			content.add(row);
			content.add(Box.createVerticalStrut(2));

			syncers.add(() ->
			{
				int have = roster.itemCount(item.getId());
				boolean afford = roster.getCoins() >= item.getPrice();
				buy.setEnabled(afford);
				buy.setToolTipText(!afford ? "Not enough coins"
					: have > 0 ? "Owned ×" + have + " — buy another" : null);
			});
		}

		syncWallet.run();
		syncers.forEach(Runnable::run);

		JOptionPane.showMessageDialog(this, content, "Pet Battles Store", JOptionPane.PLAIN_MESSAGE);
		refresh();
	}
}
