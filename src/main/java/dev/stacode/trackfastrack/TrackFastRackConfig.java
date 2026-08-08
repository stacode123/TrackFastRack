package dev.stacode.trackfastrack;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TrackFastRackConfig {

	private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

	private static final ForgeConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER.comment(
		"Print the [TrackFastRack] timing/debug lines to the log. Disable for a clean production log.")
		.define("debugLogging", true);

	public static final ForgeConfigSpec SPEC = BUILDER.build();

	public static boolean debugLogging() {
		return DEBUG_LOGGING.get();
	}

	private TrackFastRackConfig() {}
}