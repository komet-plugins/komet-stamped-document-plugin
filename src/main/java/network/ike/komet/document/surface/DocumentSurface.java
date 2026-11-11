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
package network.ike.komet.document.surface;

import dev.ikm.komet.markdown.richtext.RichTextSearch;
import dev.ikm.komet.markdown.richtext.TableColumnWidths;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.TextPos;
import network.ike.komet.document.surface.print.PrintSource;
import network.ike.komet.document.surface.MarkdownRichText;
import network.ike.komet.document.RichTextViewpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The v1 Document surface ({@code IKE-Network/ike-issues#808}): a single-column, read-only
 * <em>block stack</em> — the spatial half of the rich interaction surface
 * ({@code dev-interaction-surface-family}). Turns append as blocks: prose runs are per-block
 * view-only {@code RichTextArea}s sized to their content; structured blocks (koncept-trees,
 * tables) are direct children of the stack rather than nodes smuggled into a text model. The
 * surface owns the document's <b>one vertical scroll axis</b> — no block scrolls internally —
 * which is the scroll discipline that retires the single-transcript-area virtualization fight.
 *
 * <p>Deliberately host-independent: it consumes only {@link MarkdownRichText.Entry} turns through
 * a {@link BlockFactory}, so the Scriptorium window ({@code #814}/{@code #815}) hosts it
 * unchanged. Non-virtualized by design for conversation-length documents (the {@code DynamicCard}
 * stack discipline); appends are incremental, and heavy nodes build lazily via their suppliers.
 *
 * <p><b>Accepted trade-off</b>: text selection is per-block — a drag cannot cross block
 * boundaries the way it crossed paragraphs in the single-area transcript. Whole-conversation
 * export lives in the host's Save action; a table carries its own copy affordance (the {@code
 * #596} interchange context menu) and a koncept-tree's chips drag. A surface-level
 * copy-turn/copy-document affordance is future work on the projection-flip slice.
 *
 * <p>The surface is the Document surface of the family: a twelve-track grid whose named regions
 * are a <em>header</em> and a <em>footer</em> that stay put while the body scrolls, a
 * <em>left margin</em> and a <em>right margin</em> that share each block's row and scroll with
 * it (marginalia), and the <em>body</em> holding the block stack. The regions are off by default
 * — a conversation is body alone — and a host turns them on with {@link #setHeader},
 * {@link #setFooter} and {@link #setMarginTracks}; a document materialized from git puts its
 * title and section in the header, its status in the footer, dates in the left margin and
 * identicons and marks in the right ({@code dev-stamped-document-surface}).
 *
 * <p>All methods must be called on the JavaFX application thread.
 */
public final class DocumentSurface extends BorderPane {

    /** The grid's track count: margins and body are expressed as tracks of it. */
    public static final int TRACKS = 12;

    /** A block's row, when the margin regions are on. */
    private static final String ROW_STYLE = "doc-block-row";
    /** The gap between a row's columns, px. */
    private static final double ROW_GAP = 8;
    /** Vertical gap between stacked blocks, px. */
    public static final double BLOCK_SPACING = 6;
    /** Extra gap above a turn header, px (separates turns more strongly than blocks). */
    private static final double TURN_SPACING = 10;

    private final ScrollPane scroll = new ScrollPane();
    private final VBox stack = new VBox(BLOCK_SPACING);
    private final StackPane header = new StackPane();
    private final StackPane footer = new StackPane();
    /** The one horizontal scrollbar of the unwrapped areas the stack holds, under the body and above the footer. */
    private final HorizontalScroll horizontal = new HorizontalScroll();
    private int leftTracks;
    private int rightTracks;
    private Supplier<PrintSource> printSource;
    private final List<MarkdownRichText.Entry> turns = new ArrayList<>();
    /** Per-turn: index (into {@link #blocks}) of the turn's first block (its header). */
    private final List<Integer> turnStarts = new ArrayList<>();
    private final List<DocumentBlock> blocks = new ArrayList<>();

    private BlockFactory blockFactory;
    /** The prose area holding the current find selection, for {@link #clearSelection()}. */
    private RichTextArea selectedArea;

    /**
     * Creates an empty surface.
     *
     * @param blockFactory builds each turn's blocks (view + font wiring)
     */
    public DocumentSurface(BlockFactory blockFactory) {
        this.blockFactory = blockFactory;
        stack.getStyleClass().add("claude-doc-stack");
        stack.setPadding(new Insets(8, 10, 8, 10));
        stack.setFillWidth(true);
        scroll.setContent(stack);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("claude-doc-surface");
        header.getStyleClass().add("doc-header-region");
        footer.getStyleClass().add("doc-footer-region");
        header.setVisible(false);
        header.setManaged(false);
        footer.setVisible(false);
        footer.setManaged(false);
        getStyleClass().add("claude-doc-frame");
        // the surface's own sheet, on the surface: a card in another plugin's module has no way to find it
        java.net.URL css = DocumentSurface.class.getResource("document-surface.css");
        if (css != null) {
            getStylesheets().add(css.toExternalForm());
        }
        setTop(header);
        setCenter(scroll);
        setBottom(new VBox(horizontal.bar(), footer));
        // A sideways gesture moves every unwrapped area together, through the one bar. Left to the areas, the one
        // under the pointer would move alone. A gesture is taken as going one way: mostly sideways, or mostly down.
        scroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (!horizontal.isShowing() || event.getDeltaX() == 0) {
                return;
            }
            if (Math.abs(event.getDeltaX()) >= Math.abs(event.getDeltaY())) {
                horizontal.scrollBy(-event.getDeltaX());
            } else {
                double range = stack.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
                if (range > 0) {
                    scroll.setVvalue(Math.clamp(scroll.getVvalue() - event.getDeltaY() / range, 0, 1));
                }
            }
            event.consume();
        });
        scroll.vvalueProperty().addListener((obs, was, is) -> lookSoon());
        scroll.viewportBoundsProperty().addListener((obs, was, is) -> lookSoon());
    }

    // ── Regions (dev-interaction-surface-family) ──────────────────────────

    /**
     * Sets the header region, which stays put while the body scrolls; {@code null} removes it.
     *
     * @param node the header's content, or {@code null}
     */
    public void setHeader(Node node) {
        header.getChildren().setAll(node == null ? List.of() : List.of(node));
        header.setVisible(node != null);
        header.setManaged(node != null);
    }

    /**
     * Whether the vertical scroll bar stands whether or not there is anything to scroll. A host
     * whose room does not follow its content keeps the bar, so the text has one measure whatever
     * the document's length, and a short document shows a bar with nowhere to go rather than none.
     *
     * @param always whether the bar always stands; otherwise it stands when the content is taller than its room
     */
    public void setVerticalBarAlways(boolean always) {
        scroll.setVbarPolicy(always ? ScrollPane.ScrollBarPolicy.ALWAYS : ScrollPane.ScrollBarPolicy.AS_NEEDED);
    }

    /**
     * Sets the footer region, which stays put while the body scrolls; {@code null} removes it.
     *
     * @param node the footer's content, or {@code null}
     */
    public void setFooter(Node node) {
        footer.getChildren().setAll(node == null ? List.of() : List.of(node));
        footer.setVisible(node != null);
        footer.setManaged(node != null);
    }

    /**
     * The surface's one horizontal scrollbar: a host that stacks unwrapped text, as a ledger of
     * source lines is, takes its areas from here ({@link HorizontalScroll#area}), and the bar
     * stands under the body while some line is wider than the surface.
     *
     * @return the horizontal scroll
     */
    public HorizontalScroll horizontalScroll() {
        return horizontal;
    }

    /**
     * Sets the margin regions' widths in tracks of the twelve-track grid: {@code 2, 2} gives the
     * family's left-margin 2 | body 8 | right-margin 2; {@code 0, 0} (the default) is body alone,
     * with no row wrapping at all. Takes effect on the next rebuild of the blocks.
     *
     * @param left  the left margin's tracks, 0 for none
     * @param right the right margin's tracks, 0 for none
     */
    public void setMarginTracks(int left, int right) {
        this.leftTracks = Math.clamp(left, 0, TRACKS - 1);
        this.rightTracks = Math.clamp(right, 0, TRACKS - 1 - leftTracks);
    }

    /**
     * The vertical scroll position of the body, 0 at the top and 1 at the end; a host that keeps
     * a running head current listens to it.
     *
     * @return the scroll position property
     */
    public ReadOnlyDoubleProperty scrollPositionProperty() {
        return scroll.vvalueProperty();
    }

    // ── Turn-less documents ───────────────────────────────────────────────

    /**
     * Replaces the document with blocks a host built itself — a document that is not a
     * conversation, such as one materialized from git. The turns are cleared; the blocks are
     * stacked in order, each with its marginalia when the margin regions are on.
     *
     * @param document the blocks in order
     */
    public void setBlocks(List<DocumentBlock> document) {
        clearSelection();
        stack.getChildren().clear();
        blocks.clear();
        turns.clear();
        turnStarts.clear();
        exchangeHeaders.clear();
        exchangeTitles.clear();
        // A long document is built as it is read: past a few screens of blocks, a block stands as a
        // placeholder of its expected height until it nears the viewport, and goes back to one when
        // it is far again. A short document is built whole, as it always was.
        virtual = document.size() > VIRTUAL_FROM;
        for (DocumentBlock block : document) {
            blocks.add(block);
            if (virtual) {
                block.released();
                stack.getChildren().add(cell(block));
            } else {
                stack.getChildren().add(stackNode(block));
                block.realized();
            }
        }
        if (virtual) {
            lookSoon();
        }
    }

    // ── Building blocks as they are read ─────────────────────────────────

    /** How many blocks a document has before it is built as it is read rather than whole. */
    static final int VIRTUAL_FROM = 80;
    /** How far beyond the viewport, in viewport heights, a block is built. */
    private static final double BUILD_WITHIN = 1.5;
    /** How far beyond the viewport, in viewport heights, a built block is let go. */
    private static final double RELEASE_BEYOND = 5;
    /** How many blocks one look builds at most, so a look never holds the thread long. */
    private static final int BUILD_BATCH = 24;
    /** The room an unbuilt block takes when nothing better is known. */
    private static final double DEFAULT_ESTIMATE = 40;
    private static final String PLACEHOLDER_STYLE = "doc-block-placeholder";
    /** How many layouts a newly built block keeps its stand-in's room for: its first, and those its text's height arrives in. */
    private static final int FLOOR_PULSES = 3;

    private boolean virtual;
    private boolean virtualizing;
    private boolean lookPending;
    /** How many times in a row the surface looked before it was laid out. */
    private int unlaidLooks;

    /**
     * Looks once the current pulse has laid the stack out: scroll events come many to a pulse and
     * a look reads the blocks' places from the last layout, so one look a pulse is enough, and a
     * look after a change waits a pulse for the new heights.
     */
    private void lookSoon() {
        if (!virtual || lookPending) {
            return;
        }
        lookPending = true;
        Platform.runLater(() -> Platform.runLater(() -> {
            lookPending = false;
            virtualize();
        }));
    }

    /**
     * A block's place in the stack while the document is built as it is read: a cell holding a
     * stand-in of the block's expected height, and, once the block is built, its row. The row is
     * built into the cell once and then only switched on and off, since a node that joins the
     * scene pays for its styles every time, and that was the whole cost of scrolling.
     */
    private static final class Cell extends javafx.scene.layout.StackPane {
        final javafx.scene.layout.Region stand = new javafx.scene.layout.Region();
        /** Holds the row, and is what is switched: the row's own visibility may be bound, as a hidden block's is. */
        final javafx.scene.layout.StackPane holder = new javafx.scene.layout.StackPane();
        Node row;
        /** While the row is newly shown: counts the layouts since, and lets the stand-in's room go after {@link #FLOOR_PULSES}. */
        private Runnable floor;
        private javafx.scene.Scene floorScene;
        private int floorPulses;

        Cell(double height) {
            stand.getStyleClass().add(PLACEHOLDER_STYLE);
            standAt(height);
            holder.setAlignment(javafx.geometry.Pos.TOP_LEFT);
            holder.setVisible(false);
            holder.setManaged(false);
            getChildren().addAll(stand, holder);
            setAlignment(javafx.geometry.Pos.TOP_LEFT);
        }

        void standAt(double height) {
            double h = height > 0 ? height : DEFAULT_ESTIMATE;
            stand.setMinHeight(h);
            stand.setPrefHeight(h);
            stand.setMaxHeight(h);
        }

        /** Shows the row, built into the cell the first time. */
        void show(Node built) {
            if (row == null) {
                row = built;
                holder.getChildren().add(row);
            }
            holder.setVisible(true);
            holder.setManaged(true);
            stand.setVisible(false);
            keepRoom();
        }

        /**
         * Keeps the stand-in's room under the row until the row has been laid out. A content-height
         * text area answers one line's height until its first layout and its true height a layout
         * later, while it draws all its lines from the start: given only the room it first asks for,
         * it is drawn over the blocks below it for as long as those layouts take, which for a batch
         * of long blocks is long enough to see (the Source view of a book, 2026-11-10). The room is
         * the block's expected height, or the height it stood at when it was last built.
         */
        private void keepRoom() {
            letRoomGo();
            javafx.scene.Scene scene = getScene();
            if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
                stand.setManaged(false);                             // no pulses to count: the row's own height at once
                return;
            }
            stand.setManaged(true);
            floorPulses = 0;
            floorScene = scene;
            floor = () -> {
                if (++floorPulses >= FLOOR_PULSES) {
                    letRoomGo();
                    stand.setManaged(false);
                }
            };
            scene.addPostLayoutPulseListener(floor);
        }

        private void letRoomGo() {
            if (floor != null) {
                floorScene.removePostLayoutPulseListener(floor);
                floor = null;
                floorScene = null;
            }
        }

        /** Shows the stand-in at the height the row stood at; the row stays, switched off. */
        void hide(double height) {
            letRoomGo();
            standAt(height);
            holder.setVisible(false);
            holder.setManaged(false);
            stand.setVisible(true);
            stand.setManaged(true);
        }
    }

    private Node cell(DocumentBlock block) {
        Cell cell = new Cell(block.estimatedHeight());
        block.setRow(cell);
        return followShown(block.node(), cell);
    }

    /**
     * Builds the blocks within reach of the viewport and lets go of those far from it. Called
     * when the surface scrolls or is resized, and again after a build changes the stack's
     * heights, until nothing more changes.
     */
    private void virtualize() {
        if (!virtual || virtualizing || blocks.isEmpty()) {
            return;
        }
        double contentHeight = stack.getBoundsInLocal().getHeight();
        if (contentHeight <= 0 || scroll.getViewportBounds().getHeight() <= 0) {
            // not laid out yet: every block would read as at the top; look once the stack has its heights
            if (unlaidLooks++ < 20) {
                lookSoon();
            }
            return;
        }
        unlaidLooks = 0;
        virtualizing = true;
        try {
            // the blocks' places are read from the last layout: forcing one here measured every built block's text each scroll
            double viewport = Math.max(1, scroll.getViewportBounds().getHeight());
            double range = Math.max(0, contentHeight - viewport);
            double top = scroll.getVvalue() * range;
            double bottom = top + viewport;
            boolean changed = false;
            int built = 0;
            for (int i = 0; i < blocks.size(); i++) {
                DocumentBlock block = blocks.get(i);
                Bounds bounds = block.stackNode().getBoundsInParent();
                boolean near = bounds.getMaxY() >= top - BUILD_WITHIN * viewport && bounds.getMinY() <= bottom + BUILD_WITHIN * viewport;
                boolean far = bounds.getMaxY() < top - RELEASE_BEYOND * viewport || bounds.getMinY() > bottom + RELEASE_BEYOND * viewport;
                if ((near || block.isPinned()) && !block.isRealized()) {
                    realize(i);                                          // a pinned block is built wherever it stands
                    changed = true;
                    if (++built >= BUILD_BATCH) {
                        break;                                           // a screenful at a time, so the thread is never held long
                    }
                } else if (far && block.isRealized() && !block.isPinned()) {
                    release(i);
                    changed = true;
                }
            }
            if (changed) {
                lookSoon();                                              // the heights moved: look again once they are laid out
            }
        } finally {
            virtualizing = false;
        }
    }

    /** Builds one block into its cell, or switches it back on. */
    private void realize(int index) {
        DocumentBlock block = blocks.get(index);
        if (block.isRealized() || index >= stack.getChildren().size() || !(stack.getChildren().get(index) instanceof Cell cell)) {
            return;
        }
        if (cell.row == null) {
            Node row = stackNode(block);                                 // sets the block's row: put the cell back as what stands in the stack
            cell.show(row);
        } else {
            cell.show(cell.row);
        }
        block.setRow(cell);
        block.realized();
    }

    /** Switches a built block off, its stand-in at the height it stood at. */
    private void release(int index) {
        DocumentBlock block = blocks.get(index);
        if (!block.isRealized() || index >= stack.getChildren().size() || !(stack.getChildren().get(index) instanceof Cell cell)) {
            return;
        }
        double height = cell.getBoundsInParent().getHeight();
        cell.hide(height > 0 ? height : block.estimatedHeight());
        block.released();
    }

    /**
     * Makes sure a block is built, for a caller about to select in it or scroll to it.
     *
     * @param index the block
     */
    public void ensureBuilt(int index) {
        if (virtual && index >= 0 && index < blocks.size() && !blocks.get(index).isRealized()) {
            realize(index);
            stack.applyCss();
            stack.layout();
        }
    }

    /** @return how many blocks the surface holds */
    public int blockCount() {
        return blocks.size();
    }

    /**
     * @param index a block index
     * @return the block
     */
    public DocumentBlock block(int index) {
        return blocks.get(index);
    }

    /**
     * The block at the top of the viewport, or -1 for an empty surface.
     *
     * @return the block index
     */
    public int topBlockIndex() {
        Viewpoint viewpoint = viewpoint();
        return viewpoint == null ? -1 : viewpoint.blockIndex();
    }

    /**
     * Scrolls so the block sits at the viewport top, keeping {@code margin} pixels above it.
     *
     * @param blockIndex the block to reveal
     */
    public void revealBlock(int blockIndex) {
        scrollToBlock(blockIndex);
    }

    /**
     * Sets what a paged rendering of this surface prints when the surface holds no turns — a
     * host that stacks its own blocks says what the pages carry.
     *
     * @param source supplies the print source when a paged view is built, or {@code null} to print the turns
     */
    public void setPrintSource(Supplier<PrintSource> source) {
        this.printSource = source;
    }

    /**
     * What a paged rendering prints: the print source when one is set and the surface holds no
     * turns, else the turns.
     *
     * @return the source to paginate
     */
    public PrintSource printSource() {
        if (turns.isEmpty() && printSource != null) {
            PrintSource source = printSource.get();
            return source == null ? PrintSource.EMPTY : source;
        }
        return PrintSource.ofTurns(turns());
    }

    /**
     * The node stacked for a block: its body alone with the margin regions off, else a row of
     * the grid with the body in the body tracks and the marginalia in the margins, so every
     * block's body aligns whether or not it has marginalia.
     */
    private Node stackNode(DocumentBlock block) {
        return followShown(block.node(), stackRow(block));
    }

    /** Lays a block out as absent while its node says it is hidden ({@link MarkdownRichText#BLOCK_SHOWN}). */
    private static Node followShown(Node body, Node stacked) {
        if (body != null && body.getProperties().get(MarkdownRichText.BLOCK_SHOWN)
                instanceof javafx.beans.value.ObservableBooleanValue shown) {
            stacked.visibleProperty().bind(shown);
            stacked.managedProperty().bind(shown);
        }
        return stacked;
    }

    /**
     * What a figure in a block's body may take from the margins either side, the {@code full-bleed}
     * of the family's grid. Answered from the body's width, not the row's, so a figure asked for
     * its height at a new width need not wait for the row to take that width.
     *
     * @param node      a node in the block's body
     * @param bodyWidth the width the body is given
     * @return the room to the left and right, none when the node is not in a row with margins
     */
    public static Insets bleedBeside(Node node, double bodyWidth) {
        for (Node at = node; at != null; at = at.getParent()) {
            if (at instanceof GridPane row && row.getStyleClass().contains(ROW_STYLE)) {
                double left = row.getColumnConstraints().get(0).getPercentWidth();
                double body = row.getColumnConstraints().get(1).getPercentWidth();
                double right = row.getColumnConstraints().get(2).getPercentWidth();
                // a body with no share of its own takes what the margins leave
                double share = body > 0 ? body : 100 - Math.max(0, left) - Math.max(0, right);
                return new Insets(0, right > 0 ? bodyWidth * right / share + ROW_GAP : 0,
                        0, left > 0 ? bodyWidth * left / share + ROW_GAP : 0);
            }
        }
        return Insets.EMPTY;
    }

    private Node stackRow(DocumentBlock block) {
        // With no left tracks, what a block keeps in its left margin (a fold mark, with the date gone) still
        // stands beside the body, in a column as narrow as itself.
        boolean slot = leftTracks == 0 && block.leftMarginalia() != null;
        if (leftTracks == 0 && rightTracks == 0 && !slot) {
            block.setRow(null);
            return block.node();
        }
        GridPane row = new GridPane();
        row.getStyleClass().add(ROW_STYLE);
        row.setHgap(ROW_GAP);
        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints body = new ColumnConstraints();
        if (slot) {
            left.setHgrow(Priority.NEVER);
        } else {
            left.setPercentWidth(100.0 * leftTracks / TRACKS);
            body.setPercentWidth(100.0 * (TRACKS - leftTracks - rightTracks) / TRACKS);
        }
        body.setHgrow(Priority.ALWAYS);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(100.0 * rightTracks / TRACKS);
        row.getColumnConstraints().addAll(left, body, right);
        if (block.leftMarginalia() != null) {
            row.add(block.leftMarginalia(), 0, 0);
            GridPane.setValignment(block.leftMarginalia(), javafx.geometry.VPos.TOP);
        }
        row.add(block.node(), 1, 0);
        GridPane.setValignment(block.node(), javafx.geometry.VPos.TOP);
        GridPane.setHgrow(block.node(), Priority.ALWAYS);
        if (block.node() instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        if (block.rightMarginalia() != null && rightTracks > 0) {
            row.add(block.rightMarginalia(), 2, 0);
            GridPane.setValignment(block.rightMarginalia(), javafx.geometry.VPos.TOP);
        }
        block.setRow(row);
        return row;
    }

    /**
     * Re-wires block production (a new view coordinate or font size). Takes effect on the next
     * {@link #setTurns} or {@link #appendTurn} — callers refreshing an existing document follow
     * with {@code setTurns(...)}, which rebuilds every block through the new factory.
     *
     * @param blockFactory the new factory
     */
    public void setBlockFactory(BlockFactory blockFactory) {
        this.blockFactory = blockFactory;
        if (blockFactory != null) {
            blockFactory.setTableColumnWidths(tableColumnWidths);
        }
    }

    /** The active conversation's table-column-width store, re-applied across factory swaps. */
    private TableColumnWidths tableColumnWidths;

    /**
     * Sets the store for user-chosen table column widths (ike-issues#1034), normally the active
     * conversation's preferences-backed store. Applies to blocks built afterwards — callers wire
     * it before {@link #setTurns}, alongside the exchange-title provider.
     *
     * @param store the width store, or {@code null} to make column resizing session-local
     */
    public void setTableColumnWidths(TableColumnWidths store) {
        this.tableColumnWidths = store;
        if (blockFactory != null) {
            blockFactory.setTableColumnWidths(store);
        }
    }

    /**
     * Replaces the document with the given turns, in order.
     *
     * @param entries the conversation's turns
     */
    public void setTurns(List<MarkdownRichText.Entry> entries) {
        turns.clear();
        turns.addAll(entries);
        rebuild();
    }

    /**
     * Appends one turn's blocks to the stack.
     *
     * @param turn the turn to append
     */
    public void appendTurn(MarkdownRichText.Entry turn) {
        turns.add(turn);
        appendBlocksFor(turn);
    }

    /**
     * Pops turns from the end until {@code turnCount} remain — the failed-send rollback.
     *
     * @param turnCount the number of turns to keep
     */
    public void truncateTurns(int turnCount) {
        if (turnCount < 0 || turnCount >= turns.size()) {
            return;
        }
        clearSelection();
        int firstRemovedBlock = turnStarts.get(turnCount);
        stack.getChildren().remove(firstRemovedBlock, stack.getChildren().size());
        blocks.subList(firstRemovedBlock, blocks.size()).clear();
        turnStarts.subList(turnCount, turnStarts.size()).clear();
        turns.subList(turnCount, turns.size()).clear();
        int keptHeaders = (int) exchangeHeaders.stream()
                .filter(h -> h < firstRemovedBlock).count();
        exchangeHeaders.removeIf(h -> h >= firstRemovedBlock);
        exchangeTitles.subList(keptHeaders, exchangeTitles.size()).clear();
    }

    /**
     * The number of turns on the surface.
     *
     * @return the turn count
     */
    public int turnCount() {
        return turns.size();
    }

    /**
     * The surface's turns, in order — the source a paged print rendering rebuilds from (it renders
     * its own print column at page width, rather than reparenting these live blocks).
     *
     * @return an unmodifiable snapshot of the turns
     */
    public List<MarkdownRichText.Entry> turns() {
        return List.copyOf(turns);
    }

    /**
     * Scrolls so the turn's header block sits at the top of the viewport. Deferred two pulses so
     * freshly appended content-height blocks have settled their heights before the offset is
     * computed (the successor of the old transcript's paragraph-anchor scroll).
     *
     * @param turnIndex the turn to reveal
     */
    public void scrollToTurn(int turnIndex) {
        if (turnIndex < 0 || turnIndex >= turnStarts.size()) {
            return;
        }
        int blockIndex = turnStarts.get(turnIndex);
        // Two chained pulses: the first lets the new blocks join layout, the second reads settled
        // bounds (useContentHeight areas report their height one pulse after their model lands).
        Platform.runLater(() -> Platform.runLater(() -> scrollToBlock(blockIndex)));
    }

    // ── Viewpoint (ike-issues#943) ───────────────────────────────────────

    /**
     * A text selection within one block's prose area, in the block-stack's stable coordinates.
     *
     * @param blockIndex   the block holding the selection
     * @param anchorIndex  the anchor's paragraph index within the block's model
     * @param anchorOffset the anchor's character offset within its paragraph
     * @param caretIndex   the caret's paragraph index within the block's model
     * @param caretOffset  the caret's character offset within its paragraph
     */
    public record TextSelection(int blockIndex, int anchorIndex, int anchorOffset,
                                int caretIndex, int caretOffset) {
    }

    /**
     * The surface's <em>equivalent place</em> (IKE-Network/ike-issues#943): the viewport top sits
     * {@code fraction} of the way down block {@code blockIndex}, plus the text selection if there
     * is one. Block index is the stable coordinate across a re-render that rebuilds the same
     * turns (a coordinate change, #942, or a font-size change) and across a reopen of the same
     * conversation; the fraction degrades gracefully when block heights shift. One value serves
     * both journeys — held across a rebuild in memory, or {@linkplain #encode() encoded} into
     * preferences when leaving and {@linkplain #decode(String) decoded} on return.
     *
     * @param blockIndex the block at the viewport top
     * @param fraction   how far down that block the viewport top sits (0 = its top edge)
     * @param selection  the text selection, or {@code null} when there is none
     */
    public record Viewpoint(int blockIndex, double fraction, TextSelection selection) {

        /**
         * Encodes this viewpoint as a compact string for a preferences value.
         *
         * @return the encoded form
         */
        public String encode() {
            StringBuilder sb = new StringBuilder()
                    .append(blockIndex).append(':').append(fraction);
            if (selection != null) {
                sb.append(':').append(selection.blockIndex())
                        .append(':').append(selection.anchorIndex())
                        .append(':').append(selection.anchorOffset())
                        .append(':').append(selection.caretIndex())
                        .append(':').append(selection.caretOffset());
            }
            return sb.toString();
        }

        /**
         * Decodes a viewpoint previously written by {@link #encode()}.
         *
         * @param encoded the encoded form; blank or malformed input yields {@code null}
         * @return the viewpoint, or {@code null}
         */
        public static Viewpoint decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String[] parts = encoded.split(":");
                int block = Integer.parseInt(parts[0]);
                double fraction = Double.parseDouble(parts[1]);
                TextSelection selection = (parts.length >= 7)
                        ? new TextSelection(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                                Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6]))
                        : null;
                return new Viewpoint(block, fraction, selection);
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    /**
     * Captures the surface's current viewpoint.
     *
     * @return the viewpoint, or {@code null} for an empty surface
     */
    public Viewpoint viewpoint() {
        if (blocks.isEmpty()) {
            return null;
        }
        double contentHeight = stack.getBoundsInLocal().getHeight();
        double range = Math.max(0, contentHeight - scroll.getViewportBounds().getHeight());
        double y = scroll.getVvalue() * range;
        int blockIndex = 0;
        double fraction = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Bounds bounds = blocks.get(i).stackNode().getBoundsInParent();
            if (bounds.getMinY() > y) {
                break;
            }
            blockIndex = i;
            fraction = bounds.getHeight() <= 0 ? 0
                    : Math.clamp((y - bounds.getMinY()) / bounds.getHeight(), 0, 1);
        }
        return new Viewpoint(blockIndex, fraction, currentTextSelection());
    }

    /** Pulse cap for {@link #restoreViewpoint}'s settle wait (~1 s at 60 fps). */
    private static final int MAX_SETTLE_PULSES = 60;

    /**
     * Restores a viewpoint after a rebuild (or on reopen). Waits for the stack's layout to
     * settle before computing offsets: a fixed two-pulse defer is enough after an in-place
     * rebuild, but on a fresh reopen the content-height prose areas settle their heights over
     * several pulses (fonts, first layout of a whole conversation) — scrolling before that
     * computes offsets against a half-built stack, which is exactly the "selection restored but
     * not the viewport" failure (#943). The block index clamps to the current stack and the
     * selection clamps to its block's document, so a viewpoint captured against a longer
     * document degrades to the nearest existing place.
     *
     * @param viewpoint the viewpoint to restore; {@code null} is a no-op
     */
    public void restoreViewpoint(Viewpoint viewpoint) {
        restoreViewpoint(viewpoint, null);
    }

    /**
     * As {@link #restoreViewpoint(Viewpoint)}, additionally running {@code afterApplied} once the
     * viewpoint has actually been applied. Restoration is asynchronous (it waits for layout to
     * settle), so a host that must not treat the surface's current position as the reader's
     * place until the return has happened — e.g. to keep captures from overwriting a
     * not-yet-applied restore (#943) — keys that off this callback.
     *
     * @param viewpoint    the viewpoint to restore; {@code null} is a no-op
     * @param afterApplied run on the FX thread after the viewpoint is applied; may be {@code null}
     */
    public void restoreViewpoint(Viewpoint viewpoint, Runnable afterApplied) {
        if (viewpoint == null) {
            return;
        }
        awaitSettledLayout(0, -1, () -> {
            if (blocks.isEmpty()) {
                return;
            }
            scrollToBlockOffset(Math.min(viewpoint.blockIndex(), blocks.size() - 1),
                    viewpoint.fraction());
            TextSelection selection = viewpoint.selection();
            if (selection != null && selection.blockIndex() >= 0
                    && selection.blockIndex() < blocks.size()) {
                ensureBuilt(selection.blockIndex());
                RichTextArea area = blocks.get(selection.blockIndex()).richText();
                if (area != null && area.getParagraphCount() > 0) {
                    // Blocks are content-height (no internal scroll), so selecting here cannot
                    // fight the surface scroll restored above.
                    area.select(
                            RichTextViewpoint.clamp(area, selection.anchorIndex(),
                                    selection.anchorOffset()),
                            RichTextViewpoint.clamp(area, selection.caretIndex(),
                                    selection.caretOffset()));
                    selectedArea = area;
                }
            }
            if (afterApplied != null) {
                afterApplied.run();
            }
        });
    }

    /**
     * Runs {@code action} once the stack's content height is nonzero and unchanged across
     * consecutive pulses and the viewport has bounds — the earliest moment scroll offsets are
     * meaningful. Each poll forces a layout pass first: content-height prose areas report their
     * height only after real layout, and bounds can sit stale-but-equal across pulses before the
     * first pass — "stable" must mean genuinely settled, not not-yet-started (#943). After the
     * action runs, one more pulse verifies the stack did not move (late font/wrap growth); if it
     * did, the offsets were computed against a half-built stack and the action re-runs (it is
     * idempotent). Capped at {@link #MAX_SETTLE_PULSES} so a pathological layout cannot stall
     * the restore forever (the action then runs against the best available geometry).
     */
    private void awaitSettledLayout(int attempt, double lastHeight, Runnable action) {
        Platform.runLater(() -> {
            stack.applyCss();
            stack.layout();
            double height = stack.getBoundsInLocal().getHeight();
            boolean settled = attempt >= 2 && height > 0 && height == lastHeight
                    && scroll.getViewportBounds().getHeight() > 0;
            if (settled || attempt >= MAX_SETTLE_PULSES) {
                action.run();
                if (attempt < MAX_SETTLE_PULSES) {
                    final double appliedHeight = height;
                    Platform.runLater(() -> {
                        stack.applyCss();
                        stack.layout();
                        double verifyHeight = stack.getBoundsInLocal().getHeight();
                        if (verifyHeight != appliedHeight) {
                            awaitSettledLayout(attempt + 1, verifyHeight, action);
                        }
                    });
                }
            } else {
                awaitSettledLayout(attempt + 1, height, action);
            }
        });
    }

    /** The first block-level text selection on the surface, or {@code null} when there is none. */
    private TextSelection currentTextSelection() {
        for (int i = 0; i < blocks.size(); i++) {
            RichTextArea area = blocks.get(i).richText();
            if (area != null && area.hasNonEmptySelection()) {
                SelectionSegment segment = area.getSelection();
                return new TextSelection(i, segment.getAnchor().index(),
                        segment.getAnchor().offset(),
                        segment.getCaret().index(), segment.getCaret().offset());
            }
        }
        return null;
    }

    // ── Find ─────────────────────────────────────────────────────────────

    /**
     * One find match. Prose matches are selectable in their block's text area; header and node
     * blocks match on their plain-text projection and are revealed by scrolling only.
     *
     * @param blockIndex     the block holding the match
     * @param paragraphIndex the paragraph within a prose block's model, or {@code -1}
     * @param start          the match's start offset within the paragraph, or {@code -1}
     * @param end            the match's end offset within the paragraph, or {@code -1}
     * @param selectable     whether {@link #select} can place a text selection on this match
     */
    public record DocumentMatch(int blockIndex, int paragraphIndex, int start, int end,
                                boolean selectable) {
    }

    /**
     * Finds every case-insensitive occurrence of {@code query} across the document's blocks, in
     * document order.
     *
     * @param query the text to find; blank finds nothing
     * @return the matches, in document order
     */
    public List<DocumentMatch> findAll(String query) {
        List<DocumentMatch> matches = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return matches;
        }
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (block.model() != null) {
                for (RichTextSearch.Match m : RichTextSearch.findAll(block.model(), query)) {
                    matches.add(new DocumentMatch(i, m.paragraphIndex(), m.start(), m.end(), true));
                }
            } else {
                String hay = block.plainText().toLowerCase(Locale.ROOT);
                String needle = query.toLowerCase(Locale.ROOT);
                int from = 0;
                int at;
                while ((at = hay.indexOf(needle, from)) >= 0) {
                    matches.add(new DocumentMatch(i, -1, -1, -1, false));
                    from = at + needle.length();
                }
            }
        }
        return matches;
    }

    /**
     * Reveals a match: scrolls its block into view and, for a prose match, selects the matched
     * range in the block's text area.
     *
     * @param match the match to reveal (from {@link #findAll})
     */
    public void select(DocumentMatch match) {
        if (match == null || match.blockIndex() < 0 || match.blockIndex() >= blocks.size()) {
            return;
        }
        clearSelection();
        ensureBuilt(match.blockIndex());
        DocumentBlock block = blocks.get(match.blockIndex());
        if (match.selectable() && block.richText() != null) {
            RichTextArea area = block.richText();
            area.select(TextPos.ofLeading(match.paragraphIndex(), match.start()),
                    TextPos.ofLeading(match.paragraphIndex(), match.end()));
            selectedArea = area;
            // Reveal the match itself, not merely the block's top edge: a match deep inside a
            // tall content-height block would otherwise select off-screen. The paragraph's
            // fraction of the model approximates its fraction of the block's height.
            int paragraphCount = block.model() == null ? 0 : block.model().size();
            double fraction = paragraphCount <= 1 ? 0
                    : (double) match.paragraphIndex() / (paragraphCount - 1);
            scrollToBlockOffset(match.blockIndex(), fraction);
        } else {
            scrollToBlock(match.blockIndex());
        }
    }

    /** Collapses the current find selection, if any. */
    public void clearSelection() {
        if (selectedArea != null) {
            selectedArea.select(TextPos.ZERO);
            selectedArea = null;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** Rebuilds every block from {@link #turns} through the current factory. */
    private void rebuild() {
        clearSelection();
        stack.getChildren().clear();
        virtual = false;
        blocks.clear();
        turnStarts.clear();
        exchangeHeaders.clear();
        exchangeTitles.clear();
        List<MarkdownRichText.Entry> snapshot = new ArrayList<>(turns);
        turns.clear();
        for (MarkdownRichText.Entry turn : snapshot) {
            appendTurn(turn);
        }
    }

    /** Builds and stacks one turn's blocks, recording the turn's start block. */
    private void appendBlocksFor(MarkdownRichText.Entry turn) {
        turnStarts.add(blocks.size());
        // A user turn opens a new exchange: a disclosure header block whose
        // toggle hides the exchange's blocks IN PLACE (ike-issues#1033).
        // Blocks never reparent, so truncation, viewpoints, and scroll
        // offsets keep their flat stack indices.
        boolean headered = turn.role() == MarkdownRichText.Role.USER;
        if (headered) {
            int headerIndex = blocks.size();
            exchangeHeaders.add(headerIndex);
            Node header = exchangeHeader(turn, headerIndex);
            if (!blocks.isEmpty()) {
                VBox.setMargin(header, new Insets(TURN_SPACING, 0, 0, 0));
            }
            DocumentBlock headerBlock = DocumentBlock.of(header, "");
            blocks.add(headerBlock);
            stack.getChildren().add(stackNode(headerBlock));
        }
        List<DocumentBlock> turnBlocks = blockFactory.turnBlocks(turn);
        // Blocks of a conversation lay out as the surface's body alone; a row wraps them only
        // when a host has turned the margin regions on.
        if (headered && turnBlocks.size() > 1) {
            // The exchange header owns the question identity; the "You"
            // role heading underneath it was pure redundancy (KEC review).
            // The question's full prose body stays.
            turnBlocks = turnBlocks.subList(1, turnBlocks.size());
        }
        boolean first = true;
        for (DocumentBlock block : turnBlocks) {
            Node node = block.node();
            if (first && !blocks.isEmpty() && exchangeHeaders.isEmpty()) {
                // A little extra air above each turn header, so turns read as units.
                VBox.setMargin(node, new Insets(TURN_SPACING, 0, 0, 0));
            }
            blocks.add(block);
            stack.getChildren().add(stackNode(block));
            first = false;
        }
    }

    /** Block indices of exchange headers, in order (ike-issues#1033). */
    private final java.util.List<Integer> exchangeHeaders = new java.util.ArrayList<>();
    /** Each exchange header's title label, for post-hoc naming. */
    private final java.util.List<javafx.scene.control.Label> exchangeTitles =
            new java.util.ArrayList<>();
    /** Supplies a generated exchange title by ordinal, or null for none yet. */
    private java.util.function.IntFunction<String> exchangeTitleProvider;

    /**
     * Installs the source of Claude-generated exchange titles (KEC design:
     * numbered named turns). Applies to headers built after the call.
     *
     * @param provider ordinal → title, or {@code null} to clear
     */
    public void setExchangeTitleProvider(
            java.util.function.IntFunction<String> provider) {
        this.exchangeTitleProvider = provider;
    }

    /**
     * Names an exchange after the fact — the async title generation
     * landing. No-op for an unknown ordinal.
     *
     * @param ordinal zero-based exchange ordinal
     * @param title   the generated title
     */
    public void setExchangeTitle(int ordinal, String title) {
        if (ordinal >= 0 && ordinal < exchangeTitles.size()) {
            exchangeTitles.get(ordinal).setText(title);
        }
    }

    /** Builds one exchange's disclosure header: arrow + numbered title; click toggles. */
    private Node exchangeHeader(MarkdownRichText.Entry turn, int headerIndex) {
        javafx.scene.control.Label arrow = new javafx.scene.control.Label("▾");
        arrow.setMinWidth(16);
        int ordinal = exchangeHeaders.size() - 1;
        String generated = exchangeTitleProvider == null
                ? null : exchangeTitleProvider.apply(ordinal);
        String text;
        if (generated != null && !generated.isBlank()) {
            text = generated;
        } else {
            text = turn.content() == null ? "" : turn.content()
                    .replaceAll("\\s+", " ").strip();
            if (text.length() > 90) {
                text = text.substring(0, 90).strip() + "…";
            }
        }
        double base = blockFactory == null ? 14 : blockFactory.baseFontSize();
        javafx.scene.control.Label qBadge =
                new javafx.scene.control.Label("P" + (ordinal + 1));
        // The P-number (Prompt) is the exchange's distinct identity (KEC
        // design): semantic — prompts are not always questions — and never
        // confusable with numbering inside generated text.
        qBadge.setStyle("-fx-font-weight: bold; -fx-text-fill: #b15c00;"
                + " -fx-font-size: " + Math.round(base) + "px;");
        javafx.scene.control.Label title = new javafx.scene.control.Label(text);
        exchangeTitles.add(title);
        // The header outranks the prose below it: same size, heavier.
        title.setStyle("-fx-font-weight: bold; -fx-font-size: "
                + Math.round(base) + "px;");
        javafx.scene.layout.HBox header =
                new javafx.scene.layout.HBox(6, arrow, qBadge, title);
        header.getStyleClass().add("claude-exchange-header");
        // A distinct band no generated content uses: light fill plus an
        // accent left rule, so exchange boundaries read at a glance.
        header.setStyle("-fx-cursor: hand; -fx-padding: 3 6 3 6;"
                + " -fx-background-color: #f4f0ea;"
                + " -fx-border-color: #b15c00 transparent transparent"
                + " transparent; -fx-border-width: 0 0 0 0;"
                + " -fx-background-radius: 4;");
        header.setOnMouseClicked(e -> toggleExchange(headerIndex, arrow));
        return header;
    }

    /** The exchange's content range: (header, next header or end). */
    private int exchangeEnd(int headerIndex) {
        for (int h : exchangeHeaders) {
            if (h > headerIndex) {
                return h;
            }
        }
        return blocks.size();
    }

    private void toggleExchange(int headerIndex, javafx.scene.control.Label arrow) {
        boolean collapse = "▾".equals(arrow.getText());
        setExchangeCollapsed(headerIndex, arrow, collapse);
    }

    private void setExchangeCollapsed(int headerIndex,
                                      javafx.scene.control.Label arrow,
                                      boolean collapsed) {
        arrow.setText(collapsed ? "▸" : "▾");
        for (int i = headerIndex + 1; i < exchangeEnd(headerIndex); i++) {
            Node node = blocks.get(i).stackNode();
            node.setVisible(!collapsed);
            node.setManaged(!collapsed);
        }
    }

    /**
     * Collapses or expands every exchange (the toolbar's Expand all /
     * Collapse all, ike-issues#1033).
     *
     * @param collapsed true to collapse all exchanges
     */
    public void setAllExchangesCollapsed(boolean collapsed) {
        for (int h : exchangeHeaders) {
            if (blocks.get(h).node() instanceof javafx.scene.layout.HBox header
                    && !header.getChildren().isEmpty()
                    && header.getChildren().get(0)
                            instanceof javafx.scene.control.Label arrow) {
                setExchangeCollapsed(h, arrow, collapsed);
            }
        }
    }

    /** Scrolls so the block sits at the viewport top (clamped to the scroll range). */
    private void scrollToBlock(int blockIndex) {
        scrollToBlockOffset(blockIndex, 0);
    }

    /**
     * Scrolls so the point {@code fraction} of the way down the block sits at the viewport top.
     *
     * @param blockIndex the block to reveal
     * @param fraction   0 = the block's top edge, 1 = its bottom edge
     */
    private void scrollToBlockOffset(int blockIndex, double fraction) {
        if (blockIndex < 0 || blockIndex >= blocks.size()) {
            return;
        }
        ensureBuilt(blockIndex);
        stack.applyCss();
        stack.layout();
        Node node = blocks.get(blockIndex).stackNode();
        double y = node.getBoundsInParent().getMinY()
                + fraction * node.getBoundsInParent().getHeight();
        double contentHeight = stack.getBoundsInLocal().getHeight();
        double viewportHeight = scroll.getViewportBounds().getHeight();
        double range = contentHeight - viewportHeight;
        scroll.setVvalue(range <= 0 ? 0 : Math.clamp(y / range, 0, 1));
    }
}
