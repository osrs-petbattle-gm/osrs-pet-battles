package com.petbattles.ui;

import com.petbattles.data.PetDatabase;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.persist.RosterManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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
	// Whether a trainer is the current Random Battle challenge (fightable without being in-world).
	private final Predicate<String> isPendingChallenge;

	private final JLabel statusLabel = new JLabel();
	private final JLabel teamTitle = new JLabel();
	private final JPanel teamPanel = new JPanel();
	private final JLabel bankHintLabel = new JLabel();
	private final JButton restButton = new JButton("Rest pets");
	private final JButton resetProgressionButton = new JButton("Reset progression (dev)");
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
		Consumer<String> fightAction, Runnable onRest, Predicate<String> isNearTrainer,
		Predicate<String> isPendingChallenge)
	{
		this.db = db;
		this.roster = roster;
		this.sprites = sprites;
		this.fightAction = fightAction;
		this.onRest = onRest;
		this.isNearTrainer = isNearTrainer;
		this.isPendingChallenge = isPendingChallenge;

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

		List<String> team = roster.getTeam();
		boolean canEditTeam = loggedIn && roster.canEditTeam();
		teamTitle.setText("Active team (" + team.size() + "/" + RosterManager.MAX_TEAM_SIZE + ")");
		bankHintLabel.setText(canEditTeam || !loggedIn ? " " : BANK_HINT);

		rebuildTeamRows(team, canEditTeam);

		resetProgressionButton.setVisible(loggedIn && roster.isDevSelectEnabled());

		boolean injured = loggedIn && roster.anyPetInjured();
		restButton.setEnabled(canEditTeam && injured);
		restButton.setToolTipText(!loggedIn ? null
			: !canEditTeam ? "Visit a bank to rest your pets"
			: !injured ? "All pets are rested" : "Restore every pet to full HP");

		// The first fight against a trainer must be earned in the world: stand near them,
		// or beat them once (remote re-fights then unlock permanently). A pending Random Battle
		// challenge also unlocks that trainer. The dev remote-battles toggle bypasses this entirely.
		TrainerItem selected = (TrainerItem) trainerBox.getSelectedItem();
		boolean challenged = selected != null && isPendingChallenge.test(selected.trainer.getId());
		boolean unlocked = selected != null
			&& (challenged
				|| roster.isTrainerDefeated(selected.trainer.getId())
				|| roster.isDevRemoteBattlesEnabled()
				|| isNearTrainer.test(selected.trainer.getId()));
		boolean canFight = loggedIn && !team.isEmpty() && roster.teamCanFight() && unlocked;
		fightButton.setEnabled(canFight);
		fightButton.setToolTipText(team.isEmpty() ? "Add a pet to your team first"
			: loggedIn && !roster.teamCanFight() ? "Your team is knocked out — rest at a bank"
			: challenged ? "Random Battle challenge — fight them now!"
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
				canJoin, joinTooltip, devMode, devUnlocked, sprites, listener));
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
}
