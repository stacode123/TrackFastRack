package dev.stacode.trackfastrack;

/**
 * Shared per-placement metrics collected across TrackGraph hot methods so we can see how many
 * nodes/edges a single onRailAdded touches. Kept OUT of the .mixin package because classes in a
 * declared mixin package are "owned" by the mixin config and can't be referenced directly by
 * other classes. Server is single-threaded, so plain statics are safe (counters are reset by the
 * onRailAdded HEAD injection).
 */
public abstract class TrackMetrics {

	public static long connectNodesNanos;
	public static int connectNodesCalls;

	public static long createNodeNanos;
	public static int createNodeCalls;

	public static long locateNodeNanos;
	public static int locateNodeCalls;

	public static long removeNodeNanos;
	public static int removeNodeCalls;

	public static void reset() {
		connectNodesNanos = 0;
		connectNodesCalls = 0;
		createNodeNanos = 0;
		createNodeCalls = 0;
		locateNodeNanos = 0;
		locateNodeCalls = 0;
		removeNodeNanos = 0;
		removeNodeCalls = 0;
	}

	private TrackMetrics() {}
}