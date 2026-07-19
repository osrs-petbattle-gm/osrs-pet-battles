package com.petbattles.ui;

import com.petbattles.engine.Leveling;
import com.petbattles.engine.PetInstance;
import com.petbattles.engine.PetType;
import com.petbattles.engine.SpeciesDef;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * One roster row: icon, name, level, type badges, XP bar, team/move buttons.
 */
public class PetCard extends JPanel
{
	public interface Listener
	{
		void onJoinTeam(String speciesId);

		void onEditMoves(String speciesId);

		void onToggleDevUnlock(String speciesId);
	}

	public PetCard(SpeciesDef species, PetInstance pet, boolean owned, boolean canJoin,
		String joinDisabledTooltip, boolean devMode, boolean devUnlocked, Sprites sprites, Listener listener)
	{
		setLayout(new BorderLayout(6, 0));
		setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
			BorderFactory.createEmptyBorder(6, 6, 6, 6)));
		setBackground(owned ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.DARKER_GRAY_HOVER_COLOR);

		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		sprites.applyItemIcon(icon, species.getItemId());
		icon.setEnabled(owned);
		add(icon, BorderLayout.WEST);

		JPanel center = new JPanel();
		center.setOpaque(false);
		center.setLayout(new GridLayout(0, 1, 0, 2));

		JLabel name = new JLabel(species.getName()
			+ (owned && pet != null ? "  Lv " + pet.getLevel() : "")
			+ (devUnlocked ? "  (dev)" : ""));
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(owned ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR.darker());
		center.add(name);

		JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		badges.setOpaque(false);
		for (PetType type : species.getTypes())
		{
			JLabel badge = new JLabel(type.getDisplayName());
			badge.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 10f));
			badge.setForeground(Color.WHITE);
			badge.setOpaque(true);
			badge.setBackground(new Color(type.getColorRgb()));
			badge.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
			badges.add(badge);
		}
		center.add(badges);

		if (owned && pet != null)
		{
			int level = pet.getLevel();
			JProgressBar xpBar = new JProgressBar();
			if (level >= Leveling.MAX_LEVEL)
			{
				xpBar.setMaximum(1);
				xpBar.setValue(1);
				xpBar.setString("Max level");
			}
			else
			{
				long floor = Leveling.xpForLevel(level);
				long ceil = Leveling.xpForLevel(level + 1);
				xpBar.setMaximum((int) (ceil - floor));
				xpBar.setValue((int) (pet.getXp() - floor));
				xpBar.setString((pet.getXp() - floor) + " / " + (ceil - floor) + " xp");
			}
			xpBar.setStringPainted(true);
			xpBar.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.PLAIN, 9f));
			xpBar.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
			xpBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
			xpBar.setPreferredSize(new Dimension(100, 14));
			center.add(xpBar);

			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
			buttons.setOpaque(false);
			JButton teamBtn = new JButton("Join team");
			teamBtn.setFont(FontManager.getRunescapeSmallFont());
			teamBtn.setMargin(new java.awt.Insets(1, 4, 1, 4));
			teamBtn.setEnabled(canJoin);
			teamBtn.setToolTipText(canJoin ? null : joinDisabledTooltip);
			teamBtn.addActionListener(e -> listener.onJoinTeam(species.getId()));
			buttons.add(teamBtn);

			JButton movesBtn = new JButton("Moves");
			movesBtn.setFont(FontManager.getRunescapeSmallFont());
			movesBtn.setMargin(new java.awt.Insets(1, 4, 1, 4));
			movesBtn.addActionListener(e -> listener.onEditMoves(species.getId()));
			buttons.add(movesBtn);

			if (devUnlocked)
			{
				JButton lockBtn = new JButton("Lock");
				lockBtn.setFont(FontManager.getRunescapeSmallFont());
				lockBtn.setMargin(new java.awt.Insets(1, 4, 1, 4));
				lockBtn.setToolTipText("Remove this testing-only unlock");
				lockBtn.addActionListener(e -> listener.onToggleDevUnlock(species.getId()));
				buttons.add(lockBtn);
			}
			center.add(buttons);
		}
		else
		{
			JLabel locked = new JLabel(devMode
				? "Locked — not in your collection log"
				: "Not in your collection log yet");
			locked.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.ITALIC, 10f));
			locked.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
			center.add(locked);

			if (devMode)
			{
				JPanel devButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
				devButtons.setOpaque(false);
				JButton unlockBtn = new JButton("Unlock (dev)");
				unlockBtn.setFont(FontManager.getRunescapeSmallFont());
				unlockBtn.setMargin(new java.awt.Insets(1, 4, 1, 4));
				unlockBtn.setToolTipText("Unlock this pet for testing (not added to your real collection log)");
				unlockBtn.addActionListener(e -> listener.onToggleDevUnlock(species.getId()));
				devButtons.add(unlockBtn);
				center.add(devButtons);
			}
		}

		add(center, BorderLayout.CENTER);
	}
}
