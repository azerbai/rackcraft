package dev.rackcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public final class RackcraftConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static Values values = new Values();

	private RackcraftConfig() {}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("rackcraft.json");
		try {
			Files.createDirectories(path.getParent());
			if (Files.exists(path)) {
				Values loaded = GSON.fromJson(Files.readString(path), Values.class);
				if (loaded != null) values = loaded.withDefaults();
				if (values.version < Values.CURRENT_VERSION) {
					// Version 2 rebuilt the air model; the old thermal numbers would leave heat trapped.
					values.thermal = new Thermal();
					values.version = Values.CURRENT_VERSION;
					Files.writeString(path, GSON.toJson(values));
				}
			} else {
				values.version = Values.CURRENT_VERSION;
				Files.writeString(path, GSON.toJson(values));
			}
		} catch (IOException exception) {
			Rackcraft.LOGGER.error("[Rackcraft] Could not read or write config", exception);
		}
	}

	public static final class Values {
		static final int CURRENT_VERSION = 2;
		/** 0 in files written before versions existed (Gson leaves a missing field at its default). */
		public int version;
		public Sim sim = new Sim();
		public Thermal thermal = new Thermal();
		public Events events = new Events();
		public HeatOverlay heatOverlay = new HeatOverlay();
		public Hud hud = new Hud();

		private Values withDefaults() {
			Values defaults = new Values();
			if (sim == null) sim = defaults.sim;
			if (thermal == null) thermal = defaults.thermal;
			if (events == null) events = defaults.events;
			if (heatOverlay == null) heatOverlay = defaults.heatOverlay;
			if (hud == null) hud = defaults.hud;
			if (sim.stepTicks <= 0) sim.stepTicks = defaults.sim.stepTicks;
			if (thermal.cellCapacityKjPerK <= 0) thermal.cellCapacityKjPerK = defaults.thermal.cellCapacityKjPerK;
			if (thermal.maxActiveCells <= 0) thermal.maxActiveCells = defaults.thermal.maxActiveCells;
			if (heatOverlay.maxCells <= 0) heatOverlay.maxCells = defaults.heatOverlay.maxCells;
			return this;
		}
	}

	/** {@code rackBootScale} multiplies how long racks take to boot (1 by default, 0 for instant). */
	public static final class Sim {
		public int stepTicks = 10;
		public double rackBootScale = 1;
	}
	/**
	 * The air. Each block of air holds {@code cellCapacityKjPerK}; neighbours trade heat at
	 * {@code faceConductanceKwPerK} per degree of difference (times {@code upwardMultiplier} for hot air rising),
	 * and each cell loses {@code leakKwPerK} per degree above ambient through walls, or {@code outdoorLeakKwPerK}
	 * where it can see the sky.
	 */
	public static final class Thermal {
		public double ambientC = 24;
		public double cellCapacityKjPerK = 4;
		public double faceConductanceKwPerK = 2;
		public double upwardMultiplier = 2;
		public double leakKwPerK = 0.005;
		public double outdoorLeakKwPerK = 0.4;
		public int maxActiveCells = 65536;
		public double settleEpsilonK = 0.03;
	}
	public static final class Events {
		public boolean enabled = true;
		public double perHour = 3;
	}
	public static final class HeatOverlay { public int maxCells = 2000; }
	/** Client-side: the RackCoin balance shown near racks or while holding a Rackcraft tool. */
	public static final class Hud { public boolean enabled = true; }
}