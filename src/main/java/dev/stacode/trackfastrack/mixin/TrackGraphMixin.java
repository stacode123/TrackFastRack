package dev.stacode.trackfastrack.mixin;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.createmod.catnip.data.Pair;

import org.slf4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.logging.LogUtils;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.graph.TrackNodeLocation.DiscoveredLocation;

import dev.stacode.trackfastrack.TrackFastRackConfig;
import dev.stacode.trackfastrack.TrackMetrics;
import dev.stacode.trackfastrack.TrackScanQueue;

import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;

import net.minecraft.world.level.LevelAccessor;

/**
 * Replaces {@link TrackGraph#findDisconnectedGraphs(LevelAccessor, Map)} with a linear-time BFS.
 * <p>
 * The upstream method used an {@code ArrayList} as its BFS frontier and popped with
 * {@code ArrayList#remove(0)}, which shifts the whole backing array on every node, making the
 * flood fill O(n^2). It runs on every track placement and removal, so on a large network a single
 * track change walked billions of nodes. Switching to an {@link ArrayDeque} (O(1) pop from the
 * head) and a plain iterator instead of {@code stream().findFirst()} makes it O(n).
 * <p>
 * This is the optimisation that lives in the fork's Create at the same method. The mixin class
 * deliberately avoids naming packages owned by Create so the two mods stay in separate JPMS
 * modules.
 */
@Mixin(TrackGraph.class)
public abstract class TrackGraphMixin {

	private static final Logger LOGGER = LogUtils.getLogger();

	@Shadow(remap = false)
	private Map<TrackNodeLocation, TrackNode> nodes;

	@Shadow(remap = false)
	public abstract TrackNode locateNode(TrackNodeLocation position);

	@Shadow(remap = false)
	public abstract Map<TrackNode, TrackEdge> getConnectionsFrom(TrackNode node);

	@Shadow(remap = false)
	public abstract void transfer(LevelAccessor level, TrackNode node, TrackGraph target);

	@Shadow(remap = false)
	public abstract void setId(UUID id);

	@Shadow(remap = false)
	public abstract void setNetId(int id);

	/**
	 * @author stacode
	 * @reason Optimise the connected-component flood fill (O(n^2) -&gt; O(n)) that runs on every
	 *         track placement and removal.
	 */
	@Overwrite(remap = false)
	public Set<TrackGraph> findDisconnectedGraphs(@Nullable LevelAccessor level,
												  @Nullable Map<Integer, Pair<Integer, UUID>> splitSubGraphs) {
		long startNanos = System.nanoTime();
		Set<TrackGraph> dicovered = new HashSet<>();
		Set<TrackNodeLocation> vertices = new HashSet<>(this.nodes.keySet());
		ArrayDeque<TrackNodeLocation> frontier = new ArrayDeque<>();
		TrackGraph target = null;
		int visited = 0;

		while (!vertices.isEmpty()) {
			if (target != null)
				dicovered.add(target);

			TrackNodeLocation start = vertices.iterator()
				.next();
			frontier.add(start);
			vertices.remove(start);

			while (!frontier.isEmpty()) {
				TrackNodeLocation current = frontier.poll();
				visited++;
				TrackNode currentNode = this.locateNode(current);

				Map<TrackNode, TrackEdge> connections = this.getConnectionsFrom(currentNode);
				for (TrackNode connected : connections.keySet())
					if (vertices.remove(connected.getLocation()))
						frontier.add(connected.getLocation());

				if (target != null) {
					if (splitSubGraphs != null && splitSubGraphs.containsKey(currentNode.getNetId())) {
						Pair<Integer, UUID> ids = splitSubGraphs.get(currentNode.getNetId());
						target.setId(ids.getSecond());
						target.setNetId(ids.getFirst());
					}
					this.transfer(level, currentNode, target);
				}
			}

			frontier.clear();
			target = new TrackGraph();
		}

		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
		if (TrackFastRackConfig.debugLogging() && LOGGER.isInfoEnabled()) {
			boolean client = FMLEnvironment.dist == Dist.CLIENT;
			LOGGER.info("[TrackFastRack][{}][{}] findDisconnectedGraphs: visited {} nodes in {} ms, {} graph(s)",
				client ? "client" : "server",
				Thread.currentThread()
					.getName(),
				visited, elapsedMs, dicovered.size());
		}
		return dicovered;
	}

	@Unique
	private long transferAllStartNanos;
	@Unique
	private int transferAllNodeCount;

	/**
	 * Times the network-merge path. On the server this is the real O(big graph) cost when placing
	 * a track that joins two existing networks (it transfers every node + edge with per-node sync).
	 */
	@Inject(method = "transferAll", remap = false, at = @At("HEAD"))
	private void trackfastrack_transferAllStart(TrackGraph toOther, CallbackInfo ci) {
		this.transferAllStartNanos = System.nanoTime();
		this.transferAllNodeCount = this.nodes.size();
	}

	@Inject(method = "transferAll", remap = false, at = @At("RETURN"))
	private void trackfastrack_transferAllEnd(TrackGraph toOther, CallbackInfo ci) {
		long elapsedMs = (System.nanoTime() - this.transferAllStartNanos) / 1_000_000;
		if (TrackFastRackConfig.debugLogging() && elapsedMs >= 1) {
			LOGGER.info("[TrackFastRack][{}][{}] transferAll merge: {} nodes in {} ms",
				sideName(), Thread.currentThread()
					.getName(),
				this.transferAllNodeCount, elapsedMs);
		}
	}

	private static String sideName() {
		return FMLEnvironment.dist == Dist.CLIENT ? "client" : "server";
	}

	@Unique
	private long connectNodeStartNanos;

	@Inject(method = "connectNodes", remap = false, at = @At("HEAD"))
	private void trackfastrack_connectStart(LevelAccessor reader, DiscoveredLocation location,
		DiscoveredLocation location2, com.simibubi.create.content.trains.track.BezierConnection turn, CallbackInfo ci) {
		this.connectNodeStartNanos = System.nanoTime();
	}

	@Inject(method = "connectNodes", remap = false, at = @At("RETURN"))
	private void trackfastrack_connectEnd(LevelAccessor reader, DiscoveredLocation location,
		DiscoveredLocation location2, com.simibubi.create.content.trains.track.BezierConnection turn, CallbackInfo ci) {
		TrackMetrics.connectNodesNanos += System.nanoTime() - this.connectNodeStartNanos;
		TrackMetrics.connectNodesCalls++;
	}

	/**
	 * Skip the whole-network intersection scan inside connectNodes (it blocks each placement on
	 * large graphs). The real scan work is deferred: the first putConnection below enqueues the
	 * new edge so TrackScanQueue can timeslice it across later server ticks.
	 */
	@Redirect(method = "connectNodes", remap = false, at = @At(value = "INVOKE", ordinal = 0,
		target = "java/util/Map.values()Ljava/util/Collection;"))
	private Collection<TrackGraph> trackfastrack_skipScan(Map<UUID, TrackGraph> receiver) {
		return Collections.emptyList();
	}

	/**
	 * The first putConnection call in connectNodes hands us the freshly created edge object (and
	 * its two nodes) before it is placed. Enqueue it for deferred, time-sliced intersection
	 * scanning, then fall through to the real putConnection.
	 */
	@Redirect(method = "connectNodes", remap = false, at = @At(value = "INVOKE", ordinal = 0,
		target = "putConnection(Lcom/simibubi/create/content/trains/graph/TrackNode;Lcom/simibubi/create/content/trains/graph/TrackNode;Lcom/simibubi/create/content/trains/graph/TrackEdge;)Z"))
	private boolean trackfastrack_captureEnqueue(TrackGraph thiz, TrackNode n1, TrackNode n2, TrackEdge edge) {
		TrackScanQueue.enqueue(thiz, n1, n2, edge);
		return thiz.putConnection(n1, n2, edge);
	}

	@Unique
	private long createNodeStartNanos;

	@Inject(method = "createNodeIfAbsent", remap = false, at = @At("HEAD"))
	private void trackfastrack_createStart(DiscoveredLocation location, CallbackInfoReturnable<Boolean> cir) {
		this.createNodeStartNanos = System.nanoTime();
	}

	@Inject(method = "createNodeIfAbsent", remap = false, at = @At("RETURN"))
	private void trackfastrack_createEnd(DiscoveredLocation location, CallbackInfoReturnable<Boolean> cir) {
		TrackMetrics.createNodeNanos += System.nanoTime() - this.createNodeStartNanos;
		TrackMetrics.createNodeCalls++;
	}

	@Unique
	private long locateNodeStartNanos;

	@Inject(method = "locateNode(Lcom/simibubi/create/content/trains/graph/TrackNodeLocation;)Lcom/simibubi/create/content/trains/graph/TrackNode;",
		remap = false, at = @At("HEAD"))
	private void trackfastrack_locateStart(TrackNodeLocation position, CallbackInfoReturnable<TrackNode> cir) {
		this.locateNodeStartNanos = System.nanoTime();
	}

	@Inject(method = "locateNode(Lcom/simibubi/create/content/trains/graph/TrackNodeLocation;)Lcom/simibubi/create/content/trains/graph/TrackNode;",
		remap = false, at = @At("RETURN"))
	private void trackfastrack_locateEnd(TrackNodeLocation position, CallbackInfoReturnable<TrackNode> cir) {
		TrackMetrics.locateNodeNanos += System.nanoTime() - this.locateNodeStartNanos;
		TrackMetrics.locateNodeCalls++;
	}

	@Unique
	private long removeNodeStartNanos;

	@Inject(method = "removeNode", remap = false, at = @At("HEAD"))
	private void trackfastrack_removeStart(LevelAccessor level, TrackNodeLocation location,
		CallbackInfoReturnable<Boolean> cir) {
		this.removeNodeStartNanos = System.nanoTime();
	}

	@Inject(method = "removeNode", remap = false, at = @At("RETURN"))
	private void trackfastrack_removeEnd(LevelAccessor level, TrackNodeLocation location,
		CallbackInfoReturnable<Boolean> cir) {
		TrackMetrics.removeNodeNanos += System.nanoTime() - this.removeNodeStartNanos;
		TrackMetrics.removeNodeCalls++;
	}
}