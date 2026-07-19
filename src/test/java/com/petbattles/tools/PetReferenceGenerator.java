package com.petbattles.tools;

import com.google.gson.Gson;
import com.petbattles.data.ContentLoader;
import com.petbattles.data.PetDatabase;
import com.petbattles.engine.LearnsetEntry;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.MoveEffect;
import com.petbattles.engine.PetType;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.Stats;
import com.petbattles.engine.TypeChart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Build-time tool (never on the plugin classpath): renders every species, its
 * learnset, and the type chart to a single HTML page for QA and reference.
 * Run via {@code ./gradlew generatePetReference}; writes into build/ only.
 */
public final class PetReferenceGenerator
{
	private PetReferenceGenerator()
	{
	}

	public static void main(String[] args) throws IOException
	{
		Path out = Paths.get(args.length > 0 ? args[0] : "build/pets.html");
		PetDatabase db = PetDatabase.load(new ContentLoader(new Gson()));
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.write(out, render(db).getBytes(StandardCharsets.UTF_8));
		System.out.println("Wrote " + out.toAbsolutePath() + " ("
			+ db.allSpecies().size() + " species, " + db.allMoves().size() + " moves)");
	}

	private static String render(PetDatabase db)
	{
		StringBuilder html = new StringBuilder();
		html.append("<!doctype html><html><head><meta charset=\"utf-8\">")
			.append("<title>Pet Battles — Reference</title><style>")
			.append("body{font-family:sans-serif;background:#16181c;color:#e6e1d2;margin:20px}")
			.append("h1,h2{color:#f0dc78}")
			.append(".cards{display:flex;flex-wrap:wrap;gap:12px}")
			.append(".card{background:#22262c;border:1px solid #5a4b28;border-radius:8px;padding:10px;width:330px}")
			.append(".card h3{margin:0 0 4px}")
			.append(".badge{display:inline-block;padding:1px 7px;border-radius:4px;color:#fff;font-size:11px;margin-right:4px}")
			.append(".meta{color:#9a948a;font-size:11px;margin:4px 0}")
			.append("table{border-collapse:collapse;font-size:12px;width:100%;margin-top:6px}")
			.append("td,th{border:1px solid #3a3f46;padding:2px 6px;text-align:left}")
			.append("th{background:#2b3038}")
			.append(".icon{width:32px;height:32px;vertical-align:middle;margin-right:6px}")
			.append(".s{color:#5cbf60}.w{color:#d16a5a}")
			.append("</style></head><body>");
		html.append("<h1>Pet Battles — Reference</h1>");
		html.append("<p class=\"meta\">Generated at build time from species.json / moves.json / typechart.json. ")
			.append(db.allSpecies().size()).append(" species, ")
			.append(db.allMoves().size()).append(" moves.</p>");

		// Species cards grouped by primary type
		for (PetType group : PetType.values())
		{
			StringBuilder cards = new StringBuilder();
			for (SpeciesDef species : db.allSpecies())
			{
				if (species.getTypes().isEmpty() || species.getTypes().get(0) != group)
				{
					continue;
				}
				cards.append(card(db, species));
			}
			if (cards.length() > 0)
			{
				html.append("<h2>").append(esc(group.getDisplayName())).append("</h2>")
					.append("<div class=\"cards\">").append(cards).append("</div>");
			}
		}

		html.append(typeChart(db.getTypeChart()));
		html.append("</body></html>");
		return html.toString();
	}

	private static String card(PetDatabase db, SpeciesDef species)
	{
		StringBuilder card = new StringBuilder();
		card.append("<div class=\"card\"><h3>")
			.append("<img class=\"icon\" alt=\"\" loading=\"lazy\" src=\"https://static.runelite.net/cache/item/icon/")
			.append(species.getItemId()).append(".png\">")
			.append(esc(species.getName())).append("</h3>");
		for (PetType type : species.getTypes())
		{
			card.append(badge(type));
		}
		card.append("<div class=\"meta\">id: ").append(esc(species.getId()))
			.append(" · item: ").append(species.getItemId());
		if (!species.getAltItemIds().isEmpty())
		{
			card.append(" (alt: ").append(esc(species.getAltItemIds().toString())).append(")");
		}
		card.append("</div>");
		Stats base = species.getBase();
		card.append("<div class=\"meta\">HP ").append(base.getHp())
			.append(" · Atk ").append(base.getAtk())
			.append(" · Def ").append(base.getDef())
			.append(" · Spd ").append(base.getSpd()).append("</div>");

		card.append("<table><tr><th>Lv</th><th>Move</th><th>Type</th><th>Pow</th><th>Acc</th><th>Effect</th></tr>");
		for (LearnsetEntry entry : species.getLearnset())
		{
			MoveDef move = db.move(entry.getMove());
			if (move == null)
			{
				card.append("<tr><td>").append(entry.getLevel())
					.append("</td><td colspan=\"5\" class=\"w\">UNKNOWN MOVE: ")
					.append(esc(entry.getMove())).append("</td></tr>");
				continue;
			}
			card.append("<tr><td>").append(entry.getLevel()).append("</td><td>")
				.append(esc(move.getName())).append("</td><td>").append(badge(move.getType()))
				.append("</td><td>").append(move.getPower() > 0 ? move.getPower() : "—")
				.append("</td><td>").append(move.getAccuracy()).append("%</td><td>")
				.append(effectText(move)).append("</td></tr>");
		}
		card.append("</table></div>");
		return card.toString();
	}

	private static String effectText(MoveDef move)
	{
		if (move.getEffect() == MoveEffect.NONE)
		{
			return "—";
		}
		return esc(move.getEffect().name()) + (move.getEffectChance() < 100
			? " (" + move.getEffectChance() + "%)" : "");
	}

	private static String typeChart(TypeChart chart)
	{
		StringBuilder html = new StringBuilder("<h2>Type chart</h2><table><tr><th>atk \\ def</th>");
		for (PetType def : PetType.values())
		{
			html.append("<th>").append(esc(def.getDisplayName())).append("</th>");
		}
		html.append("</tr>");
		for (PetType atk : PetType.values())
		{
			html.append("<tr><th>").append(esc(atk.getDisplayName())).append("</th>");
			for (PetType def : PetType.values())
			{
				double eff = chart.effectiveness(atk, def);
				String cls = eff > 1.0 ? "s" : eff < 1.0 ? "w" : "";
				html.append("<td class=\"").append(cls).append("\">").append(eff).append("</td>");
			}
			html.append("</tr>");
		}
		return html.append("</table>").toString();
	}

	private static String badge(PetType type)
	{
		return "<span class=\"badge\" style=\"background:#"
			+ String.format("%06x", type.getColorRgb() & 0xFFFFFF) + "\">"
			+ esc(type.getDisplayName()) + "</span>";
	}

	private static String esc(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
