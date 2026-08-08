package dev.stacode.trackfastrack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;

import net.createmod.catnip.data.Couple;

/**
 * Time-sliced re-implementation of the edge-intersection scan in TrackGraph#connectNodes.
 * <p>
 * The stock scan iterates every node and edge of the whole network to find crossings for each newly
 * added edge, which blocks the server for a long time on large graphs. Here the scan of a single
 * new edge is turned into a resumable task: each server tick the queue spends a small time budget
 * advancing the current task's cursor over the (snapshotted, immutable) node list. The result is
 * the same work, spread across many ticks instead of one hard freeze.
 * <p>
 * Runs on the server thread only (driven from GlobalRailwayManager#tick), so this is single
 * threaded and safe. It deliberately uses only the public TrackGraph/TrackEdge APIs.
 */
public abstract class TrackScanQueue {

	/** Nanoseconds of intersection-scan work budgeted per server tick. */
	public static long budgetNanos = 3_000_000L;

	private static final Deque<Task> QUEUE = new ArrayDeque<>();

	private static final class Task {
		final TrackGraph graph;
		final TrackNode from;
		final TrackNode to;
		final TrackEdge edge;
		final boolean bezier;

		final ArrayList<TrackGraph> graphs;
		int graphIdx;
		ArrayList<TrackNodeLocation> nodeLocs;
		int nodeIdx;

		Task(TrackGraph graph, TrackNode from, TrackNode to, TrackEdge edge) {
			this.graph = graph;
			this.from = from;
			this.to = to;
			this.edge = edge;
			this.bezier = edge.isTurn();
			this.graphs = new ArrayList<>(Create.RAILWAYS.trackNetworks.values());
		}
	}

	public static int pending() {
		return QUEUE.size();
	}

	/** Called from the putConnection hook in connectNodes to defer the scan for a new edge. */
	public static void enqueue(TrackGraph graph, TrackNode from, TrackNode to, TrackEdge edge) {
		if (graph == null || edge == null)
			return;
		QUEUE.add(new Task(graph, from, to, edge));
	}

	/**
	 * Advance the deferred scans, spending roughly {@code budgetNanos} this tick.
	 * Returns the number of tasks fully completed this call (for diagnostics).
	 */
	public static int processTick() {
		long deadline = System.nanoTime() + budgetNanos;
		int done = 0;
		while (System.nanoTime() < deadline) {
			Task task = QUEUE.peek();
			if (task == null)
				break;
			if (advance(task, deadline)) {
				QUEUE.poll();
				done++;
			} else {
				break;
			}
		}
		return done;
	}

	private static boolean advance(Task task, long deadline) {
		while (task.graphIdx < task.graphs.size()) {
			TrackGraph other = task.graphs.get(task.graphIdx);
			if (task.nodeLocs == null)
				task.nodeLocs = new ArrayList<>(other.getNodes());

			while (task.nodeIdx < task.nodeLocs.size()) {
				if (System.nanoTime() >= deadline)
					return false;
				TrackNodeLocation loc = task.nodeLocs.get(task.nodeIdx);
				task.nodeIdx++;
				TrackNode node = other.locateNode(loc);
				if (node == null)
					continue;
				scanNode(task, other, node);
			}

			task.graphIdx++;
			task.nodeIdx = 0;
			task.nodeLocs = null;
		}
		return true;
	}

	private static void scanNode(Task task, TrackGraph other, TrackNode otherNode1) {
		Map<TrackNode, TrackEdge> connections = other.getConnectionsFrom(otherNode1);
		if (connections == null || connections.isEmpty())
			return;

		TrackNode n1 = task.from;
		TrackNode n2 = task.to;
		TrackEdge edge = task.edge;
		boolean selfGraph = other == task.graph;

		TrackEdge edge2 = selfGraph ? reverse(task.graph, n2, n1) : null;

		for (Map.Entry<TrackNode, TrackEdge> entry : connections.entrySet()) {
			TrackNode otherNode2 = entry.getKey();
			TrackEdge otherEdge = entry.getValue();

			if (selfGraph)
				if (otherNode1 == n1 || otherNode2 == n1 || otherNode1 == n2 || otherNode2 == n2)
					continue;
			if (edge == otherEdge)
				continue;
			if (otherEdge.isInterDimensional() || edge.isInterDimensional())
				continue;
			if (n1.getLocation().dimension != otherNode1.getLocation().dimension)
				continue;
			if (!task.bezier && !otherEdge.isTurn())
				continue;
			if (otherEdge.isTurn() && otherEdge.getTurn().isPrimary())
				continue;

			Collection<double[]> intersections = edge.getIntersection(n1, n2, otherEdge, otherNode1, otherNode2);
			if (intersections.isEmpty())
				continue;

			UUID id = UUID.randomUUID();
			for (double[] inter : intersections) {
				double s = inter[0];
				double t = inter[1];
				edge.getEdgeData()
					.addIntersection(task.graph, id, s, otherNode1, otherNode2, t);
				if (edge2 != null)
					edge2.getEdgeData()
						.addIntersection(task.graph, id, edge.getLength() - s, otherNode1, otherNode2, t);
				otherEdge.getEdgeData()
					.addIntersection(other, id, t, n1, n2, s);
				TrackEdge otherEdge2 = other.getConnection(Couple.create(otherNode2, otherNode1));
				if (otherEdge2 != null)
					otherEdge2.getEdgeData()
						.addIntersection(other, id, otherEdge.getLength() - t, n1, n2, s);
			}
		}
	}

	/** Look up an existing reverse edge n2->n1 if present. */
	private static TrackEdge reverse(TrackGraph graph, TrackNode n2, TrackNode n1) {
		Map<TrackNode, TrackEdge> cons = graph.getConnectionsFrom(n2);
		return cons == null ? null : cons.get(n1);
	}

	private TrackScanQueue() {}
}