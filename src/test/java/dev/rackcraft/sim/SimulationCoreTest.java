package dev.rackcraft.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SimulationCoreTest {
	@Test
	void networkComponentsRebuildAfterRemoval() {
		NetGraph<Integer> graph = new NetGraph<>();
		graph.connect(1, 2);
		graph.connect(2, 3);
		assertEquals(1, graph.components().size());
		graph.remove(2);
		assertEquals(2, graph.components().size());
	}

	@Test
	void priorityZeroPowerIsServedBeforeRackLoad() {
		PowerSolver.Result result = PowerSolver.solve(List.of(
				new PowerSolver.Sink("cooling", 0, 4),
				new PowerSolver.Sink("rack", 1, 8)),
				List.of(new PowerSolver.Source("solar", PowerSolver.SourceKind.SOLAR, 8)), 0.5);
		assertEquals(1.0, result.satisfaction().get("cooling"));
		assertEquals(0.5, result.satisfaction().get("rack"));
	}

	@Test
	void batteryBridgesDieselSpinup() {
		PowerSolver.Source battery = new PowerSolver.Source("battery", PowerSolver.SourceKind.BATTERY,
				0, 100, 100, 15, 60);
		PowerSolver.Source diesel = new PowerSolver.Source("diesel", PowerSolver.SourceKind.DIESEL, 40);
		PowerSolver.Result first = PowerSolver.solve(List.of(new PowerSolver.Sink("rack", 1, 10)),
				List.of(battery, diesel), 0.5);
		assertEquals(1.0, first.satisfaction().get("rack"));
		assertTrue(battery.chargeKws() < 100);
	}

	@Test
	void generationSurplusChargesBattery() {
		PowerSolver.Source battery = new PowerSolver.Source("battery", PowerSolver.SourceKind.BATTERY,
				0, 100, 0, 15, 60);
		PowerSolver.solve(List.of(new PowerSolver.Sink("rack", 1, 5)),
				List.of(new PowerSolver.Source("solar", PowerSolver.SourceKind.SOLAR, 20), battery), 0.5);
		assertEquals(6.75, battery.chargeKws(), 0.00001);
	}

	@Test
	void thermalDiffusionConservesEnergyWithoutLeak() {
		ThermalGrid grid = new ThermalGrid(24, 4, 0.25, 2, 0, 0, 100);
		ThermalGrid.CellPos hot = new ThermalGrid.CellPos(0, 0, 0);
		ThermalGrid.CellPos cool = new ThermalGrid.CellPos(1, 0, 0);
		grid.ensureCell(hot);
		grid.ensureCell(cool);
		grid.depositKw(hot, 8, 1);
		double before = totalEnergy(grid.snapshot(), 24, 4);
		for (int index = 0; index < 100; index++) grid.step(0.5, false);
		double after = totalEnergy(grid.snapshot(), 24, 4);
		assertEquals(before, after, 0.001);
		assertTrue(grid.temperatureCelsius(hot) > grid.temperatureCelsius(cool));
	}

	@Test
	void thermalGridStaysBoundedAndHeatingIsMonotonic() {
		ThermalGrid grid = new ThermalGrid(24, 4, 0.25, 2, 0, 0.05, 100);
		ThermalGrid.CellPos hot = new ThermalGrid.CellPos(0, 0, 0);
		ThermalGrid.CellPos cool = new ThermalGrid.CellPos(1, 0, 0);
		grid.ensureCell(hot);
		grid.ensureCell(cool);
		grid.depositKw(hot, 10, 0.5);
		double hottest = grid.temperatureCelsius(hot);
		for (int index = 0; index < 1000; index++) {
			grid.step(0.5, false);
			assertTrue(grid.temperatureCelsius(hot) >= 24);
			assertTrue(grid.temperatureCelsius(cool) <= hottest);
		}
		ThermalGrid isolated = new ThermalGrid(24, 4, 0.25, 2, 0, 0, 10);
		isolated.ensureCell(hot);
		double previous = isolated.temperatureCelsius(hot);
		for (int index = 0; index < 100; index++) {
			isolated.depositKw(hot, 1, 0.5);
			isolated.step(0.5, false);
			assertTrue(isolated.temperatureCelsius(hot) >= previous);
			previous = isolated.temperatureCelsius(hot);
		}
	}

	@Test
	void heatSpreadsThroughAirAndLeaksFasterOutdoors() {
		ThermalGrid.CellPos source = new ThermalGrid.CellPos(0, 0, 0);
		double[] peaks = new double[2];
		for (int outdoors = 0; outdoors < 2; outdoors++) {
			boolean open = outdoors == 1;
			ThermalGrid grid = new ThermalGrid(24, 4, 2, 2, 0.005, 0.4, 0.03, 10000);
			ThermalGrid.AirMap air = new ThermalGrid.AirMap() {
				@Override public boolean passable(ThermalGrid.CellPos cell) { return Math.abs(cell.x()) < 6 && Math.abs(cell.y()) < 4 && Math.abs(cell.z()) < 6; }
				@Override public boolean outdoors(ThermalGrid.CellPos cell) { return open; }
			};
			grid.ensureCell(source, open);
			for (int step = 0; step < 600; step++) {
				grid.depositKw(source, 10, 0.5);
				grid.step(0.5, air);
			}
			// Indoors the whole closed room warms; outdoors the heat blows away within a few blocks.
			assertTrue(grid.activeCells() > (open ? 6 : 300), "heat should spread: " + grid.activeCells() + " cells");
			double near = grid.temperatureCelsius(new ThermalGrid.CellPos(open ? 1 : 4, 0, 0));
			assertTrue(near > 24.05, (open ? "outdoors" : "indoors") + " near=" + near + " source=" + grid.temperatureCelsius(source)
					+ " cells=" + grid.activeCells());
			peaks[outdoors] = grid.temperatureCelsius(source);
		}
		assertTrue(peaks[1] < peaks[0], "outdoor air should run cooler: " + peaks[1] + " vs " + peaks[0]);
	}

	@Test
	void coolingCannotRemoveMoreThanAvailableHeat() {
		ThermalGrid grid = new ThermalGrid(24, 4, 0, 1, 0, 0, 10);
		ThermalGrid.CellPos cell = new ThermalGrid.CellPos(0, 0, 0);
		grid.depositKw(cell, 10, 0.5);
		assertEquals(10, grid.removeKw(cell, 100, 0.5), 0.00001);
		assertEquals(0, grid.removeKw(cell, 100, 0.5), 0.00001);
		assertEquals(24, grid.temperatureCelsius(cell), 0.00001);
	}

	@Test
	void rackThrottleAndQuantumGateAreApplied() {
		ServerModel.RackStep throttled = ServerModel.calculate(
				List.of(ServerModel.Module.SERVER_1U), 100, 1, 35, true);
		assertEquals(0.46875, throttled.thermalFactor(), 0.00001);
		ServerModel.RackStep blocked = ServerModel.calculate(
				List.of(ServerModel.Module.QUANTUM_CORE), 100, 1, 24, false);
		assertTrue(blocked.quantumBlocked());
		assertEquals(0, blocked.load());
	}

	@Test
	void slaAndEventsAreDeterministic() {
		SlaModel.ContractResult bronze = SlaModel.evaluate(SlaModel.Contract.BRONZE, 1, 1, 0);
		assertTrue(bronze.met());
		assertEquals(0.10, bronze.payoutRate());
		EventScheduler first = new EventScheduler(7, 3600, 0);
		EventScheduler second = new EventScheduler(7, 3600, 0);
		String firstEvent = null;
		String secondEvent = null;
		for (int index = 0; index < 50 && firstEvent == null; index++) {
			firstEvent = first.tickSecond(true, true, true);
			secondEvent = second.tickSecond(true, true, true);
		}
		assertEquals(firstEvent, secondEvent);
		assertFalse(firstEvent == null);
	}

	private static double totalEnergy(java.util.Map<ThermalGrid.CellPos, Double> cells,
			double ambient, double capacity) {
		return cells.values().stream().mapToDouble(temperature -> (temperature - ambient) * capacity).sum();
	}
}