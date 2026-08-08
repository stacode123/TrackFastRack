package dev.stacode.trackfastrack;

/**
 * World-side flag tracking whether any curved (bezier) track edge has ever been created on this
 * JVM. The intersection scan in TrackGraph#connectNodes only does real work when a curve edge
 * exists (straight-vs-straight crossings are never computed upstream, see the `!bezier &&
 * !otherEdge.isTurn()` guard). So while NO curve edge has ever been created, that full-network
 * scan is always a no-op and can be safely skipped.
 * <p>
 * The flag is only ever set true and never reset, which is the safe/conservative direction: once a
 * curve exists anywhere we fall back to the stock O(N) scan, so we can never skip a scan that
 * actually has intersections to find. Like TrackMetrics this lives OUTSIDE the .mixin package.
 */
public abstract class TrackFlags {

	/** True once any bezier edge has been created this server instance. */
	public static boolean anyCurvedEdgeCreated;

	private TrackFlags() {}
}