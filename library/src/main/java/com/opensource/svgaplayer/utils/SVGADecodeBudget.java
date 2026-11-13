package com.opensource.svgaplayer.utils;

import java.io.IOException;

/** Shared by nested Wire adapters and the streaming JSON reader; never retained by a worker. */
public final class SVGADecodeBudget {
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private static final class State {
        int depth;
        int nodes;
        int values;
    }

    private SVGADecodeBudget() { }

    public static void enter() throws IOException {
        SVGAIO.INSTANCE.checkInterrupted();
        State state = CURRENT.get();
        if (state == null) {
            state = new State();
            CURRENT.set(state);
        }
        if (state.depth >= SVGAMemoryLimits.MAX_STRUCTURE_DEPTH ||
                state.nodes >= SVGAMemoryLimits.INSTANCE.getStructureNodes()) {
            if (state.depth == 0) CURRENT.remove();
            throw new IOException("SVGA structure exceeds its object or depth limit");
        }
        state.depth++;
        state.nodes++;
    }

    public static void value() throws IOException {
        SVGAIO.INSTANCE.checkInterrupted();
        State state = CURRENT.get();
        if (state == null) throw new IllegalStateException("No SVGA decode budget");
        if (++state.values > SVGAMemoryLimits.INSTANCE.getStructureValues()) {
            throw new IOException("SVGA structure exceeds its value limit");
        }
    }

    public static void exit() {
        State state = CURRENT.get();
        if (state != null && --state.depth == 0) CURRENT.remove();
    }
}
