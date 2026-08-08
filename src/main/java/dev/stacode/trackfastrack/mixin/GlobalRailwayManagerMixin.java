package dev.stacode.trackfastrack.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.GlobalRailwayManager;

import dev.stacode.trackfastrack.TrackScanQueue;

import net.minecraft.world.level.Level;

/**
 * Drives the deferred intersection-scan queue once per server tick. Tick runs on the server
 * thread (single-threaded with track mutation), so processing here is race-free.
 */
@Mixin(GlobalRailwayManager.class)
public abstract class GlobalRailwayManagerMixin {

	@Inject(method = "tick", remap = false, at = @At("RETURN"))
	private void trackfastrack_driveScanQueue(Level level, CallbackInfo ci) {
		TrackScanQueue.processTick();
	}
}