package dev.rackcraft.generated;

import java.util.List;
import java.util.Map;

public final class ContentIds {
	public static final List<String> BLOCK_IDS = List.of("bauxite_ore", "steel_block", "raised_floor_tile", "blanking_panel", "power_cable", "diesel_generator", "solar_panel", "wind_turbine", "pdu", "server_rack", "exhaust_fan", "coolant_pipe", "cooling_tower", "crac_unit", "battery_bank", "utility_intake", "fiber_cable", "uplink_router", "core_router", "facility_controller", "cdu", "modular_reactor", "monitoring_wall", "fire_suppression_tank", "crypto_exchange", "storage_array", "tape_library", "storage_terminal", "wireless_transmitter", "freshwater_pump", "art_table", "writing_desk", "operations_terminal", "smog_scrubber", "creative_power", "creative_rack", "creative_cooler", "creative_router", "coke_block");
	public static final List<String> ITEM_IDS = List.of("field_manual", "raw_bauxite", "aluminum_ingot", "steel_ingot", "silicon", "copper_wire", "circuit_board", "cpu_chip", "ram_module", "gpu_chip", "cryo_coil", "pi_node", "server_1u", "gpu_blade", "asic_miner", "quantum_core", "tensor_accelerator", "failed_module", "thermal_scanner", "multimeter", "repair_kit", "fuel_cell", "suppression_canister", "drive_1k", "drive_4k", "drive_16k", "drive_64k", "tape_cartridge", "blank_pattern", "recipe_pattern", "wireless_terminal", "coke", "biomass_pellet", "biodiesel_canister", "crayons", "shackles", "art_aggregate", "text_corpus", "generated_image", "generated_document", "respirator", "carbon_offset");
	public static final List<String> MACHINE_IDS = List.of("diesel_generator", "solar_panel", "wind_turbine", "pdu", "server_rack", "exhaust_fan", "cooling_tower", "crac_unit", "battery_bank", "utility_intake", "uplink_router", "core_router", "facility_controller", "cdu", "modular_reactor", "monitoring_wall", "fire_suppression_tank", "crypto_exchange", "storage_array", "tape_library", "storage_terminal", "wireless_transmitter", "freshwater_pump", "art_table", "writing_desk", "operations_terminal", "smog_scrubber", "creative_power", "creative_rack", "creative_cooler", "creative_router");
	/** Storage capacity in items for drives and tapes. */
	public static final Map<String, Long> DRIVE_CAPACITY = Map.ofEntries(Map.entry("drive_1k", 1024L), Map.entry("drive_4k", 4096L), Map.entry("drive_16k", 16384L), Map.entry("drive_64k", 65536L), Map.entry("tape_cartridge", 1048576L));
	/** Creative-only: no recipe, never sold at the Exchange, shown in the Rackcraft Creative tab. */
	public static final List<String> CREATIVE_IDS = List.of("creative_power", "creative_rack", "creative_cooler", "creative_router");
	/** Furnace burn time in ticks for Rackcraft fuels; registered with Fabric's FuelRegistry. */
	public static final Map<String, Integer> FUEL_TICKS = Map.ofEntries(Map.entry("coke_block", 32000), Map.entry("coke", 3200), Map.entry("biomass_pellet", 800), Map.entry("biodiesel_canister", 9600));

	public record GuideChapter(String id, String icon, boolean fuelPage, List<String> entries) {}

	public static final List<GuideChapter> GUIDE_CHAPTERS = List.of(
			new GuideChapter("getting_started", "field_manual", false, List.of("field_manual", "multimeter", "bauxite_ore", "raw_bauxite", "aluminum_ingot", "steel_ingot", "steel_block", "silicon", "copper_wire")),
			new GuideChapter("mining", "crypto_exchange", false, List.of("crypto_exchange")),
			new GuideChapter("components", "circuit_board", false, List.of("circuit_board", "cpu_chip", "ram_module", "gpu_chip", "cryo_coil")),
			new GuideChapter("compute", "server_rack", false, List.of("server_rack", "pi_node", "server_1u", "asic_miner", "gpu_blade", "quantum_core", "tensor_accelerator", "failed_module", "repair_kit", "blanking_panel")),
			new GuideChapter("power", "diesel_generator", false, List.of("power_cable", "pdu", "utility_intake", "solar_panel", "wind_turbine", "battery_bank", "diesel_generator", "modular_reactor", "fuel_cell")),
			new GuideChapter("fuels", "biodiesel_canister", true, List.of("coke", "coke_block", "biomass_pellet", "biodiesel_canister")),
			new GuideChapter("cooling", "crac_unit", false, List.of("exhaust_fan", "raised_floor_tile", "coolant_pipe", "cooling_tower", "crac_unit", "cdu", "freshwater_pump", "thermal_scanner", "smog_scrubber", "respirator", "carbon_offset")),
			new GuideChapter("operations", "facility_controller", false, List.of("fiber_cable", "uplink_router", "core_router", "facility_controller", "monitoring_wall", "fire_suppression_tank", "suppression_canister")),
			new GuideChapter("storage", "storage_terminal", false, List.of("storage_array", "drive_1k", "drive_4k", "drive_16k", "drive_64k", "tape_library", "tape_cartridge", "storage_terminal", "blank_pattern", "recipe_pattern", "wireless_transmitter", "wireless_terminal")),
			new GuideChapter("ai", "operations_terminal", false, List.of("operations_terminal", "art_table", "crayons", "art_aggregate", "writing_desk", "shackles", "text_corpus", "generated_image", "generated_document")),
			new GuideChapter("creative", "creative_power", false, List.of("creative_power", "creative_rack", "creative_cooler", "creative_router")));

	private ContentIds() {}
}
