package com.petbattles.data;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.petbattles.engine.MoveDef;
import com.petbattles.engine.SpeciesDef;
import com.petbattles.engine.TrainerDef;
import com.petbattles.engine.TypeChart;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Loads the bundled JSON content resources. Uses the injected RuneLite Gson
 * (Plugin Hub disallows new Gson()).
 */
public class ContentLoader
{
	private static final String BASE = "/com/petbattles/data/";

	private final Gson gson;

	public ContentLoader(Gson gson)
	{
		this.gson = gson;
	}

	public List<SpeciesDef> loadSpecies()
	{
		return load("species.json", new TypeToken<List<SpeciesDef>>()
		{
		});
	}

	public List<MoveDef> loadMoves()
	{
		return load("moves.json", new TypeToken<List<MoveDef>>()
		{
		});
	}

	public List<TrainerDef> loadTrainers()
	{
		return load("trainers.json", new TypeToken<List<TrainerDef>>()
		{
		});
	}

	public TypeChart loadTypeChart()
	{
		Map<String, Map<String, Double>> raw = load("typechart.json",
			new TypeToken<Map<String, Map<String, Double>>>()
			{
			});
		return TypeChart.fromMap(raw);
	}

	private <T> T load(String name, TypeToken<T> type)
	{
		try (InputStream in = ContentLoader.class.getResourceAsStream(BASE + name))
		{
			if (in == null)
			{
				throw new IllegalStateException("Missing bundled resource: " + name);
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				T result = gson.fromJson(reader, type.getType());
				if (result == null)
				{
					throw new IllegalStateException("Empty content resource: " + name);
				}
				return result;
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to load " + name, e);
		}
	}
}
