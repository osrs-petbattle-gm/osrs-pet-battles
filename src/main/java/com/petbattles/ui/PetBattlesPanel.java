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
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;
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
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel: sync hint, team summary, trainer picker, and the full roster.
 */
public class PetBattlesPanel extends PluginPanel
{
	private final PetDatabase db;
	private final RosterManager roster;
	private final Sprites sprites;
	private final Consumer<String> fightAction;

	private final JLabel statusLabel = new JLabel();
	private final JLabel teamLabel = new JLabel();
	private final JComboBox<TrainerItem> trainerBox = new JComboBox<>();
	private final JButton fightButton = new JButton("Fight!");
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

	public PetBattlesPanel(PetDatabase db, RosterManager roster, Sprites sprites, Consumer<String> fightAction)
	{
		this.db = db;
		this.roster = roster;
		this.sprites = sprites;
		this.fightAction = fightAction;

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

		teamLabel.setFont(FontManager.getRunescapeSmallFont());
		teamLabel.setForeground(Color.WHITE);
		teamLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(teamLabel);
		north.add(Box.createVerticalStrut(6));

		trainerBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		trainerBox.setFont(FontManager.getRunescapeSmallFont());
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

		JLabel rosterTitle = new JLabel("Roster");
		rosterTitle.setFont(FontManager.getRunescapeBoldFont());
		rosterTitle.setForeground(Color.WHITE);
		rosterTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(rosterTitle);

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
	 * Rebuild all dynamic content. Safe to call from any thread.
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
		StringBuilder teamText = new StringBuilder("<html>Team (" + team.size() + "/" + RosterManager.MAX_TEAM_SIZE + "): ");
		if (team.isEmpty())
		{
			teamText.append("<i>empty</i>");
		}
		else
		{
			for (int i = 0; i < team.size(); i++)
			{
				SpeciesDef s = db.species(team.get(i));
				PetInstance p = roster.getPet(team.get(i));
				if (i > 0)
				{
					teamText.append(", ");
				}
				teamText.append(s.getName());
				if (p != null)
				{
					teamText.append(" (").append(p.getLevel()).append(")");
				}
			}
		}
		teamText.append("</html>");
		teamLabel.setText(teamText.toString());

		fightButton.setEnabled(loggedIn && !team.isEmpty());
		fightButton.setToolTipText(team.isEmpty() ? "Add a pet to your team first" : null);

		rosterList.removeAll();
		boolean teamFull = team.size() >= RosterManager.MAX_TEAM_SIZE;
		PetCard.Listener listener = new PetCard.Listener()
		{
			@Override
			public void onToggleTeam(String speciesId)
			{
				if (roster.getTeam().contains(speciesId))
				{
					roster.removeFromTeam(speciesId);
				}
				else
				{
					roster.addToTeam(speciesId);
				}
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
			boolean owned = loggedIn && roster.isOwned(species.getId());
			boolean devUnlocked = owned && roster.isDevUnlocked(species.getId());
			PetInstance pet = owned ? roster.getOrCreatePet(species.getId()) : null;
			rosterList.add(new PetCard(species, pet, owned,
				team.contains(species.getId()), teamFull, devMode, devUnlocked, sprites, listener));
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
			box.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 11f));
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
			species.getName() + " — Moves", JOptionPane.PLAIN_MESSAGE);
		refresh();
	}
}
