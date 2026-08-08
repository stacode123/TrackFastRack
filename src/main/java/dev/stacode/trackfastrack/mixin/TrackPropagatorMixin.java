package dev.stacode.trackfastrack.mixin;

import org.slf4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.logging.LogUtils;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackPropagator;

import dev.stacode.trackfastrack.TrackFastRackConfig;
import dev.stacode.trackfastrack.TrackMetrics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Times the server-side track placement handler to confirm which part is the O(large graph)
 * cost. onRailAdded runs whenever a track is (re)added to the network: for a long track bridging
 * two big networks it triggers the full graph merge.
 */
@Mixin(TrackPropagator.class)
public abstract class TrackPropagatorMixin {

	private static final Logger LOGGER = LogUtils.getLogger();

	@Unique
	private static long onRailAddedStartNanos;

	@Inject(method = "onRailAdded", remap = false, at = @At("HEAD"), cancellable = false)
	private static void trackfastrack_onRailAddedStart(LevelAccessor reader, BlockPos pos, BlockState state,
		CallbackInfoReturnable<TrackGraph> cir) {
		onRailAddedStartNanos = System.nanoTime();
		TrackMetrics.reset();
	}

	@Inject(method = "onRailAdded", remap = false, at = @At("RETURN"))
	private static void trackfastrack_onRailAddedEnd(LevelAccessor reader, BlockPos pos, BlockState state,
		CallbackInfoReturnable<TrackGraph> cir) {
		long elapsedMs = (System.nanoTime() - onRailAddedStartNanos) / 1_000_000;
		TrackGraph graph = cir.getReturnValue();
		int size = graph == null ? 0 : graph.getNodes().size();
		long connectMs = TrackMetrics.connectNodesNanos / 1_000_000;
		long createMs = TrackMetrics.createNodeNanos / 1_000_000;
		long locateMs = TrackMetrics.locateNodeNanos / 1_000_000;
		long removeMs = TrackMetrics.removeNodeNanos / 1_000_000;
		if (TrackFastRackConfig.debugLogging() && elapsedMs >= 1) {
			LOGGER.info(
				"[TrackFastRack][server][{}] onRailAdded @{}: {} ms ({} nodes) -- create {} ({} ms) connect {} ({} ms) locate {} ({} ms) remove {} ({} ms)",
				Thread.currentThread().getName(),
				pos, elapsedMs, size,
				TrackMetrics.createNodeCalls, createMs,
				TrackMetrics.connectNodesCalls, connectMs,
				TrackMetrics.locateNodeCalls, locateMs,
				TrackMetrics.removeNodeCalls, removeMs);
		}
	}
}