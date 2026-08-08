package dev.stacode.trackfastrack;

import com.simibubi.create.content.trains.graph.TrackGraph;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.event.server.ServerStartingEvent;

@Mod(TrackFastRack.MOD_ID)
public class TrackFastRack {

	public static final String MOD_ID = "trackfastrack";
	private static final Logger LOGGER = LogUtils.getLogger();

	public TrackFastRack() {
		ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TrackFastRackConfig.SPEC);
		MinecraftForge.EVENT_BUS.register(this);
	}

	// Temporary self-test: force TrackGraph to load (triggering the mixin apply) and exercise the
	// replaced method on an empty graph, so a clean run proves the @Overwrite bound correctly.
	@SubscribeEvent
	public void onServerStarting(ServerStartingEvent event) {
		TrackGraph graph = new TrackGraph();
		int components = graph.findDisconnectedGraphs(null, null).size();
		if (TrackFastRackConfig.debugLogging())
			LOGGER.info("[{}] TrackGraph flood-fill self-test OK: {} disconnected component(s) on empty graph",
				MOD_ID, components);
	}
}