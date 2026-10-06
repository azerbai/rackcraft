package dev.rackcraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.rackcraft.world.DataCenterLocator;
import dev.rackcraft.world.FacilityManager;
import dev.rackcraft.world.SimTicker;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class RackcraftCommands {
	private static final List<String> EVENTS = List.of("utility_outage", "cooling_failure",
			"hardware_failure", "cable_cut", "heat_wave", "surge");

	private static final int DEFAULT_LOCATE_RADIUS = 100;

	private RackcraftCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		LiteralArgumentBuilder<ServerCommandSource> root = literal("rackcraft")
				.requires(source -> source.hasPermissionLevel(2));
		root.then(creditsCommand());
		root.then(eventCommand());
		root.then(heatCommand());
		root.then(facilityCommand());
		root.then(simCommand());
		root.then(locateCommand());
		root.then(literal("structure").then(literal("datacenter").executes(context -> {
			var source = context.getSource();
			BlockPos origin = BlockPos.ofFloored(source.getPosition()).add(-4, 0, 1);
			dev.rackcraft.world.AbandonedDataCenterFeature.place(source.getWorld(), origin, source.getWorld().getRandom(), true);
			source.sendFeedback(() -> Text.literal("Placed an abandoned data center at " + origin.toShortString()), true);
			return 1;
		})));
		dispatcher.register(root);
	}

	private static LiteralArgumentBuilder<ServerCommandSource> locateCommand() {
		return literal("locate").then(literal("datacenter")
				.executes(context -> locateDataCenter(context.getSource(), DEFAULT_LOCATE_RADIUS))
				.then(argument("radius", IntegerArgumentType.integer(1, 500))
						.executes(context -> locateDataCenter(context.getSource(),
								IntegerArgumentType.getInteger(context, "radius")))));
	}

	private static int locateDataCenter(ServerCommandSource source, int radius) {
		BlockPos from = BlockPos.ofFloored(source.getPosition());
		var found = DataCenterLocator.nearest(source.getWorld(), from, radius);
		if (found.isEmpty()) {
			source.sendError(Text.literal("No abandoned data center within " + radius + " chunks"));
			return 0;
		}
		BlockPos site = found.get();
		int distance = (int) Math.round(Math.sqrt(site.getSquaredDistance(from.getX(), site.getY(), from.getZ())));
		Text coordinates = Texts.bracketed(Text.translatable("chat.coordinates", site.getX(), site.getY(), site.getZ()))
				.styled(style -> style.withColor(Formatting.GREEN)
						.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
								"/tp @s " + site.getX() + " " + site.getY() + " " + site.getZ()))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.translatable("chat.coordinates.tooltip"))));
		source.sendFeedback(() -> Text.literal("The nearest abandoned data center is at ").append(coordinates)
				.append(" (" + distance + " blocks away)"), false);
		return distance;
	}

	private static LiteralArgumentBuilder<ServerCommandSource> creditsCommand() {
		return literal("credits").then(literal("add").then(argument("amount", IntegerArgumentType.integer(0))
				.executes(context -> {
					int amount = IntegerArgumentType.getInteger(context, "amount");
					FacilityManager.get(context.getSource().getWorld()).addCredits(amount);
					context.getSource().sendFeedback(() -> Text.literal("Rackcraft credits: +" + amount), false);
					return 1;
				})));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> eventCommand() {
		return literal("event").then(argument("id", StringArgumentType.word()).executes(context -> {
			String id = StringArgumentType.getString(context, "id");
			if (!EVENTS.contains(id)) {
				context.getSource().sendError(Text.literal("Unknown Rackcraft event: " + id));
				return 0;
			}
			long duration = switch (id) {
				case "utility_outage" -> 1800 + FacilityManager.get(context.getSource().getWorld()).nextRandomInt(1801);
				case "cooling_failure" -> 2400;
				case "heat_wave" -> 6000;
				default -> 1_200_000;
			};
			FacilityManager.get(context.getSource().getWorld()).triggerEvent(id, duration);
			context.getSource().sendFeedback(() -> Text.literal("Rackcraft event started: " + id), true);
			return 1;
		}));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> heatCommand() {
		return literal("heat").then(literal("set")
				.then(argument("x", IntegerArgumentType.integer())
						.then(argument("y", IntegerArgumentType.integer())
								.then(argument("z", IntegerArgumentType.integer())
										.then(argument("celsius", DoubleArgumentType.doubleArg(-50, 500))
												.executes(context -> {
												BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(context, "x"),
														IntegerArgumentType.getInteger(context, "y"),
														IntegerArgumentType.getInteger(context, "z"));
												SimTicker.setHeat(context.getSource().getWorld(), pos,
														DoubleArgumentType.getDouble(context, "celsius"));
												context.getSource().sendFeedback(() -> Text.literal("Heat cell updated at " + pos), false);
												return 1;
											}))))));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> facilityCommand() {
		return literal("facility").then(literal("info").executes(context -> {
			FacilityManager facility = FacilityManager.get(context.getSource().getWorld());
			context.getSource().sendFeedback(() -> Text.literal("Credits " + facility.credits()
					+ " | Contract " + facility.activeContract() + " | Event " + facility.activeEvent()), false);
			return 1;
		}));
	}

	private static LiteralArgumentBuilder<ServerCommandSource> simCommand() {
		return literal("sim").then(literal("step").then(argument("count", IntegerArgumentType.integer(0, 1000))
				.executes(context -> {
					int count = IntegerArgumentType.getInteger(context, "count");
					for (int index = 0; index < count; index++) SimTicker.stepNow(context.getSource().getWorld());
					context.getSource().sendFeedback(() -> Text.literal("Ran " + count + " Rackcraft simulation steps"), false);
					return count;
				})));
	}
}