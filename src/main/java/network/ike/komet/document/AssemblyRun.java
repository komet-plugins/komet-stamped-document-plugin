/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.document;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * One materialization of an assembly that a person can watch, pause, resume and stop. Opening a
 * file that includes others materializes every file it includes, so a long book takes a while;
 * the card shows each file as it is written and offers Pause, Resume and Stop instead of asking
 * first whether to take the includes at all.
 *
 * <p>The run checks its state between files, never inside one. Pause takes effect when the file
 * being written is done. Stop keeps every file already written and leaves the rest as they were,
 * so the next run writes only what is missing. Every method may be called from any thread.
 */
public final class AssemblyRun {

    /** Where a run stands. */
    public enum State {
        /** Writing files, or about to. */
        RUNNING,
        /** Waiting between files until resumed or stopped. */
        PAUSED,
        /** Writes no further file. */
        STOPPED
    }

    /**
     * What a run reports as it goes. Called on the thread that writes the files, so a listener
     * that touches a scene graph hands the work to the JavaFX application thread.
     */
    public interface Listener {

        /**
         * The files the run will write, known before any is written.
         *
         * @param files      every file in include order, the assembly file first, absolute
         * @param unresolved the includes that could not be found, in document order
         */
        default void planned(List<Path> files, List<GitDocumentMaterializer.Unresolved> unresolved) { }

        /**
         * A file is about to be written.
         *
         * @param number its 1-based place in the run
         * @param total  how many files the run has
         * @param file   the file, absolute
         */
        default void writing(int number, int total, Path file) { }

        /**
         * A file was written.
         *
         * @param number its 1-based place in the run
         * @param total  how many files the run has
         * @param result what was written
         */
        default void written(int number, int total, GitDocumentMaterializer.Result result) { }

        /**
         * The run is waiting between files, or going on again.
         *
         * @param state the state now
         */
        default void changed(State state) { }
    }

    private static final Listener SILENT = new Listener() { };

    private final Listener listener;
    private State state = State.RUNNING;

    /**
     * A run that reports to a listener.
     *
     * @param listener told of each file; never {@code null}
     */
    public AssemblyRun(Listener listener) {
        this.listener = listener;
    }

    /**
     * A run no person watches, as a headless caller or a test materializes: it writes every
     * file and reports nothing.
     *
     * @return a new run
     */
    public static AssemblyRun unattended() {
        return new AssemblyRun(SILENT);
    }

    /** @return the listener this run reports to */
    Listener listener() {
        return listener;
    }

    /** @return where the run stands now */
    public synchronized State state() {
        return state;
    }

    /** Waits after the file being written, until {@link #resume()} or {@link #stop()}. */
    public void pause() {
        if (move(State.RUNNING, State.PAUSED)) {
            listener.changed(State.PAUSED);
        }
    }

    /** Goes on with the next file after a pause. */
    public void resume() {
        if (move(State.PAUSED, State.RUNNING)) {
            listener.changed(State.RUNNING);
        }
    }

    /** Writes no further file; the file being written is finished first. */
    public void stop() {
        boolean moved;
        synchronized (this) {
            moved = state != State.STOPPED;
            state = State.STOPPED;
            notifyAll();
        }
        if (moved) {
            listener.changed(State.STOPPED);
        }
    }

    private synchronized boolean move(State from, State to) {
        if (state != from) {
            return false;
        }
        state = to;
        notifyAll();
        return true;
    }

    /**
     * Called between files by the writer: waits while the run is paused.
     *
     * @return {@code true} to write the next file, {@code false} once the run is stopped
     * @throws InterruptedIOException if the writing thread is interrupted while it waits
     */
    synchronized boolean proceed() throws InterruptedIOException {
        while (state == State.PAUSED) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while paused");
            }
        }
        return state == State.RUNNING;
    }
}
