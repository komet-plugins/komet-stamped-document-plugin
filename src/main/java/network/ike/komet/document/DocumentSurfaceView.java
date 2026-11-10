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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.Cursor;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.SideDecorator;
import jfx.incubator.scene.control.richtext.model.RichTextModel;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import network.ike.komet.document.surface.DocumentBlock;
import network.ike.komet.document.surface.DocumentSurface;
import network.ike.komet.document.KonceptIdenticon;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.GitDocumentMaterializer.StoredReference;
import network.ike.komet.document.LineRunModel.LineRun;
import network.ike.komet.document.surface.MarkdownRichText;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The two views of a stamped document and the Provenance layer over them, on the plugin's
 * Document surface (ike-lab-documents topics {@code dev-stamped-document-surface} and
 * {@code dev-interaction-surface-family}; IKE-Network/ike-issues#1307).
 *
 * <p>The view does not draw its own body: it builds {@link DocumentBlock}s for a
 * {@link DocumentSurface} the card hosts through its {@code DocumentSurfaceArea}, so the
 * surface's one scroll axis, its header and footer regions, its margins, its expansion to the
 * paged preview and its print settings are the stamped document's too. The <em>Reading
 * view</em> is one block per document block — headings, paragraphs, lists, tables, listings,
 * admonitions — each a view-only {@link RichTextArea} over a {@link LineRunModel} with one
 * styled segment per source line; the left margin carries the introducing date, the right
 * margin the identicons and marks (marginalia that share the block's row and scroll with it);
 * the header carries the document's title and the section at the top of the viewport. The
 * <em>Source view</em> is the ledger, one paragraph per line in areas of
 * {@link #LEDGER_CHUNK} lines with the number, identicon and mark as a side decorator, stacked
 * on the same surface with the margins off.
 *
 * <p>What is shown is a {@link DocumentAssembly}: the host document and, in place of each of
 * its include lines that names a document the store holds, that child's blocks — each child
 * its own document with its own lines, STAMPs, annotations and commit summaries, stacked in
 * include order on the one surface. In the Source view the host's ledger lists each include
 * line followed by the child's own ledger, numbered as the child's file is. An include the
 * store cannot answer stays a line, with a note beneath it.
 *
 * <p>Hovering text resolves the position to its run and shows the line's provenance; hovering
 * an identicon in the margin highlights every run that commit introduced. A click selects a
 * line, a drag or shift-click a contiguous range, snapped to whole lines and allowed to cross
 * blocks; the right-click menu offers the acts on the selection, which the {@link Host}
 * performs. A block is edited in place: the same area flips to an editable model, one paragraph
 * per source line, and Save hands the text to the host.
 */
public final class DocumentSurfaceView {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DocumentSurfaceView.class);

    /** What the surface asks of its card. */
    public interface Host {
        ViewCalculator view();

        /** @return the card's interactive view, or {@code null} when it has none */
        default dev.ikm.komet.framework.view.ViewProperties viewProperties() {
            return null;
        }

        CommitSummaries summaries();

        void status(String text);

        /** Prepares an annotation of the current selection and asks the person to confirm it. */
        void annotate(AnnotationKind kind);

        /** Saves an in-place edit of a block as the person's pending edit. */
        void saveEdit(StoredBlock block, String text);

        /** Inserts a new block after the block the selection starts in, as the person's pending edit, and opens it for editing when the entry says so. */
        default void insertAfterSelection(DocumentInserts.Insertion insertion) { }

        /** Print to PDF, as the card's menu offers it. */
        default void printPdf() { }

        /** Asks to confirm, then deletes the block the selection starts in, as the person's pending edit. */
        default void deleteSelectedBlock() { }

        /** Shows the history of the selected lines, pinned until closed. */
        void showHistory();

        /** The selection changed by a gesture. */
        void selectionChanged();

        /** The view asks its card to switch views — Edit block from the pages returns to the Reading view. */
        default void viewRequested(View view) { }

        /** The view asks its card to show the page a Page-layout block begins on. */
        default void revealPage(int page, double y) { }

        /** The member of the assembly whose block is at the top of the viewport. */
        void showingMember(DocumentAssembly.Member member);

        /** Whether an annotation's commit is signed: a standing choice of the card's. */
        default boolean signsAnnotations() {
            return false;
        }

        /** The person changed whether annotations are signed. */
        default void setSignsAnnotations(boolean sign) { }

        /** The surface switched a layer on itself, as it does the comments' when one is written: the card's menu follows. */
        default void layerShown(Layer layer) { }

        /** What the reader has folded in the shown document, as the view's keys, to be given back by {@link #rememberedFolds}. */
        default void rememberFolds(List<String> keys) { }

        /** The folds remembered for the shown document; none by default. */
        default List<String> rememberedFolds() {
            return List.of();
        }
    }

    /**
     * What the Provenance layer draws over the document, each switched on its own: the dates in
     * the left margin, the stamps in the right, the comments in force as notes in the right
     * margin, the commit tints over the text, the review queue, and the annotation marks. The
     * comments are a layer apart from the stamps: either stands in the right margin without the
     * other, and the margin goes only when no layer that is on has anything to draw there.
     */
    enum Layer { DATES, STAMPS, COMMENTS, TINTS, REVIEW_QUEUE, ANNOTATION_MARKS }

    /** The views: three on the surface's areas, and the Graph, a tree the card hosts in their place. */
    enum View { READING, SOURCE, PAGE_LAYOUT, GRAPH }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Color HOVER_HIGHLIGHT = Color.web("#FFE082");
    private static final Font SMALL = Font.font(11);
    /** Lines per ledger area: a content-height {@code RichTextArea} stops growing at the incubator's 10,000 px safeguard. */
    private static final int LEDGER_CHUNK = 200;

    /**
     * The text styles a build draws with: the screen's for the Reading view, a print theme's for
     * the Page layout view, where the body is the bundled Noto Serif at the theme's size and the
     * headings scale as the Typst writer scales them, so the two layouts shape the same glyphs.
     */
    record Styles(StyleAttributeMap prose, StyleAttributeMap mono, StyleAttributeMap monoGrey, StyleAttributeMap smallGrey,
                  StyleAttributeMap title, StyleAttributeMap bold, StyleAttributeMap cell, StyleAttributeMap headerCell, double base) {

        static final Styles SCREEN = new Styles(
                StyleAttributeMap.builder().setFontSize(14).build(),
                StyleAttributeMap.builder().setFontFamily("Menlo").setFontSize(12).build(),
                StyleAttributeMap.builder().setFontFamily("Menlo").setFontSize(12).setTextColor(Color.web("#8a8782")).build(),
                StyleAttributeMap.builder().setFontSize(11).setTextColor(Color.web("#8a8782")).build(),
                StyleAttributeMap.builder().setFontSize(24).setBold(true).build(),
                StyleAttributeMap.builder().setFontSize(14).setBold(true).build(),
                StyleAttributeMap.builder().setFontSize(13).build(),
                StyleAttributeMap.builder().setFontSize(13).setBold(true).build(), 14);

        /** A section heading's style: the screen's by level, or the theme's scale of 1.5, 1.25, 1.1 and 1 as Typst's. */
        StyleAttributeMap heading(int level) {
            if (this == SCREEN) {
                return StyleAttributeMap.builder().setFontSize(Math.max(13, 21 - 2 * level)).setBold(true).build();
            }
            double scale = switch (Math.max(1, level)) {
                case 1 -> 1.5;
                case 2 -> 1.25;
                case 3 -> 1.1;
                default -> 1.0;
            };
            StyleAttributeMap.Builder b = StyleAttributeMap.builder().merge(prose).setFontSize(base * scale).setBold(true)
                    .setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
            if (level >= 4) {
                b.setItalic(true);
            }
            return b.build();
        }

        /** A footnote's style: the body's, at Typst's size for a footnote entry. */
        StyleAttributeMap footnote() {
            return StyleAttributeMap.builder().merge(prose).setFontSize(base * network.ike.komet.document.print.LayoutOracle.FOOTNOTE_SCALE).build();
        }

        /** Prose set ragged right, as a front list's entry is: the Typst writer's entry block turns justification off. */
        StyleAttributeMap ragged() {
            return StyleAttributeMap.builder().merge(prose).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build();
        }

        /** A block's title on the page: bold and ragged, at the scale of the body the Typst writer sets it. */
        StyleAttributeMap blockTitle(double scale) {
            return StyleAttributeMap.builder().merge(bold).setFontSize(base * scale).build();
        }

        /** The print theme's styles: Noto Serif body at the theme size, Noto Sans Mono at the size Typst sets raw text, a 1.8 title. */
        static Styles page(network.ike.komet.document.surface.print.settings.DocumentTheme theme) {
            double base = theme.baseFontSize();
            String family = theme.fontFamily() == null ? null : network.ike.komet.document.print.DocumentFonts.SERIF;
            StyleAttributeMap.Builder body = StyleAttributeMap.builder().setFontSize(base);
            if (family != null) {
                body.setFontFamily(family);
            }
            body.setTextAlignment(javafx.scene.text.TextAlignment.JUSTIFY);   // as Typst justifies; headings and cells override it
            StyleAttributeMap prose = body.build();
            StyleAttributeMap mono = StyleAttributeMap.builder().setFontFamily(network.ike.komet.document.print.DocumentFonts.MONO)
                    .setFontSize(base * network.ike.komet.document.print.TypstWriter.RAW_SCALE).build();
            return new Styles(prose, mono,
                    StyleAttributeMap.builder().merge(mono).setTextColor(Color.web("#8a8782")).build(),
                    StyleAttributeMap.builder().merge(prose).setFontSize(base * 0.8).setTextColor(Color.web("#8a8782")).build(),
                    StyleAttributeMap.builder().merge(prose).setFontSize(base * 1.8).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(), base);
        }
    }

    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\[\\[[^\\]]*\\]\\]$");
    private static final Pattern BLOCK_TITLE_LINE = Pattern.compile("^\\.([^\\s.].*)$");

    /** The styles of the build in progress: the screen's, or a print theme's while the Page layout is built. */
    private Styles st = Styles.SCREEN;
    /** The document's footnote count while the Page layout is built. */
    private network.ike.komet.document.print.InlineText.Numbering printNotes = new network.ike.komet.document.print.InlineText.Numbering();
    /** The Reading view's footnote count: one per shown document, as the pages have theirs. */
    private network.ike.komet.document.print.InlineText.Numbering readingNotes = new network.ike.komet.document.print.InlineText.Numbering();
    /** What the structured blocks of the shown document know of each other; a new one each time a document is shown. */
    private BlockContext blocks = new BlockContext();
    /** Where the build in progress adds its areas: the Reading view's, or the Page layout's. */
    private List<Area> target;
    /** Whether the build in progress is the Page layout: no front matter, comments or attribute lines, print spacing. */
    private boolean print;
    /** Whether the shown document is a reading of a past time, which is read, never edited. */
    private boolean past;
    /** The index of the top-level block of the print model the build is in, as the Typst writer labels them. */
    private int modelOrdinal;
    /** How deep inside containers the print build is: only blocks outside any are top-level blocks of the model. */
    private int nesting;

    private final Host host;
    private final DocumentSurface surface;
    private final Map<Long, Image> badges = new HashMap<>();
    private final Set<Layer> layers = EnumSet.of(Layer.DATES, Layer.STAMPS, Layer.COMMENTS, Layer.ANNOTATION_MARKS);
    private final List<Area> areas = new ArrayList<>();
    private final List<Area> sources = new ArrayList<>();
    /** The Page layout's units: one area per paragraph-like piece of the print model, what the layout oracles place. */
    private final List<Area> units = new ArrayList<>();
    /** The Page layout's lines as the oracle laid them, one per line, in order; each builds its area when its page is first shown. */
    private final List<PageLine> pageLines = new ArrayList<>();
    /** The Page layout's line areas built so far: those of the pages the stack has shown, what the selection and the layers redraw. */
    private final List<Area> pages = new ArrayList<>();
    /** The commit highlighted in every area, 0 for none; a page line built later takes it. */
    private long highlightedCommit;
    private final Map<Node, Area> areaByNode = new HashMap<>();
    private final Map<String, double[]> metrics = new HashMap<>();
    private final Map<Long, Area> areasByBlock = new HashMap<>();
    private final Popup popover = new Popup();
    private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(350));
    /**
     * The one menu of every view (ike-lab-documents topic {@code dev-document-edit-menu}): built
     * once, shown from every line area, its verbs enabled by what is selected and whether an edit
     * is open. Annotate, Block, Insert, View and Record, a separator between the families.
     */
    private final ContextMenu menu = new ContextMenu();
    /** The numbered captions of the shown assembly's titled figures and tables. */
    private Captions captions = Captions.none();
    /** What the reader has folded away in the Reading view, by the index of the areas. */
    private final Folding folding = new Folding();
    /** The document the folds belong to. */
    private dev.ikm.tinkar.common.id.PublicId foldedDocument;
    private final MenuItem foldItem = new MenuItem("Fold");
    private final CheckMenuItem signItem = new CheckMenuItem("Sign annotations");
    private final MenuItem editItem = new MenuItem("Edit block");
    private final MenuItem deleteItem = new MenuItem("Delete block…");
    private final Menu insertMenu = new Menu("Insert");
    private final Menu formatMenu = new Menu("Format");
    /** The menu of a block in edit: the inline marks, each with its key. */
    private final ContextMenu editingMenu = new ContextMenu();
    private final Map<View, CheckMenuItem> viewItems = new LinkedHashMap<>();
    private final Label headerTitle = new Label();
    private final Label headerSection = new Label();
    private final VBox header = new VBox(1, headerTitle, headerSection);

    private View view = View.READING;
    private DocumentAssembly assembly;
    private LineSelection selection;
    private boolean applying;
    private LineRun hovered;
    private Area editing;
    private double[] hoverAt;
    private RichTextArea hoverArea;

    public DocumentSurfaceView(Host host, DocumentSurface surface) {
        this.host = host;
        this.surface = surface;
        this.target = areas;
        headerTitle.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
        headerTitle.setId("stampedDocumentHeaderTitle");
        headerSection.setFont(SMALL);
        headerSection.setStyle("-fx-text-fill: #5f6b7a;");
        headerSection.setId("stampedDocumentHeaderSection");
        header.setPadding(new Insets(4, 12, 4, 12));
        header.setId("stampedDocumentHeader");
        surface.setHeader(header);
        surface.scrollPositionProperty().addListener((obs, was, is) -> {
            hidePopover();
            // Content-height areas settle their heights a pulse or two after a scroll that follows a
            // model change; the section at the top is read once they have.
            javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(this::updateRunningHead));
        });
        popover.setAutoHide(false);
        popover.setAutoFix(true);
        hoverDelay.setOnFinished(e -> showPopover());
        buildMenu();
    }

    /** @return the shown view */
    View view() {
        return view;
    }

    /** Switches the view; the selection carries over. */
    void setView(View wanted) {
        if (wanted == view) {
            return;
        }
        if (editing != null) {
            cancelEdit(editing);
        }
        view = wanted;
        hidePopover();
        stack();
    }

    /** @return whether the layer's toggle is on */
    boolean layer(Layer layer) {
        return layers.contains(layer);
    }

    /** Turns a layer toggle on or off and redraws the layer. */
    void setLayer(Layer layer, boolean on) {
        if (on ? layers.add(layer) : layers.remove(layer)) {
            redecorate();
        }
    }

    /** @return the current selection, if any */
    Optional<LineSelection> selection() {
        return Optional.ofNullable(selection);
    }

    /** @return the selected lines in document order */
    List<StoredLine> selectedLines() {
        if (selection == null || assembly == null) {
            return List.of();
        }
        List<StoredLine> lines = assembly.lines();
        return lines.subList(Math.max(0, selection.min()), Math.min(lines.size(), selection.max() + 1));
    }

    /** @return the shown assembly, if one is */
    Optional<DocumentAssembly> assembly() {
        return Optional.ofNullable(assembly);
    }

    /** Sets the selection (by ordinals) and shows it in the current view. */
    void select(LineSelection wanted) {
        selection = wanted;
        render();
        reveal();
        host.selectionChanged();
    }

    /** Clears the selection. */
    void clearSelection() {
        selection = null;
        render();
        host.selectionChanged();
    }

    /**
     * Marks the next {@link #show} as a reading of a past time (ike-lab-documents topic
     * {@code dev-decision-document}, "Time travel"): no block is edited and nothing is written
     * back, since an edit is made to the document as it is now.
     *
     * @param past whether the document is read as of a past time
     */
    public void setPast(boolean past) {
        this.past = past;
    }

    /** @return whether a block is being edited in place */
    boolean editing() {
        return editing != null;
    }

    /** @return the ordinal of a line by nid in surface order, or {@code -1} */
    int ordinal(long nid) {
        return assembly == null ? -1 : assembly.ordinal(nid);
    }

    // ── building ──────────────────────────────────────────────────────────────

    /**
     * One area of a view: a block of the Reading view, a chunk of the Source view's ledger, a
     * line of the Page layout — or a unit of the Page layout, which is bare: the oracles place a
     * unit by its text and style alone, so a unit has no control of its own and no model, only
     * what the lines built from it need.
     */
    private final class Area {
        final DocumentAssembly.Member member;
        final StoredBlock block;
        final List<StoredLine> lines;
        final LineRunModel.Spec spec;
        /** The control that draws the area; {@code null} for a bare unit. */
        final RichTextArea area;
        /** The area's column in the surface, the control and what stands with it; {@code null} for a bare unit. */
        final VBox holder;
        final boolean editable;
        final String section;
        /** Whether the area's row is laid out: false while a fold hides it. */
        final javafx.beans.property.BooleanProperty shown = new javafx.beans.property.SimpleBooleanProperty(true);
        /** Whether the area anchors a fold: a heading whose section is folded, or the first block of a folded run. */
        final javafx.beans.property.BooleanProperty anchored = new javafx.beans.property.SimpleBooleanProperty(false);
        /** A heading's fold mark in the left margin; null for any other area. */
        Label foldMark;
        /** The line that says what is folded, while the area anchors a fold. */
        HBox foldLine;
        /** What a run's first block hid to make way for its fold line, each node with the visible and managed it had, to be given back. */
        final Map<Node, boolean[]> hiddenByFold = new java.util.LinkedHashMap<>();
        LineRunModel model;
        HBox marginalia;
        /** The right margin's notes: the comments in force on the area's lines, and a note being written. */
        final VBox notes = new VBox(6);
        Label date;
        DocumentBlock documentBlock;
        long highlightedStamp;
        /** A ledger chunk's line numbers in its member's file, 1-based, parallel to {@link #lines}. */
        int[] numbers;
        /** In the Page layout: what this area is drawn as, when it is drawn rather than set. */
        network.ike.komet.document.DecisionFigure drawn;
        /** In the Page layout: the node a drawn area's figure stands in. */
        javafx.scene.layout.Region drawing;
        /** In the Page layout: how tall a drawn area stands on its page, its baseline being its foot. */
        double drawnHeight;
        /** In the Page layout: whether this unit, or line of one, is a listing's, which draws on the listing's one tint. */
        boolean listing;
        /** In the Page layout: the top-level block of the print model this area belongs to, or -1. */
        int modelOrdinal = -1;
        /** In the Page layout: where the text marks footnotes, by offset in its plain text. */
        List<network.ike.komet.document.print.InlineText.Mark> marks = List.of();
        /** In the Page layout: the footnote this area is, or 0. */
        int note;
        /** In the Page layout: whether the area is prose that may split across pages. */
        boolean splittable;
        /** In the Page layout: whether the area is a heading that keeps with what follows. */
        boolean heading;
        /** In the Page layout: whether the area is a block's title, a unit of its own above the block. */
        boolean caption;
        /** In the Page layout: whether the area is a front list's title or entry, generated from the header's request. */
        boolean frontList;
        /** In the Page layout: the key of the block a front list's entry names ({@link network.ike.komet.document.print.StoredDocumentModel#key}); empty for any other area. */
        String entryTarget = "";
        /** In the Page layout: how far a front list's entry is indented, in ems of the body. */
        int entryDepth;
        /** A page line of a front list's entry, its unit's last: the leader and page drawn after its text. */
        Region entryTail;
        /** The width of the page number {@link #entryTail} draws, in the entry's font. */
        double entryNumberWidth;
        /** A page line: the unit it is a line of, which line of it, the page, its left edge and baseline there. */
        int unit = -1;
        int lineOfUnit;
        /** A table row's table's relative column widths, for the layout oracles. */
        List<Integer> columns = List.of();
        int page = -1;
        double x;
        double baseline;
        /** A page line: whether it is the whole unit, wrapped by JavaFX, rather than one of the oracle's lines. */
        boolean whole;
        /** A structured block's kind, its source, and the control that reads it in place of the text; null otherwise. */
        StructuredBlockKind structured;
        StructuredBlockKind.Source structuredSource;
        Node structuredView;
        /** The kind's editor while one is open on this area. */
        StructuredBlockKind.Editor structuredEditor;
        /** A table row's cells in the Reading view, each a text of its own in the row's grid; none for any other area. */
        List<CellText> cells = List.of();
        /** A table row's cells as specs, one per column, an empty cell's {@code null}; none for any other area. */
        List<LineRunModel.Spec> cellSpecs = List.of();
        /** A table row: whether it is its table's first, which draws the table's top edge on a page. */
        boolean tableFirst;

        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section) {
            this(member, block, lines, spec, editable, section, false);
        }

        /**
         * @param bare whether the area is a unit of the Page layout, with no control and no model: it is never shown,
         *             only placed by the oracles and read by the lines built from it
         */
        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section,
             boolean bare) {
            this.member = member;
            this.block = block;
            this.lines = lines;
            this.spec = spec;
            this.editable = editable;
            this.section = section;
            if (bare) {
                area = null;
                holder = null;
                return;
            }
            area = new RichTextArea();
            holder = new VBox();
            area.setEditable(false);
            area.setWrapText(true);
            area.setUseContentHeight(true);
            area.setDisplayCaret(false);
            area.setHighlightCurrentParagraph(false);
            area.setContentPadding(new Insets(2, 4, 2, 4));
            area.getStyleClass().add("doc-prose-block");
            holder.getProperties().put(MarkdownRichText.BLOCK_SHOWN, shown);
            wire(this, area, () -> model);
            // A selection held off a window fails every layout of the control, so an area keeps one only while attached.
            area.sceneProperty().addListener((obs, was, is) -> {
                if (is == null) {
                    applySelection(this);
                } else {
                    javafx.application.Platform.runLater(() -> applySelection(this));
                }
            });
            decorate();
        }

        void decorate() {
            if (area == null) {
                return;                                          // a bare unit draws nothing
            }
            model = spec.build(decoration(this));
            applying = true;
            try {
                area.setModel(model);
            } finally {
                applying = false;
            }
            for (CellText cell : cells) {
                cell.decorate();
            }
        }
    }

    /**
     * One line of the Page layout as the oracle laid it: which unit, which line of it, the text
     * it draws, and where it stands. A line's area — the control that draws it, hovers, selects —
     * is built from this when its page first comes near the viewport, so a long document carries
     * the controls of the pages it has shown rather than one per line of the book (the regulatory
     * book, 2026-11-09: 6,300 fewer rich text areas), and re-laying it to Typst's lines once they
     * arrive is a pass over positions, not a rebuild of every control.
     */
    private final class PageLine {
        final Area unitArea;
        final LineRunModel.Spec spec;
        /** The line's ordinal among every laid line. */
        final int index;
        final int unit;
        final int lineOfUnit;
        final int page;
        final double x;
        final double baseline;
        /** Whether the line is the whole unit, wrapped by JavaFX, rather than one of the oracle's lines. */
        final boolean whole;
        /** Whether the line is a listing's, which draws on the listing's one tint. */
        final boolean listing;
        /** Whether the line is its unit's last: a front list's entry draws its leader and page after it. */
        boolean lastOfUnit;
        /**
         * A front list entry's last line: where the Typst oracle read its leader and page ending
         * ({@link network.ike.komet.document.print.LayoutOracle.Line#tail}), or {@code null} when the
         * layout is the greedy estimate, which the pages place by the writer's rule instead.
         */
        network.ike.komet.document.print.LayoutOracle.Line tail;
        /** A figure's height on its page at the scale it is shown, known once a page asked for its lines. */
        double drawnHeight;
        /** The line's area, once its page was shown. */
        Area area;

        PageLine(Area unitArea, LineRunModel.Spec spec, int unit, int lineOfUnit, network.ike.komet.document.print.LayoutOracle.Line line,
                 boolean whole, boolean listing) {
            this.unitArea = unitArea;
            this.spec = spec;
            this.index = pageLines.size();
            this.unit = unit;
            this.lineOfUnit = lineOfUnit;
            this.page = line.page();
            this.x = line.x();
            this.baseline = line.baseline();
            this.whole = whole;
            this.listing = listing;
        }

        /** @return the line's style, its first paragraph's */
        StyleAttributeMap style() {
            return spec.paragraphs().getFirst().style();
        }

        /** @return the footnote the line is part of, or 0 */
        int note() {
            return unitArea.note;
        }
    }

    /**
     * Shows an assembly: rebuilds both views from its members' lines and blocks and stacks the
     * current one on the surface. The selection is kept when the same lines are still there.
     *
     * @param shown the host document with its children resolved
     */
    public void show(DocumentAssembly shown) {
        hidePopover();
        editing = null;
        assembly = shown;
        // the structured blocks of this document read each other here: trees folded in, statements named, values computed
        blocks = new BlockContext();
        printed.clear();
        // the same document's folds are carried across the rebuild by what the blocks say; another document's are what the card remembers of it
        boolean sameDocument = foldedDocument != null && foldedDocument.equals(shown.host().document().anchor());
        List<String> foldKeys = sameDocument ? folding.keys(this::foldKey) : host.rememberedFolds();
        foldedDocument = shown.host().document().anchor();
        areas.clear();
        pages.clear();
        pageLines.clear();
        units.clear();
        areaByNode.clear();
        areasByBlock.clear();
        badges.clear();
        if (selection != null && (selection.max() >= shown.lines().size())) {
            selection = null;
        }
        StoredDocument document = shown.host().document();
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        network.ike.komet.document.print.DocumentModel.Lists matter = network.ike.komet.document.print.StoredDocumentModel.lists(document);
        captions = Captions.of(shown, DocumentSurfaceView::isFigure, matter.sectionNumbers() ? matter.sectionLevels() : 0);
        noteHomes = null;
        composing = null;
        readingNotes = new network.ike.komet.document.print.InlineText.Numbering();
        renderMember(shown.host(), 0, "");
        if (title.isEmpty() && document.root() != null) {
            title = document.root().title();
        }
        headerTitle.setText(title.isEmpty() ? StampNames.name(host.view(), dev.ikm.tinkar.common.service.PrimitiveData.nid(document.anchor())) : title);
        headerSection.setText("");
        buildSource(shown);
        folding.clear();
        folding.restore(foldKeys, key -> areaIndexOf(key), this::levelAt, areas.size());
        stack();
    }

    /** Renders one member's blocks, or its lines as a ledger when the store holds no structure for it. */
    private void renderMember(DocumentAssembly.Member member, int depth, String section) {
        StoredDocument document = member.document();
        if (document.root() == null) {
            for (StoredLine line : document.lines()) {
                add(member, null, List.of(line), paragraphs(List.of(line), "", st.mono(), null), depth, false, section);
            }
        } else {
            render(member, document.root(), depth, 0, section);
        }
    }

    /** Stacks the current view's blocks on the surface and draws the selection. */
    private void stack() {
        if (view == View.PAGE_LAYOUT || view == View.GRAPH) {
            return;                                              // the card shows the pages or the graph in the surface's place
        }
        if (view == View.READING) {
            // the left margin is the dates'; the right stands while any layer that is on has something to draw in it
            rightMargin = rightMarginInUse();
            surface.setMarginTracks(layers.contains(Layer.DATES) ? 2 : 0, rightMargin ? 2 : 0);
            surface.setBlocks(areas.stream().map(a -> a.documentBlock).toList());
            applyFolds();
        } else {
            surface.setMarginTracks(0, 0);
            surface.setBlocks(sources.stream().map(a -> a.documentBlock).toList());
        }
        render();
        // The section at the top is read from laid-out bounds: two pulses after the stack joins layout.
        javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(this::updateRunningHead));
    }

    /** The header's section line: the section of the block at the top of the viewport; the host learns whose block it is in either view. */
    private void updateRunningHead() {
        List<Area> shown = view == View.PAGE_LAYOUT ? areas : shownAreas();
        if (shown.isEmpty()) {
            headerSection.setText(view == View.SOURCE ? "Source" : "");
            return;
        }
        int top = surface.topBlockIndex();
        if (top < 0 || top >= shown.size()) {
            return;
        }
        headerSection.setText(view == View.SOURCE ? "Source" : shown.get(top).section);
        host.showingMember(shown.get(top).member);
    }

    /**
     * The ledger: one paragraph per line, in areas of at most {@link #LEDGER_CHUNK} lines so no
     * area outgrows the safeguard. The lines run in surface order, so the host's ledger lists
     * each resolved include line followed by the child's own ledger; the areas break where the
     * member changes, each member's lines numbered as its file is, with the path above a
     * child's ledger and again where the host's resumes.
     */
    private void buildSource(DocumentAssembly shown) {
        sources.clear();
        List<StoredLine> lines = shown.lines();
        int chunkIndex = 0;
        int at = 0;
        DocumentAssembly.Member previous = null;
        while (at < lines.size()) {
            DocumentAssembly.Member member = shown.memberAt(at).orElseThrow();
            int end = at;
            while (end < lines.size() && end - at < LEDGER_CHUNK && shown.memberAt(end).orElseThrow() == member) {
                end++;
            }
            List<StoredLine> chunk = lines.subList(at, end);
            int[] numbers = new int[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                numbers[i] = shown.indexInMember(at + i) + 1;
            }
            List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
            for (StoredLine line : chunk) {
                paragraphs.add(new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line, line.text())), "", Styles.SCREEN.mono()));
            }
            Area source = new Area(member, null, chunk, new LineRunModel.Spec(paragraphs), false, "Source");
            source.numbers = numbers;
            source.area.setId(chunkIndex == 0 ? "stampedDocumentSource" : "stampedDocumentSource" + chunkIndex);
            source.area.setWrapText(false);
            source.area.setLeftDecorator(new LedgerDecorator(source));
            if (previous != null && previous != member) {
                Label path = new Label((member.host() || member.depth() < previous.depth() ? "↩ " : "↳ ") + member.path());
                path.setFont(Font.font("Menlo", 11));
                path.setStyle("-fx-text-fill: #5f6b7a;");
                path.setPadding(new Insets(6, 0, 2, 8));
                path.getStyleClass().add("stamped-document-member-path");
                source.holder.getChildren().add(path);
            }
            source.holder.getChildren().add(source.area);
            source.documentBlock = DocumentBlock.prose(source.holder, source.area, source.model,
                    chunk.stream().map(StoredLine::text).collect(Collectors.joining("\n")));
            sources.add(source);
            previous = member;
            chunkIndex++;
            at = end;
        }
    }

    /**
     * A ledger identicon's size: under the height of a 12 px Menlo line, so one line's identicon
     * stands clear of the next one's rather than running into it as a solid stripe.
     */
    private static final double LEDGER_IDENTICON = 12;

    /** The ledger's left column: the line's number in its file, introducing identicon, annotation mark. */
    private final class LedgerDecorator implements SideDecorator {
        private final Area chunk;

        LedgerDecorator(Area chunk) {
            this.chunk = chunk;
        }

        @Override
        public double getPrefWidth(double viewWidth) {
            return 96;
        }

        @Override
        public Node getMeasurementNode(int index) {
            return getNode(index);
        }

        @Override
        public Node getNode(int paragraph) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 4, 0, 2));
            boolean held = paragraph >= 0 && paragraph < chunk.lines.size();
            Label number = new Label(held ? Integer.toString(chunk.numbers[paragraph]) : "");
            number.setMinWidth(34);
            number.setAlignment(Pos.CENTER_RIGHT);
            number.setFont(Font.font("Menlo", 11));
            number.setStyle("-fx-text-fill: #8a8782;");
            row.getChildren().add(number);
            StoredLine line = held ? chunk.lines.get(paragraph) : null;
            if (line != null && line.introduced() != null && line.introduced().time() != Long.MAX_VALUE) {
                row.getChildren().add(identicon(line.introduced(), LEDGER_IDENTICON, null));
            } else {
                Label gap = new Label();
                gap.setMinWidth(LEDGER_IDENTICON);
                row.getChildren().add(gap);
            }
            Label mark = new Label(line == null || !layers.contains(Layer.ANNOTATION_MARKS) ? " " : mark(line));
            mark.setMinWidth(14);
            mark.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 11));
            mark.setStyle(line == null ? "" : markStyle(line));
            row.getChildren().add(mark);
            return row;
        }
    }

    /**
     * The line's annotation mark: refuted, affirmed, commented, or none. A retracted annotation
     * leaves no mark: the line reads as it did before the annotation, back in the review queue if
     * nothing else affirms it; the popover still names what was withdrawn.
     */
    public static String mark(StoredLine line) {
        if (!line.refuters().isEmpty()) {
            return "✗";
        }
        if (!line.affirmers().isEmpty()) {
            return "✓";
        }
        return line.inForce().isEmpty() ? " " : "…";
    }

    static String markStyle(StoredLine line) {
        if (!line.refuters().isEmpty()) {
            return "-fx-text-fill: #c62828;";
        }
        if (!line.affirmers().isEmpty()) {
            return "-fx-text-fill: #2e7d32;";
        }
        return "-fx-text-fill: #8a8782;";
    }

    /** Renders one block of a member and its members into the Reading view's areas. */
    private void render(DocumentAssembly.Member member, StoredBlock block, int depth, int ordinal, String section) {
        StoredDocument document = member.document();
        List<StoredLine> own = new ArrayList<>();
        List<StoredBlock> children = new ArrayList<>();
        for (long nid : block.members()) {
            Optional<StoredLine> line = document.line(nid);
            if (line.isPresent()) {
                own.add(line.get());
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    children.add(child);
                }
            }
        }
        if (print) {
            // a block with a print form is drawn, one area for each figure: the page has it from the model's Drawn blocks
            List<network.ike.komet.document.DecisionFigure> drawn = printFigures(document, block);
            if (!drawn.isEmpty()) {
                List<StoredLine> all = StructuredBlocks.lines(document, block);
                for (network.ike.komet.document.DecisionFigure figure : drawn) {
                    if (nesting == 0) {
                        modelOrdinal++;
                    }
                    Area area = addArea(member, block, all, paragraphs(all, "", st.mono(), null), depth, false, section);
                    area.drawn = figure;
                }
                return;
            }
        }
        if (block.kind() == Block.Kind.OPEN && StructuredBlocks.kindOf(block).isPresent()) {
            // a structured open block, [anf] over --, reads as a listing does: its source, then its kind's control;
            // on the page its source alone, as one block
            if (print && nesting == 0) {
                modelOrdinal++;
            }
            if (print) {
                caption(member, block, network.ike.komet.document.print.TypstWriter.TITLE_SCALE, depth, section);
            }
            List<StoredLine> all = print ? StructuredBlocks.content(document, block) : StructuredBlocks.lines(document, block);
            Area area = add(member, block, all, paragraphs(all, "", st.mono(), null), depth, !print, section);
            if (area != null) {
                tintListing(area);
                if (!print) {
                    title(area, block);
                    attachStructured(area, document, block);
                }
            }
            return;
        }
        switch (block.kind()) {
            case DOCUMENT -> {
                // A child's title is the section its own blocks sit under until its first section.
                String under = member.host() || block.title().isEmpty() ? section : block.title();
                List<StoredLine> heading = own.stream().filter(l -> l.text().startsWith("= ")).toList();
                if (!heading.isEmpty()) {
                    if (print) {
                        // The print model: the host's title is the document title, a child's a heading at its include offset.
                        modelOrdinal++;
                        StyleAttributeMap style = member.host() ? st.title() : st.heading(Math.max(1, offsetOf(member)));
                        Area title = addProse(member, block, heading, paragraphs(heading, " ", style,
                                t -> captions.heading(block, t.startsWith("= ") ? t.substring(2) : t)), depth, false, under);
                        if (title != null) {
                            title.heading = true;
                            title.splittable = false;
                        }
                    } else {
                        addProse(member, block, heading, paragraphs(heading, " ", st.title(),
                                t -> captions.heading(block, t.startsWith("= ") ? t.substring(2) : t)), depth, false, under);
                    }
                }
                if (print && member.host()) {
                    // the lists the header asks for, after the title and before the body, as the print model has them
                    frontLists(member, document);
                }
                List<StoredLine> front = own.stream().filter(l -> !l.text().startsWith("= "))
                        .filter(l -> !print || assembly.includeOf(l.nid()).map(DocumentAssembly.Include::resolved).orElse(false)).toList();
                Area frontArea = null;
                if (!front.isEmpty()) {
                    frontArea = add(member, block, front, paragraphs(front, "", st.monoGrey(), null), depth, false, under);
                }
                if (!print && member.host()) {
                    // the lists the header asks for, beneath the header's own lines, before the body
                    Area beneath = frontArea != null ? frontArea : target.isEmpty() ? null : target.getLast();
                    if (beneath != null) {
                        generatedLists(beneath, document);
                    }
                }
                renderChildren(member, children, depth, under);
            }
            case SECTION -> {
                List<StoredLine> heading = own.stream().filter(l -> l.text().startsWith("=")).toList();
                // The print model offsets an included document's headings by its include's leveloffset.
                StyleAttributeMap style = st.heading(print ? block.level() + offsetOf(member) : block.level());
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                addProse(member, block, heading.isEmpty() ? own : heading, paragraphs(heading.isEmpty() ? own : heading, " ", style,
                        t -> captions.heading(block, t.replaceFirst("^=+\\s*", ""))), depth, false, block.title());
                renderChildren(member, children, depth, block.title());
            }
            case PARAGRAPH -> {
                if (print && printable(own).stream().map(l -> printProse(l.text())).allMatch(String::isBlank)) {
                    return;                                      // the print model drops a paragraph of index terms alone
                }
                if (print && nesting == 0 && !printable(own).isEmpty()) {
                    modelOrdinal++;
                }
                LineRunModel.Spec spec = paragraphs(own, " ", st.prose(), print ? DocumentSurfaceView::printProse : DocumentSurfaceView::readingProse);
                addProse(member, block, own, print ? spec : withoutEmptyLines(spec), depth, true, section);
            }
            case ADMONITION -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                String label = block.parameters().replaceAll("[\\[\\]]", "");
                List<LineRunModel.Line> lines = new ArrayList<>();
                for (int i = 0; i < own.size(); i++) {
                    String text = prose(own.get(i).text());
                    if (i == 0) {
                        text = label + " — " + text.replaceFirst("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s*", "");
                    }
                    lines.add(new LineRunModel.Line(own.get(i), text));
                }
                Area area = addProse(member, block, own, new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(lines, " ", st.prose()))), depth, true, section);
                if (area != null && area.area != null) {
                    area.area.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4;");
                }
                nesting++;
                renderChildren(member, children, depth + 1, section);
                nesting--;
            }
            case LISTING, LITERAL, DIAGRAM, OPAQUE -> {
                if (print && nesting == 0 && block.kind() != Block.Kind.OPAQUE) {
                    modelOrdinal++;
                }
                if (print && block.kind() != Block.Kind.OPAQUE) {
                    caption(member, block, network.ike.komet.document.print.TypstWriter.TITLE_SCALE, depth, section);
                }
                // On the page a verbatim block is its source, as the print model has it: no delimiter lines.
                List<StoredLine> shown = print && block.kind() != Block.Kind.OPAQUE ? network.ike.komet.document.print.StoredDocumentModel.verbatimLines(own) : own;
                Area area = add(member, block, shown, paragraphs(shown, "", block.kind() == Block.Kind.OPAQUE ? st.monoGrey() : st.mono(), null), depth,
                        block.kind() == Block.Kind.LISTING || block.kind() == Block.Kind.LITERAL, section);
                if (area != null) {
                    if (block.kind() != Block.Kind.OPAQUE) {
                        tintListing(area);
                    }
                    if (!print) {
                        title(area, block);
                        attachStructured(area, member.document(), block);
                    }
                }
            }
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                if (!own.isEmpty() && !print) {
                    add(member, block, own, paragraphs(own, "", st.smallGrey(), null), depth, false, section);
                } else if (print) {
                    caption(member, block, network.ike.komet.document.print.TypstWriter.BOX_TITLE_SCALE, depth, section);
                }
                nesting++;
                renderChildren(member, children, depth + 1, section);
                nesting--;
            }
            case LIST -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                if (print) {
                    caption(member, block, network.ike.komet.document.print.TypstWriter.TITLE_SCALE, depth, section);
                } else if (!own.isEmpty()) {
                    addProse(member, block, own, paragraphs(own, " ", st.bold(), t -> t.replaceFirst("^\\.", "")), depth, false, section);
                }
                nesting++;
                int n = 0;
                for (StoredBlock item : children) {
                    render(member, item, depth + 1, ++n, section);
                }
                nesting--;
            }
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> {
                String marker = block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY ? "▪" : ordinalMarker(own, ordinal, print ? depth : -1);
                List<LineRunModel.Line> lines = new ArrayList<>();
                for (int i = 0; i < own.size(); i++) {
                    String text = MARKER.matcher(own.get(i).text()).replaceFirst("").strip();
                    if (text.equals("+")) {
                        text = "";
                    }
                    lines.add(new LineRunModel.Line(own.get(i), i == 0 ? marker + " " + text : text));
                }
                addProse(member, block, own, new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(lines, " ", st.prose()))), depth, true, section);
                renderChildren(member, children, depth + 1, section);
            }
            case TABLE -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                boolean header = block.parameters().contains("header");
                if (!own.isEmpty() && !print) {
                    Area delimiters = add(member, block, own, paragraphs(own, "", st.smallGrey(), null), depth, false, section);
                    if (delimiters != null) {
                        title(delimiters, block);
                    }
                } else if (print && !block.title().isEmpty()) {
                    // The caption, which Typst sets above the table: carried by the block's first line, or the first cell's.
                    List<StoredLine> carrier = !own.isEmpty() ? own.subList(0, 1) : children.stream()
                            .flatMap(row -> row.members().stream()).map(document::line).flatMap(Optional::stream).limit(1).toList();
                    if (!carrier.isEmpty()) {
                        // Straight to an area: the carrier may be the title line itself, which the print filter would drop.
                        addArea(member, block, carrier, paragraphs(carrier, "", st.bold(), t -> captions.text(block)), depth, false, section);
                    }
                }
                if (print) {
                    printedRows(member, block, children, header, depth + 1, section);
                } else {
                    gridRows(member, block, children, header, depth + 1, section);
                }
            }
            case TABLE_ROW, TABLE_CELL -> renderChildren(member, children, depth, section);
            case IMAGE -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                Area area = add(member, block, own, paragraphs(own, " ", st.prose(), t -> "🖼 " + t), depth, false, section);
                if (area != null && !print) {
                    title(area, block);
                }
            }
        }
    }

    /** Marks an area a listing's: the Reading view tints its control, the Page layout tints the block behind its lines. */
    private static void tintListing(Area area) {
        area.listing = true;
        if (area.area != null) {
            area.area.setStyle("-fx-background-color: #F4F3F1; -fx-background-radius: 4;");
        }
    }

    /** The figures of each block's source: the model and the page must draw the same ones, and a sweep is not free. */
    private final Map<String, List<network.ike.komet.document.DecisionFigure>> printed = new HashMap<>();

    /**
     * The lists the document's header asks for, drawn beneath the header block that asks: the
     * contents, the figures and the tables, each entry a link that scrolls to its block and opens
     * any fold that hides it. The page prints the same lists with page numbers.
     */
    private void generatedLists(Area header, StoredDocument document) {
        network.ike.komet.document.print.DocumentModel.Lists lists = network.ike.komet.document.print.StoredDocumentModel.lists(document);
        if (!lists.any()) {
            return;
        }
        VBox box = new VBox(2);
        box.getStyleClass().add("stamped-document-lists");
        box.setPadding(new Insets(6, 0, 2, 0));
        if (lists.contents()) {
            List<Captions.Heading> headings = captions.headings().stream().filter(h -> h.level() <= lists.depth()).toList();
            generatedList(box, "Contents", headings.stream().map(h -> new Object[] {h.read(), h.block(), h.level() - 1}).toList());
        }
        if (lists.figures()) {
            generatedList(box, "Figures", captions.of(Captions.Kind.FIGURE).stream().map(c -> new Object[] {c.text(), c.block(), 0}).toList());
        }
        if (lists.tables()) {
            generatedList(box, "Tables", captions.of(Captions.Kind.TABLE).stream().map(c -> new Object[] {c.text(), c.block(), 0}).toList());
        }
        if (!box.getChildren().isEmpty()) {
            header.holder.getChildren().add(box);
        }
    }

    /** One generated list: its heading and its entries, each {text, block nid, indent level}. */
    private void generatedList(VBox box, String heading, List<Object[]> entries) {
        if (entries.isEmpty()) {
            return;
        }
        Label title = new Label(heading);
        title.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
        title.setPadding(new Insets(6, 0, 1, 0));
        box.getChildren().add(title);
        for (Object[] entry : entries) {
            Label line = new Label((String) entry[0]);
            long block = (Long) entry[1];
            line.setFont(Font.font(13));
            line.setWrapText(true);
            line.setMaxWidth(Double.MAX_VALUE);
            line.setStyle("-fx-text-fill: #2a5db0;");
            line.setCursor(Cursor.HAND);
            line.setPadding(new Insets(0, 0, 0, 14 * (Integer) entry[2]));
            line.getStyleClass().add("stamped-document-list-entry");
            line.setOnMouseClicked(e -> {
                revealBlock(block);
                e.consume();
            });
            box.getChildren().add(line);
        }
    }

    /**
     * The Page layout's front lists: for each list the header asks for, its title as a level-1
     * heading and one unit per entry, with the entry's text alone — the page after its leader is
     * drawn by the line ({@link #linesOn}) from wherever the layout put the target. Each unit
     * stands on its target's own title line, so hovering an entry finds what it names, and
     * clicking it goes there. The print model's blocks are counted the same way, one per list.
     */
    private void frontLists(DocumentAssembly.Member host, StoredDocument document) {
        List<network.ike.komet.document.print.DocumentModel.FrontList> lists = captions.frontLists(
                network.ike.komet.document.print.StoredDocumentModel.lists(document));
        if (lists.isEmpty()) {
            return;
        }
        Map<String, Object[]> targets = new HashMap<>();                        // key -> {member, block}
        for (Captions.Heading heading : captions.headings()) {
            targets.put(network.ike.komet.document.print.StoredDocumentModel.key(heading.block()), new Object[] {heading.member(), heading.block()});
        }
        for (Captions.Caption caption : captions.all()) {
            targets.put(network.ike.komet.document.print.StoredDocumentModel.key(caption.block()), new Object[] {caption.member(), caption.block()});
        }
        List<StoredLine> hostTitle = document.lines().stream().filter(l -> l.text().startsWith("= ")).limit(1).toList();
        if (hostTitle.isEmpty()) {
            hostTitle = document.lines().isEmpty() ? List.of() : document.lines().subList(0, 1);
        }
        for (network.ike.komet.document.print.DocumentModel.FrontList list : lists) {
            modelOrdinal++;
            if (hostTitle.isEmpty()) {
                continue;                                         // a document with no line at all has nothing to stand its lists on
            }
            Area title = addArea(host, document.root(), hostTitle, paragraphs(hostTitle, " ", st.heading(1), t -> list.title()), 0, false, list.title(), true);
            title.heading = true;
            title.splittable = false;
            title.frontList = true;
            for (network.ike.komet.document.print.DocumentModel.Entry entry : list.entries()) {
                Object[] target = targets.get(entry.target());
                DocumentAssembly.Member member = target == null ? host : (DocumentAssembly.Member) target[0];
                StoredBlock block = target == null ? document.root() : member.document().blocks().get((Long) target[1]);
                List<StoredLine> carrier = block == null ? hostTitle : titleLine(member.document(), block, hostTitle);
                Area area = addArea(member, block == null ? document.root() : block, carrier,
                        paragraphs(carrier, " ", st.ragged(), t -> entry.text()), 0, false, list.title(), true);
                area.heading = false;                             // it names a section; it is not one
                area.splittable = false;
                area.frontList = true;
                area.entryTarget = entry.target();
                area.entryDepth = Math.max(0, entry.depth());
            }
        }
    }

    /** The line a block's title stands on: a section's heading line, a titled block's {@code .Title} line, else its first; the fallback when it has none. */
    private static List<StoredLine> titleLine(StoredDocument document, StoredBlock block, List<StoredLine> fallback) {
        List<StoredLine> lines = block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.DOCUMENT
                ? block.members().stream().map(document::line).flatMap(Optional::stream).filter(l -> l.text().startsWith("=")).limit(1).toList()
                : StructuredBlocks.lines(document, block).stream().filter(l -> BLOCK_TITLE_LINE.matcher(l.text().strip()).matches()).limit(1).toList();
        if (lines.isEmpty()) {
            lines = StructuredBlocks.lines(document, block).stream().limit(1).toList();
        }
        return lines.isEmpty() ? fallback : lines;
    }

    /**
     * The unit each front list target begins with, by the target's key: what the layout's pages
     * for the entries are read from, here and by the card's settling passes.
     *
     * @return the unit index by key, for every block on the pages that a front list could name
     */
    public Map<String, Integer> entryTargets() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < units.size(); i++) {
            Area area = units.get(i);
            if (area.block != null && !area.frontList && area.note == 0) {
                out.putIfAbsent(network.ike.komet.document.print.StoredDocumentModel.key(area.block.nid()), i);
            }
        }
        return out;
    }

    /** The page each front list target begins on in the layout last laid, 1-based, by key. */
    private Map<String, Integer> entryPages = Map.of();

    /** Scrolls the Page layout to the page a front list's target begins on. */
    private void revealTarget(String key) {
        Integer unit = entryTargets().get(key);
        if (unit == null) {
            return;
        }
        for (PageLine laid : pageLines) {
            if (laid.unit == unit && laid.lineOfUnit == 0) {
                double top = laid.unitArea.drawn != null ? laid.baseline - (laid.drawnHeight > 0 ? laid.drawnHeight : st.base() * 2)
                        : laid.baseline - metrics(laid.style())[0];
                host.revealPage(laid.page, top);
                return;
            }
        }
    }

    /** Scrolls the Reading view to a block's area, opening any fold that hides it. */
    private void revealBlock(long blockNid) {
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            if (area.block != null && area.block.nid() == blockNid) {
                unfoldToShow(i);
                surface.revealBlock(i);
                return;
            }
        }
    }

    /** Whether a block is a figure for its caption: a diagram, an image, or a block a kind draws. */
    private static boolean isFigure(StoredBlock block) {
        return block.kind() == Block.Kind.DIAGRAM || block.kind() == Block.Kind.IMAGE || StructuredBlocks.kindOf(block).isPresent();
    }

    /** The caption of a block as the document reads it, {@code Table 2. Title}; empty for a block with no title. */
    public String printCaption(StoredBlock block) {
        return block.title().isEmpty() ? "" : captions.text(block);
    }

    /** @return the captions of the shown assembly */
    public Captions captions() {
        return captions;
    }

    /** What the print model draws and captions: this view's figures and captions. */
    public network.ike.komet.document.print.StoredDocumentModel.Figures printing() {
        return new network.ike.komet.document.print.StoredDocumentModel.Figures() {
            @Override
            public List<network.ike.komet.document.DecisionFigure> of(StoredDocument document, StoredBlock block) {
                return printFigures(document, block);
            }

            @Override
            public String caption(StoredDocument document, StoredBlock block) {
                return printCaption(block);
            }

            @Override
            public String heading(StoredDocument document, StoredBlock block, String text) {
                return captions.heading(block, text);
            }

            @Override
            public List<network.ike.komet.document.print.DocumentModel.FrontList> frontLists(StoredDocument document,
                                                                                              network.ike.komet.document.print.DocumentModel.Lists lists) {
                return captions.frontLists(lists);
            }
        };
    }

    /**
     * What a block is drawn as on a page, the model and the Page layout alike.
     *
     * @param document the block's document
     * @param block    the block
     * @return its figures in order, none when it prints as text
     */
    public List<network.ike.komet.document.DecisionFigure> printFigures(StoredDocument document, StoredBlock block) {
        Optional<StructuredBlockKind> kind = StructuredBlocks.kindOf(block);
        if (kind.isEmpty()) {
            return List.of();
        }
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        // keyed by the caption too: a figure inserted before this one renumbers it, with the same source
        return printed.computeIfAbsent(source.parameters() + "\n" + captions.text(block) + "\n" + source.content(), key -> {
            try {
                List<network.ike.komet.document.DecisionFigure> figures = network.ike.komet.document.DecisionFigureText
                        .inSerif(() -> kind.get().figures(source, new StructuredBlockKind.Context(host.view(), blocks, st.base())));
                if (figures.isEmpty() || block.title().isEmpty()) {
                    return figures;
                }
                // the caption stands beneath the first figure, in the figure's own marks, so the page and the PDF agree
                List<network.ike.komet.document.DecisionFigure> captioned = new ArrayList<>(figures);
                captioned.set(0, network.ike.komet.document.DecisionFigureText
                        .inSerif(() -> network.ike.komet.document.TextFigure.captioned(captions.text(block), figures.getFirst())));
                return captioned;
            } catch (RuntimeException failure) {
                host.status("The " + kind.get().name() + " block could not be drawn for the page; it prints as its text: " + failure.getMessage());
                return List.of();
            }
        });
    }

    /** What a block is on the page: a structured open block prints as a listing does. */
    private static Block.Kind printKind(StoredBlock block) {
        if (block == null) {
            return Block.Kind.PARAGRAPH;
        }
        return block.kind() == Block.Kind.OPEN && StructuredBlocks.kindOf(block).isPresent() ? Block.Kind.LISTING : block.kind();
    }

    /** Whether a block prints as verbatim source. */
    private static boolean printsVerbatim(StoredBlock block) {
        return switch (printKind(block)) {
            case LISTING, LITERAL, DIAGRAM -> true;
            default -> false;
        };
    }

    /** The lines of a block the print model keeps: a verbatim block's all, for a source row is no markup however it reads. */
    private static List<StoredLine> printable(StoredBlock block, List<StoredLine> lines) {
        return printsVerbatim(block) ? lines : printable(lines);
    }

    /**
     * The lines the print model keeps of a block: not its attribute, anchor or comment lines, nor a
     * block-title line above its content. Below content such a line is text, in the model as in
     * Asciidoctor: {@code .For KEC} right under an {@code indexterm:} line prints as it stands.
     */
    private static List<StoredLine> printable(List<StoredLine> lines) {
        List<StoredLine> kept = new ArrayList<>();
        for (StoredLine line : lines) {
            String t = line.text().strip();
            if (!t.startsWith("//") && !ATTRIBUTE_LINE.matcher(t).matches() && !ANCHOR_LINE.matcher(t).matches()
                    && !(kept.isEmpty() && BLOCK_TITLE_LINE.matcher(t).matches())) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** The heading level offset a member's include lines carry, summed up its chain. */
    private int offsetOf(DocumentAssembly.Member member) {
        int offset = 0;
        for (DocumentAssembly.Include include : assembly.includes()) {
            if (include.resolved() && include.child() == member) {
                offset += network.ike.komet.document.print.StoredDocumentModel.Child.levelOffset(include.line().text());
                offset += offsetOf(include.in());
                break;
            }
        }
        return offset;
    }

    private void renderChildren(DocumentAssembly.Member member, List<StoredBlock> children, int depth, String section) {
        int n = 0;
        for (StoredBlock child : children) {
            render(member, child, depth, ++n, section);
        }
    }

    /** The item's marker: its number, or the bullet — on pages Typst's bullet for the item's depth, on screen a plain one. */
    private static String ordinalMarker(List<StoredLine> own, int ordinal, int depth) {
        String bullet = depth < 0 ? "•" : network.ike.komet.document.print.ModelUnits.bullet(Math.max(0, depth - 1));
        if (own.isEmpty()) {
            return bullet;
        }
        String first = own.getFirst().text().stripLeading();
        return !first.isEmpty() && (first.startsWith(".") || Character.isDigit(first.charAt(0))) ? ordinal + "." : bullet;
    }

    private static String prose(String text) {
        String t = text.strip();
        if (t.endsWith("\\")) {
            t = t.substring(0, t.length() - 1).stripTrailing();
        }
        return t;
    }

    private static final Pattern CONCEALED_TERM = Pattern.compile("indexterm:\\[[^\\]]*\\]|\\(\\(\\([^)]*\\)\\)\\)");
    private static final Pattern VISIBLE_TERM = Pattern.compile("indexterm2:\\[([^\\]]*)\\]|\\(\\(([^)]*)\\)\\)");

    /**
     * A prose line as the Reading view draws it: as the pages do, with the index terms that print
     * nothing left out, since the terms stand in the small line beneath the block, and a visible
     * term as its text.
     */
    private static String readingProse(String text) {
        return printProse(text);
    }

    /**
     * A paragraph without the lines that draw nothing, a line of index terms alone: joined with
     * the others it would leave a space at the paragraph's start. A paragraph of such lines alone
     * keeps them, so it has a run to select.
     */
    private static LineRunModel.Spec withoutEmptyLines(LineRunModel.Spec spec) {
        List<LineRunModel.Paragraph> kept = new ArrayList<>();
        for (LineRunModel.Paragraph p : spec.paragraphs()) {
            List<LineRunModel.Line> drawn = p.lines().stream().filter(l -> !l.text().isBlank()).toList();
            kept.add(drawn.isEmpty() || drawn.size() == p.lines().size() ? p : new LineRunModel.Paragraph(drawn, p.separator(), p.style()));
        }
        return new LineRunModel.Spec(kept);
    }

    /** A prose line as the pages draw it: concealed index terms, which print nothing, left out; visible ones as their text. */
    private static String printProse(String text) {
        String t = CONCEALED_TERM.matcher(text).replaceAll("");
        t = VISIBLE_TERM.matcher(t).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2)));
        return prose(t.replaceAll("  +", " "));
    }

    /**
     * A table's rows as the print model reads them, an area for each: a row need not be a block of
     * the source, which a blank line ends, so it is drawn from its cells and keeps its lines.
     */
    private void printedRows(DocumentAssembly.Member member, StoredBlock table, List<StoredBlock> children, boolean header, int depth, String section) {
        List<StoredLine> body = new ArrayList<>();
        Map<Long, StoredBlock> blockOf = new HashMap<>();
        for (StoredBlock row : children) {
            for (long nid : row.members()) {
                member.document().line(nid).ifPresent(line -> {
                    body.add(line);
                    blockOf.put(line.nid(), row);
                });
            }
        }
        List<List<network.ike.komet.document.print.TableCells.Cell>> rows =
                network.ike.komet.document.print.TableCells.rows(body.stream().map(StoredLine::text).toList(), table.parameters());
        List<Integer> widths = network.ike.komet.document.print.StoredDocumentModel.widths(table.parameters(),
                network.ike.komet.document.print.TableCells.columns(rows));
        int r = 0;
        for (List<network.ike.komet.document.print.TableCells.Cell> row : rows) {
            boolean head = header && r++ == 0;
            List<LineRunModel.Line> drawn = network.ike.komet.document.print.TableCells.drawn(row).stream()
                    .map(piece -> new LineRunModel.Line(body.get(piece.line()), piece.text())).toList();
            if (drawn.isEmpty()) {
                continue;
            }
            List<StoredLine> lines = drawn.stream().map(LineRunModel.Line::line).toList();
            LineRunModel.Spec spec = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(drawn, " ", head ? st.headerCell() : st.cell())));
            Area area = add(member, blockOf.get(lines.getFirst().nid()), lines, spec, depth, false, section);
            if (area != null) {
                area.columns = widths;
                area.tableFirst = r == 1;
                area.cellSpecs = cellSpecs(row, body, widths.size(), head ? st.headerCell() : st.cell());
            }
        }
    }

    /**
     * A table row's cells as a page prints them, each the lines its words come from: one spec per
     * column of the table, {@code null} where the row's cell is empty or the row has fewer cells
     * than the table.
     */
    private static List<LineRunModel.Spec> cellSpecs(List<network.ike.komet.document.print.TableCells.Cell> row, List<StoredLine> body, int columns, StyleAttributeMap style) {
        List<LineRunModel.Spec> specs = new ArrayList<>();
        for (int c = 0; c < columns; c++) {
            List<LineRunModel.Line> own = c >= row.size() ? List.<LineRunModel.Line>of() : row.get(c).pieces().stream()
                    .filter(piece -> !piece.text().isEmpty())
                    .map(piece -> new LineRunModel.Line(body.get(piece.line()), piece.text())).toList();
            if (own.isEmpty()) {
                specs.add(null);
                continue;
            }
            // read as the page prints it, markup gone, so a cell wraps as Typst wrapped it; a footnote in a cell
            // keeps the row's number, so the cells' own numbering is thrown away
            LineRunModel.Spec raw = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(own, " ", style)));
            specs.add(raw.inlined(new network.ike.komet.document.print.InlineText.Numbering()).spec());
        }
        return specs;
    }

    /**
     * A table's rows in the Reading view, read as the print model reads them and drawn as a grid:
     * an area for each row, whose cells stand side by side at the table's column widths, so the
     * columns line up down the table and a long cell wraps inside its column. A row need not be a
     * block of the source, which a blank line ends, and a table written a row to a line has no
     * blank lines at all. The row's own text stays the area's model and is not shown; each cell is
     * a text over the lines its words come from.
     */
    private void gridRows(DocumentAssembly.Member member, StoredBlock table, List<StoredBlock> children, boolean header, int depth, String section) {
        List<StoredLine> body = new ArrayList<>();
        Map<Long, StoredBlock> blockOf = new HashMap<>();
        for (StoredBlock row : children) {
            for (long nid : row.members()) {
                member.document().line(nid).ifPresent(line -> {
                    body.add(line);
                    blockOf.put(line.nid(), row);
                });
            }
        }
        List<List<network.ike.komet.document.print.TableCells.Cell>> rows =
                network.ike.komet.document.print.TableCells.rows(body.stream().map(StoredLine::text).toList(), table.parameters());
        List<Integer> widths = network.ike.komet.document.print.StoredDocumentModel.widths(table.parameters(),
                network.ike.komet.document.print.TableCells.columns(rows));
        double total = Math.max(1, widths.stream().mapToInt(Integer::intValue).sum());
        TableGrid fit = new TableGrid(widths);
        int r = 0;
        for (List<network.ike.komet.document.print.TableCells.Cell> row : rows) {
            boolean head = header && r == 0;
            boolean last = ++r == rows.size();
            List<LineRunModel.Line> drawn = network.ike.komet.document.print.TableCells.drawn(row).stream()
                    .map(piece -> new LineRunModel.Line(body.get(piece.line()), piece.text())).toList();
            if (drawn.isEmpty()) {
                continue;
            }
            List<StoredLine> lines = drawn.stream().map(LineRunModel.Line::line).toList();
            StyleAttributeMap style = head ? st.headerCell() : st.cell();
            Area area = add(member, blockOf.get(lines.getFirst().nid()), lines,
                    new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(drawn, " ", style))), depth, false, section);
            if (area == null) {
                continue;
            }
            javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
            grid.getStyleClass().add("doc-table-row");
            if (head) {
                grid.getStyleClass().add("doc-table-header-row");
            }
            if (last) {
                grid.getStyleClass().add("doc-table-last-row");
            }
            grid.setMaxWidth(Double.MAX_VALUE);
            List<CellText> cells = new ArrayList<>();
            for (int c = 0; c < widths.size(); c++) {
                javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
                column.setPercentWidth(100.0 * widths.get(c) / total);
                column.setFillWidth(true);
                grid.getColumnConstraints().add(column);
                VBox box = new VBox();
                box.getStyleClass().add("doc-table-cell");
                if (c == widths.size() - 1) {
                    box.getStyleClass().add("doc-table-last-cell");
                }
                box.setFillWidth(true);
                box.setMinWidth(0);
                List<LineRunModel.Line> own = c >= row.size() ? List.<LineRunModel.Line>of() : row.get(c).pieces().stream()
                        .filter(piece -> !piece.text().isEmpty())
                        .map(piece -> new LineRunModel.Line(body.get(piece.line()), piece.text())).toList();
                if (!own.isEmpty()) {
                    // a cell is prose: its marks read as a paragraph's are, and a concept it names drawn as a chip that folds to the column
                    LineRunModel.Spec read = onScreen(new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(own, " ", style))))
                            .inlined(readingNotes).spec();
                    CellText cell = new CellText(area, read.withInlines(text -> conceptChips(text, true)));
                    cells.add(cell);
                    box.getChildren().add(cell.rta);
                    for (LineRunModel.Line line : read.paragraphs().getFirst().lines()) {
                        fit.words(c, line.text(), head);
                    }
                }
                grid.add(box, c, 0);
            }
            area.cells = List.copyOf(cells);
            area.area.setVisible(false);
            area.area.setManaged(false);
            area.holder.getChildren().add(area.holder.getChildren().indexOf(area.area) + 1, grid);
            if (!fit.rows.isEmpty()) {
                // the rows are blocks of the surface, which stands its blocks apart: a row after the first closes that space
                VBox.setMargin(grid, new Insets(-DocumentSurface.BLOCK_SPACING, 0, 0, 0));
            }
            fit.add(grid);
        }
    }

    /**
     * The rows of one table in the Reading view, which share their columns' widths: each column
     * its weight's share of the row, but no narrower than its widest word, as
     * {@link TableColumnFit} shares them. Fitted again whenever the rows' width changes.
     */
    private final class TableGrid {
        /** What a cell's text stands in from its column's edges: the text's own padding and the rule beside it. */
        private static final double CELL_INSET = 20;

        final List<Integer> weights;
        final List<javafx.scene.layout.GridPane> rows = new ArrayList<>();
        /** By column: its words, each with whether a header cell draws it bold. */
        final List<Map<String, Boolean>> words = new ArrayList<>();
        /** Measures a word in the font the cells draw it in, which the scene's stylesheets name. */
        final javafx.scene.text.Text probe = new javafx.scene.text.Text();
        /** Each column's least width, once measured in a scene. */
        double[] least;
        double fitted = -1;

        TableGrid(List<Integer> weights) {
            this.weights = weights;
            for (int c = 0; c < weights.size(); c++) {
                words.add(new HashMap<>());
            }
            probe.setManaged(false);
            probe.setVisible(false);
        }

        void words(int column, String text, boolean bold) {
            for (String word : text.strip().split("\\s+")) {
                if (!word.isEmpty()) {
                    words.get(column).merge(word, bold, Boolean::logicalOr);
                }
            }
        }

        void add(javafx.scene.layout.GridPane row) {
            if (rows.isEmpty()) {
                row.getChildren().add(probe);
                row.widthProperty().addListener((obs, was, is) -> fit());
                row.sceneProperty().addListener((obs, was, is) -> fit());
            }
            rows.add(row);
        }

        private void fit() {
            javafx.scene.layout.GridPane first = rows.getFirst();
            double width = first.getWidth() - first.snappedLeftInset() - first.snappedRightInset();
            if (first.getScene() == null || width <= 0 || Math.abs(width - fitted) < 0.5) {
                return;
            }
            if (least == null) {
                least = new double[weights.size()];
                for (int c = 0; c < least.length; c++) {
                    for (Map.Entry<String, Boolean> word : words.get(c).entrySet()) {
                        probe.setText(word.getKey());
                        probe.setStyle("-fx-font-size: " + st.cell().getFontSize() + "px;" + (word.getValue() ? " -fx-font-weight: bold;" : ""));
                        probe.applyCss();
                        least[c] = Math.max(least[c], Math.ceil(probe.getLayoutBounds().getWidth()) + CELL_INSET);
                    }
                }
                probe.setText("");
            }
            fitted = width;
            double[] share = TableColumnFit.shares(weights, least, width);
            for (javafx.scene.layout.GridPane row : rows) {
                for (int c = 0; c < share.length; c++) {
                    row.getColumnConstraints().get(c).setPercentWidth(100.0 * share[c] / width);
                }
            }
        }
    }

    /** One paragraph per line, or one paragraph of all lines joined by the separator. */
    private static LineRunModel.Spec paragraphs(List<StoredLine> lines, String separator, StyleAttributeMap style,
                                                java.util.function.UnaryOperator<String> drawn) {
        List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
        if (separator.isEmpty()) {
            for (StoredLine line : lines) {
                paragraphs.add(new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line, drawn == null ? line.text() : drawn.apply(line.text()))), "", style));
            }
        } else {
            List<LineRunModel.Line> runs = new ArrayList<>();
            for (StoredLine line : lines) {
                runs.add(new LineRunModel.Line(line, drawn == null ? line.text() : drawn.apply(line.text())));
            }
            paragraphs.add(new LineRunModel.Paragraph(runs, separator, style));
        }
        return new LineRunModel.Spec(paragraphs);
    }

    /**
     * A block's title as its caption: numbered when the block is a figure or a table, beneath a
     * figure and above anything else, as the convention has it.
     */
    private void title(Area area, StoredBlock block) {
        if (!block.title().isEmpty()) {
            // a caption is a label, in one weight: its words without their markup, and without a note's macro
            Label title = new Label(network.ike.komet.document.print.InlineText.of(
                    network.ike.komet.document.print.InlineText.withoutNotes(captions.text(block))).text());
            title.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
            title.setWrapText(true);                                     // a figure's title is read whole, not cut to the column
            title.setMaxWidth(Double.MAX_VALUE);
            title.getStyleClass().add("stamped-document-caption");
            boolean figure = captions.of(block.nid()).map(c -> c.kind() == Captions.Kind.FIGURE).orElse(false);
            if (figure) {
                area.holder.getChildren().add(title);
            } else {
                area.holder.getChildren().addFirst(title);
            }
        }
    }

    /**
     * Adds a titled block's caption to the Page layout, a unit of its own before the block's, as
     * the print model has it. Page lines are built from units, so a title that is only a child
     * of the block's holder would never be drawn. Its inline markup is read even over a verbatim
     * block, whose source is not. It stands on the block's title line, so hovering it finds that.
     *
     * @param scale its size, in ems of the body
     */
    private void caption(DocumentAssembly.Member member, StoredBlock block, double scale, int depth, String section) {
        List<StoredLine> lines = StructuredBlocks.lines(member.document(), block);
        if (block.title().isEmpty() || lines.isEmpty()) {
            return;
        }
        List<StoredLine> carrier = lines.stream().filter(l -> BLOCK_TITLE_LINE.matcher(l.text().strip()).matches()).limit(1).toList();
        carrier = carrier.isEmpty() ? lines.subList(0, 1) : carrier;
        addArea(member, block, carrier, paragraphs(carrier, "", st.blockTitle(scale), t -> block.title()), depth, false, section, true).caption = true;
    }

    /**
     * Adds a block's lines to the Reading view: one area, or, where a line is an include whose
     * child the store holds, the lines before it, the child's whole rendering in its place, and
     * the lines after — so an assembly reads as one document. A block cut by an include is not
     * edited in place as a whole, so its parts are not editable.
     *
     * @return the first area of the block's own lines, or {@code null} when every line was an include shown in place
     */
    private Area add(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section) {
        return add(member, block, lines, spec, depth, editable, section, false);
    }

    /**
     * Adds a block whose text is prose: a paragraph, a list item, an admonition, a heading. The
     * Reading view reads its inline markup as the pages do, so strong text is bold, monospace is
     * set in the mono font, and a link or a cross reference reads as its words; what is not prose,
     * a listing, a header's attribute lines, a block's delimiters, stays as written.
     */
    private Area addProse(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section) {
        return add(member, block, lines, spec, depth, editable, section, true);
    }

    private Area add(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section,
                     boolean prose) {
        // the pages read every block the print model calls prose; the Reading view the blocks its caller says are
        boolean inline = print ? printsInline(block) : prose;
        List<DocumentAssembly.Part> parts = assembly.parts(lines);
        if (parts.size() == 1 && !parts.getFirst().included()) {
            if (print) {
                if (block != null && block.kind() == Block.Kind.OPAQUE) {
                    return null;                                 // comments and attribute entries print nothing
                }
                List<StoredLine> kept = block != null && (block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.DOCUMENT) ? lines : printable(block, lines);
                if (kept.isEmpty()) {
                    return null;
                }
                return addArea(member, block, kept, kept.size() == lines.size() ? spec : restrict(spec, kept), depth, editable, section, inline);
            }
            return addArea(member, block, lines, spec, depth, editable, section, inline);
        }
        Area first = null;
        for (DocumentAssembly.Part part : parts) {
            if (part.included()) {
                DocumentAssembly.Member child = part.include().child();
                int before = target.size();
                renderMember(child, depth, section);
                if (target.size() > before && !print) {
                    Label path = new Label("↳ " + child.path());
                    path.setFont(Font.font("Menlo", 11));
                    path.setStyle("-fx-text-fill: #5f6b7a;");
                    path.setPadding(new Insets(8, 0, 0, 0));
                    path.getStyleClass().add("stamped-document-member-path");
                    target.get(before).holder.getChildren().addFirst(path);
                }
            } else {
                if (print && (block != null && block.kind() == Block.Kind.OPAQUE || printable(block, part.lines()).isEmpty())) {
                    continue;
                }
                List<StoredLine> kept = print ? printable(block, part.lines()) : part.lines();
                Area area = addArea(member, block, kept, restrict(spec, kept), depth, false, section, inline);
                if (first == null) {
                    first = area;
                }
            }
        }
        return first;
    }

    /** The spec over a subset of its lines: each paragraph keeps the lines of the subset, paragraphs left empty are dropped. */
    private static LineRunModel.Spec restrict(LineRunModel.Spec spec, List<StoredLine> lines) {
        Set<Long> kept = lines.stream().map(StoredLine::nid).collect(Collectors.toSet());
        List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
        for (LineRunModel.Paragraph p : spec.paragraphs()) {
            List<LineRunModel.Line> own = p.lines().stream().filter(l -> kept.contains(l.line().nid())).toList();
            if (!own.isEmpty()) {
                paragraphs.add(new LineRunModel.Paragraph(own, p.separator(), p.style()));
            }
        }
        return new LineRunModel.Spec(paragraphs);
    }

    /** Adds one Reading-view area: the block's body with its references beneath, and its marginalia for the margins. */
    private Area addArea(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec read, int depth, boolean editable, String section) {
        return addArea(member, block, lines, read, depth, editable, section, print && printsInline(block));
    }

    private Area addArea(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec read, int depth, boolean editable, String section,
                         boolean inline) {
        LineRunModel.Inlined inlined = inline ? (print ? read.inlined(printNotes) : onScreen(read).inlined(readingNotes)) : null;
        // on screen a concept the prose names is drawn as its chip; a page prints the reference as written
        LineRunModel.Spec spec = inlined == null ? read : print ? inlined.spec() : inlined.spec().withInlines(text -> conceptChips(text, false));
        // a unit of the Page layout is bare: the oracles place it by its text and style, and its lines are built from it
        Area area = new Area(member, block, lines, spec, editable && !past, section, print);
        target.add(area);
        if (print) {
            area.modelOrdinal = modelOrdinal;
            Block.Kind kind = printKind(block);
            area.splittable = kind == Block.Kind.PARAGRAPH || kind == Block.Kind.LIST_ITEM || kind == Block.Kind.BIBLIOGRAPHY_ENTRY || kind == Block.Kind.ADMONITION;
            area.heading = area.heading || kind == Block.Kind.SECTION;
            if (inlined != null) {
                area.marks = inlined.marks();
                inlined.notes().forEach(note -> addNote(member, block, note, section));
            }
            return area;
        }
        if (block != null && editable) {
            areasByBlock.put(block.nid(), area);
        }
        area.holder.getChildren().add(area.area);
        if (inlined != null) {
            // a footnote's mark stands in the text; its words stand beneath the block, where the page has them at its foot
            for (LineRunModel.FootNote foot : inlined.notes()) {
                Label note = new Label(foot.note().drawn().text());
                note.setFont(SMALL);
                note.setWrapText(true);
                note.setMaxWidth(Double.MAX_VALUE);
                note.setStyle("-fx-text-fill: #4b5563;");
                note.getStyleClass().add("stamped-document-footnote");
                area.holder.getChildren().add(note);
            }
        }
        // a footnote read into the text stands beneath it with its number, so the references line need not say it again
        Node references = references(lines, inlined == null || inlined.notes().isEmpty());
        if (references != null) {
            area.holder.getChildren().add(references);
        }
        for (StoredLine line : lines) {
            assembly.includeOf(line.nid()).filter(i -> !i.resolved()).ifPresent(include -> {
                Label note = new Label("include " + include.target() + " — " + include.note());
                note.setFont(SMALL);
                note.setWrapText(true);
                note.setStyle("-fx-text-fill: #b26a00;");
                note.getStyleClass().add("stamped-document-include-note");
                area.holder.getChildren().add(note);
            });
        }
        area.holder.setFillWidth(true);
        area.holder.setMinWidth(0);
        area.holder.setSpacing(2);
        area.holder.setPadding(new Insets(0, 0, 0, depth * 18));
        area.date = new Label();
        area.date.setFont(SMALL);
        area.date.setStyle("-fx-text-fill: #5f6b7a;");
        area.date.setPadding(new Insets(3, 0, 0, 0));
        area.marginalia = new HBox(3);
        area.marginalia.setAlignment(Pos.TOP_LEFT);
        area.marginalia.getStyleClass().add("stamped-document-marginalia");
        area.notes.getStyleClass().add("stamped-document-notes");
        VBox right = new VBox(4, area.marginalia, area.notes);
        right.setFillWidth(true);
        // the margins' images and the fold mark's binding wait until the surface builds the block near the viewport
        area.documentBlock = DocumentBlock.prose(area.holder, area.area, area.model, spec.lines().stream().map(StoredLine::text).collect(Collectors.joining("\n")))
                .withMarginalia(dateBox(area), right)
                .withEstimatedHeight(estimatedHeight(area))
                .whenRealized(() -> {
                    fillMarginalia(area);
                    bindFoldMark(area);
                });
        return area;
    }

    /**
     * The concept references of a text, each to be drawn as the concept's chip: the chip the
     * assistant's replies and the structured blocks draw, with its name as the open store has it,
     * its status marks, and the journal's menu.
     *
     * @param text the drawn text of a paragraph or a cell
     * @param cell whether the text is a table cell's
     */
    private List<LineRunModel.Token> conceptChips(String text, boolean cell) {
        double base = st.base();
        List<LineRunModel.Token> tokens = new ArrayList<>();
        for (ConceptChipInlineDecorator.Interchange found : ConceptChipInlineDecorator.interchangeTokens(text)) {
            int start = found.start();
            int end = found.end();
            if (start > 0 && end < text.length() && text.charAt(start - 1) == '[' && text.charAt(end) == ']') {
                // a reference written inside brackets of its own, as the assistant has written some, is the reference: the brackets go with it
                start--;
                end++;
            }
            tokens.add(new LineRunModel.Token(start, end, () -> {
                Node chip = host.viewProperties() != null
                        ? KonceptChips.chip(found.id(), found.sctid(), host.viewProperties(), base)
                        : KonceptChips.chip(found.id(), found.sctid(), host.view(), base);
                if (cell && chip instanceof dev.ikm.komet.framework.controls.KonceptBadge badge) {
                    badge.setMultiLineLabel(true);               // a cell wraps: the name folds to the column, as the cell's own text does
                }
                return chip;
            }));
        }
        return tokens;
    }

    /** A spec whose spans are drawn in the screen's faces: the Reading view's prose. */
    private static LineRunModel.Spec onScreen(LineRunModel.Spec spec) {
        List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
        for (LineRunModel.Paragraph p : spec.paragraphs()) {
            paragraphs.add(new LineRunModel.Paragraph(p.lines(), p.separator(),
                    StyleAttributeMap.builder().merge(p.style()).set(LineRunModel.SCREEN, Boolean.TRUE).build(), p.runs()));
        }
        return new LineRunModel.Spec(paragraphs);
    }

    /** Whether a block's printed text is prose, its inline markup read; a listing's is its source. */
    private static boolean printsInline(StoredBlock block) {
        return switch (printKind(block)) {
            case LISTING, LITERAL, DIAGRAM, OPAQUE, IMAGE -> false;
            default -> true;
        };
    }

    /** Adds a footnote of a block as a unit of its own, right after the area that marks it: an oracle puts it at its page's foot. */
    private void addNote(DocumentAssembly.Member member, StoredBlock block, LineRunModel.FootNote foot, String section) {
        network.ike.komet.document.print.InlineText text = foot.note().drawn();
        LineRunModel.Spec spec = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(
                List.of(new LineRunModel.Line(foot.line(), text.text())), "", st.footnote(), text.runs())));
        Area area = new Area(member, block, List.of(foot.line()), spec, false, section, true);
        target.add(area);
        area.modelOrdinal = modelOrdinal;
        area.splittable = true;
        area.note = foot.note().number();
    }

    /** The left margin: the first introducing date, right-aligned against the body. */
    private Node dateBox(Area area) {
        // every date keeps the same slot beside it, so the dates stay in one column whether or not a mark is there
        Node slot;
        if (levelAt(areas.indexOf(area)) >= 1) {
            Label mark = new Label("▾");
            mark.setFont(SMALL);
            mark.setStyle("-fx-text-fill: #5f6b7a;");
            mark.setPadding(new Insets(2, 0, 0, 0));
            mark.setCursor(Cursor.HAND);
            mark.setVisible(false);
            mark.setTooltip(new Tooltip("Fold this section"));
            mark.getStyleClass().add("stamped-document-fold-mark");
            mark.setOnMouseClicked(e -> {
                toggleSection(area);
                e.consume();
            });
            area.foldMark = mark;
            slot = mark;
        } else {
            slot = new Region();
        }
        ((Region) slot).setMinWidth(FOLD_MARK_WIDTH);
        ((Region) slot).setPrefWidth(FOLD_MARK_WIDTH);
        ((Region) slot).setMaxWidth(FOLD_MARK_WIDTH);
        HBox box = new HBox(2, area.date, slot);
        box.setAlignment(Pos.TOP_RIGHT);
        box.setPadding(new Insets(0, 2, 0, 0));
        return box;
    }

    /** The width of the fold mark's slot beside a date. */
    private static final double FOLD_MARK_WIDTH = 12;

    /**
     * How tall an area is expected to stand before it is built: a line of text for each source
     * line at the base size, more for a structured block's control, so an unbuilt block holds
     * about its room and the scroll bar is about right.
     */
    private double estimatedHeight(Area area) {
        double line = st.base() * 1.45;
        double lines = Math.max(1, area.lines.size());
        double height = lines * line + 8;
        if (area.block != null && StructuredBlocks.kindOf(area.block).isPresent()) {
            height += 220;
        }
        return height;
    }

    // ── folding ───────────────────────────────────────────────────────────────

    /**
     * The heading level of an area: a section heading's, a document title's as its include's
     * offset, and a negative number for an area that is no heading.
     */
    private int levelAt(int index) {
        if (index < 0 || index >= areas.size()) {
            return -1;
        }
        Area area = areas.get(index);
        if (area.block == null) {
            return -1;
        }
        return switch (area.block.kind()) {
            case SECTION -> area.block.level();
            case DOCUMENT -> area.member.host() ? 0 : offsetOf(area.member);
            default -> -1;
        };
    }

    /** What a fold is remembered by: a heading's title, any other block's first line. */
    private String foldKey(int index) {
        Area area = areas.get(index);
        if (area.block != null && area.block.kind() == Block.Kind.SECTION && !area.block.title().isEmpty()) {
            return area.block.title();
        }
        return area.lines.isEmpty() ? "" : area.lines.getFirst().text();
    }

    private int areaIndexOf(String key) {
        for (int i = 0; i < areas.size(); i++) {
            if (key.equals(foldKey(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Folds a heading's section, or opens it when it is folded. */
    private void toggleSection(Area heading) {
        int index = areas.indexOf(heading);
        if (index < 0) {
            return;
        }
        if (!folding.unfold(index)) {
            if (folding.foldSection(index, Folding.sectionEnd(this::levelAt, areas.size(), index)).isEmpty()) {
                host.status("This section has nothing under its heading to fold.");
                return;
            }
        }
        foldsChanged();
    }

    /** Folds the blocks the selection touches, or opens the fold the selection's first block anchors. */
    private void toggleSelection() {
        int[] span = selectedAreas();
        if (span == null) {
            return;
        }
        if (folding.at(span[0]).isPresent()) {
            folding.unfold(span[0]);
        } else if (span[0] == span[1] && levelAt(span[0]) >= 1) {
            if (folding.foldSection(span[0], Folding.sectionEnd(this::levelAt, areas.size(), span[0])).isEmpty()) {
                host.status("This section has nothing under its heading to fold.");
                return;
            }
        } else {
            folding.foldRun(span[0], span[1]);
        }
        foldsChanged();
    }

    /** The first and last Reading-view areas the selection touches, or null when it touches none. */
    private int[] selectedAreas() {
        if (selection == null || view != View.READING) {
            return null;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            if (area.model != null && area.model.runs().stream().anyMatch(r -> selection.contains(ordinal(r.nid())))) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        return first < 0 ? null : new int[] {first, last};
    }

    /** Opens every fold hiding an area, so it can be seen. */
    private void unfoldToShow(int index) {
        if (folding.reveal(index)) {
            foldsChanged();
        }
    }

    private void foldsChanged() {
        applyFolds();
        host.rememberFolds(folding.keys(this::foldKey));
    }

    /**
     * Lays the folds out: a hidden area's row leaves the stack, an anchor shows the line that
     * says what is folded, beneath a heading or in place of a run's first block, and a heading's
     * mark shows while the pointer is on its row or its section is folded.
     */
    private void applyFolds() {
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            area.shown.set(!folding.hidden(i));
            Optional<Folding.Fold> fold = folding.at(i);
            area.anchored.set(fold.isPresent());
            if (area.foldMark != null) {
                area.foldMark.setText(fold.isPresent() ? "▸" : "▾");
                area.foldMark.setTooltip(new Tooltip(fold.isPresent() ? "Open this section" : "Fold this section"));
                bindFoldMark(area);
            }
            boolean inPlace = fold.isPresent() && !fold.get().atHeading();
            if (inPlace) {
                // the run's first block makes way for the fold line: its body, date and marks go, to come back as they were
                List<Node> making = new ArrayList<>(area.holder.getChildren());
                making.remove(area.foldLine);
                if (area.marginalia != null) {
                    making.add(area.marginalia);
                }
                if (area.date != null) {
                    making.add(area.date);
                }
                for (Node node : making) {
                    if (!area.hiddenByFold.containsKey(node)) {
                        area.hiddenByFold.put(node, new boolean[] {node.isVisible(), node.isManaged()});
                        node.setVisible(false);
                        node.setManaged(false);
                    }
                }
            } else if (!area.hiddenByFold.isEmpty()) {
                area.hiddenByFold.forEach((node, was) -> {
                    node.setVisible(was[0]);
                    node.setManaged(was[1]);
                });
                area.hiddenByFold.clear();
            }
            if (fold.isPresent()) {
                showFoldLine(area, i, fold.get());
            } else if (area.foldLine != null) {
                area.holder.getChildren().remove(area.foldLine);
                area.foldLine = null;
            }
        }
    }

    /** A heading's fold mark shows while the pointer is on its row, or its section is folded; bound to the row the surface built. */
    private void bindFoldMark(Area area) {
        if (area.foldMark == null) {
            return;
        }
        Node row = area.documentBlock == null ? null : area.documentBlock.stackNode();
        area.foldMark.visibleProperty().unbind();
        if (row != null) {
            area.foldMark.visibleProperty().bind(row.hoverProperty().or(area.anchored));
        }
    }

    /** The line an anchor shows: how many blocks are folded, and that a click opens them. */
    private void showFoldLine(Area anchor, int index, Folding.Fold fold) {
        if (anchor.foldLine == null) {
            Label mark = new Label("▸");
            mark.setFont(SMALL);
            Label words = new Label();
            words.setFont(SMALL);
            words.getStyleClass().add("stamped-document-fold-words");
            HBox line = new HBox(4, mark, words);
            line.setAlignment(Pos.CENTER_LEFT);
            line.setStyle("-fx-text-fill: #5f6b7a;");
            line.getStyleClass().add("stamped-document-fold-line");
            line.setId("stampedDocumentFold");
            line.setCursor(Cursor.HAND);
            line.setPadding(new Insets(2, 4, 2, 4));
            Tooltip.install(line, new Tooltip("Open the folded blocks"));
            line.setOnMouseClicked(e -> {
                folding.unfold(areas.indexOf(anchor));
                foldsChanged();
                e.consume();
            });
            anchor.foldLine = line;
            anchor.holder.getChildren().add(line);
        }
        java.util.Set<Long> blocks = new java.util.HashSet<>();
        for (int i = fold.from(); i <= fold.to(); i++) {
            Area hidden = areas.get(i);
            blocks.add(hidden.block == null ? (long) -i : hidden.block.nid());
        }
        int count = blocks.size();
        ((Label) anchor.foldLine.getChildren().get(1)).setText(count + (count == 1 ? " block folded" : " blocks folded"));
    }

    /** The references on the block's lines, as one small line, or null when there are none. */
    private Node references(List<StoredLine> own, boolean footnotes) {
        List<String> parts = new ArrayList<>();
        for (StoredLine line : own) {
            for (StoredReference r : assembly.references().getOrDefault(line.nid(), List.of())) {
                if (!footnotes && r.kind() == design.knowledge.stamp.adoc.dialect.Reference.Kind.FOOTNOTE) {
                    continue;
                }
                parts.add(switch (r.kind()) {
                    case INDEX_TERM -> "⌕ " + r.target();
                    case CROSS_REFERENCE -> "↗ " + r.target() + (r.text().isEmpty() ? "" : " (" + r.text() + ")");
                    case CITATION -> "[" + r.target() + "]";
                    case FOOTNOTE -> "† " + r.target();
                    case LINK -> "🔗 " + r.target();
                    case ESTIMATE_MARK -> "≈ " + r.target();
                    case INCLUDE -> "include " + r.target();
                    case CONDITIONAL -> "if " + r.target();
                });
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Label label = new Label(String.join("  ·  ", parts));
        label.setFont(SMALL);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #5f6b7a;");
        return label;
    }

    /**
     * Builds the Page layout's units for a print theme: bare areas with the same specs the
     * Reading view draws, in the theme's Noto fonts, without front matter, comments or
     * attribute lines, so the paged engine lays out what the Typst writer writes.
     *
     * @param theme the print theme
     */
    private void buildPages(network.ike.komet.document.surface.print.settings.DocumentTheme theme) {
        if (editing != null) {
            cancelEdit(editing);
        }
        units.clear();
        pages.clear();
        pageLines.clear();
        areaByNode.clear();
        if (assembly == null) {
            return;
        }
        network.ike.komet.document.print.DocumentFonts.loadForJavaFx();
        Styles was = st;
        List<Area> wasTarget = target;
        st = Styles.page(theme);
        target = units;
        print = true;
        printNotes = new network.ike.komet.document.print.InlineText.Numbering();
        modelOrdinal = -1;
        nesting = 0;
        try {
            renderMember(assembly.host(), 0, "");
        } finally {
            st = was;
            target = wasTarget;
            print = false;
        }
        // The selection is drawn on the pages once the engine has laid them out (the card calls
        // renderSelection then): a selection applied to an area before its first layout breaks the
        // incubator's selection highlight.
    }

    /** Draws the selection in the line areas of the pages; the card calls it once the page stack has built a sheet. */
    void renderSelection() {
        render();
    }

    /**
     * The Page layout's units for a layout oracle: the print areas built for the theme, each with
     * the text it draws, its font, and the spacing and keep rules the Typst writer gives its kind.
     *
     * @param theme the print theme
     * @return the units in document order
     */
    public List<network.ike.komet.document.print.LayoutOracle.Unit> pageUnits(network.ike.komet.document.surface.print.settings.DocumentTheme theme) {
        buildPages(theme);
        List<network.ike.komet.document.print.LayoutOracle.Unit> out = new ArrayList<>();
        double base = theme.baseFontSize();
        network.ike.komet.document.print.LayoutOracle.Kind previous = null;
        boolean afterLists = false;                               // the unit after the front lists opens the body's first page
        for (int i = 0; i < units.size(); i++) {
            Area area = units.get(i);
            Block.Kind blockKind = printKind(area.block);
            if (area.note > 0) {
                // No spacing and no place in the flow: an oracle sets it at the foot of the page its mark lands on.
                out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal, network.ike.komet.document.print.LayoutOracle.Kind.FOOTNOTE, area.spec.plainText(),
                        base * network.ike.komet.document.print.LayoutOracle.FOOTNOTE_SCALE, false, 0, 0, 0, false).withInline(area.spec.runs(), List.of(), area.note));
                continue;
            }
            // Each front list opens a page, and so does the body after the last of them, as the writer's page breaks set it.
            boolean opensPage = area.frontList ? area.heading : afterLists;
            afterLists = area.frontList;
            if (area.drawn != null) {
                double[] spacing = network.ike.komet.document.print.UnitSpacing.of(
                        network.ike.komet.document.print.LayoutOracle.Kind.FIGURE, previous, base, base);
                out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal,
                        network.ike.komet.document.print.LayoutOracle.Kind.FIGURE, network.ike.komet.document.print.LayoutOracle.FIGURE_WORD,
                        base, false, 0, spacing[0], spacing[1], false, area.drawn.width(), area.drawn.height()).withPageBefore(opensPage));
                previous = network.ike.komet.document.print.LayoutOracle.Kind.FIGURE;
                continue;
            }
            network.ike.komet.document.print.LayoutOracle.Kind kind = area.frontList
                    ? (area.heading ? network.ike.komet.document.print.LayoutOracle.Kind.HEADING : network.ike.komet.document.print.LayoutOracle.Kind.ENTRY)
                    : area.caption ? network.ike.komet.document.print.LayoutOracle.Kind.OTHER : area.heading
                    ? (blockKind == Block.Kind.DOCUMENT ? network.ike.komet.document.print.LayoutOracle.Kind.TITLE
                    : network.ike.komet.document.print.LayoutOracle.Kind.HEADING)
                    : switch (blockKind) {
                case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> network.ike.komet.document.print.LayoutOracle.Kind.LIST_ITEM;
                case LISTING, LITERAL, DIAGRAM -> network.ike.komet.document.print.LayoutOracle.Kind.VERBATIM;
                case TABLE_ROW -> network.ike.komet.document.print.LayoutOracle.Kind.TABLE_ROW;
                case PARAGRAPH, ADMONITION, SECTION, DOCUMENT -> network.ike.komet.document.print.LayoutOracle.Kind.PARAGRAPH;
                default -> network.ike.komet.document.print.LayoutOracle.Kind.OTHER;
            };
            StyleAttributeMap style = area.spec.paragraphs().isEmpty() ? st.prose() : area.spec.paragraphs().getFirst().style();
            double size = style.getFontSize() == null ? base : style.getFontSize();
            boolean bold = style.isBold();
            double indent = kind == network.ike.komet.document.print.LayoutOracle.Kind.LIST_ITEM ? base
                    : kind == network.ike.komet.document.print.LayoutOracle.Kind.ENTRY ? base * area.entryDepth : 0;
            double[] spacing = network.ike.komet.document.print.UnitSpacing.of(kind, previous, size, base,
                    kind == network.ike.komet.document.print.LayoutOracle.Kind.OTHER && blockKind == Block.Kind.TABLE);
            double above = spacing[0];
            double below = spacing[1];
            out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal, kind, area.spec.plainText(), size, bold, indent,
                    above, below, kind == network.ike.komet.document.print.LayoutOracle.Kind.HEADING).withInline(area.spec.runs(), area.marks, 0).withColumns(area.columns)
                    .withPageBefore(opensPage));
            previous = kind;
        }
        return out;
    }

    /**
     * Lays the Page layout's lines from a layout: for every line the oracle placed, the slice of
     * its unit's text it draws — each source line keeping its share, a hyphen where the oracle
     * broke a word, justified where the paragraph is and the line is not its last — and where it
     * stands. The line areas themselves, with the same hover, selection and context menu as the
     * Reading view's areas, are built page by page as the stack shows them ({@link #linesOn}).
     *
     * @param layout the oracle's answer for the units of {@link #pageUnits}
     * @param units  those units
     */
    public void layoutLines(network.ike.komet.document.print.LayoutOracle.Layout layout, List<network.ike.komet.document.print.LayoutOracle.Unit> units) {
        pages.clear();
        pageLines.clear();
        areaByNode.clear();
        if (editing != null) {
            cancelEdit(editing);
        }
        // the pages the front lists' entries print: wherever this layout put their targets
        entryPages = network.ike.komet.document.print.FrontLists.pages(layout, entryTargets());
        for (int u = 0; u < units.size() && u < this.units.size(); u++) {
            Area unitArea = this.units.get(u);
            network.ike.komet.document.print.LayoutOracle.Unit unit = units.get(u);
            List<network.ike.komet.document.print.LayoutOracle.Line> own = new ArrayList<>(layout.ofUnit(u));
            own.sort(Comparator.comparingInt(network.ike.komet.document.print.LayoutOracle.Line::from));
            // an entry's tail is no line of text: the text's last line keeps it, to draw the leader and page where it ended
            network.ike.komet.document.print.LayoutOracle.Line tail = !own.isEmpty() && own.getLast().tail(unit) ? own.removeLast() : null;
            for (int k = 0; k < own.size(); k++) {
                network.ike.komet.document.print.LayoutOracle.Line line = own.get(k);
                if (unitArea.drawn != null) {
                    pageLines.add(new PageLine(unitArea, unitArea.spec, u, k, line, false, false));
                    continue;
                }
                LineRunModel.Spec spec;
                boolean whole = line.whole(unit) && unit.kind() != network.ike.komet.document.print.LayoutOracle.Kind.VERBATIM
                        && unitArea.spec.paragraphs().size() == 1
                        && (unit.kind() == network.ike.komet.document.print.LayoutOracle.Kind.TABLE_ROW   // a header has a line on each page it repeats on
                        || unit.kind() == network.ike.komet.document.print.LayoutOracle.Kind.OTHER && own.size() == 1);
                if (whole) {
                    spec = unitArea.spec;
                } else {
                    int[] pa = unit.paragraphAt(line.from());
                    int paragraphStart = line.from() - pa[1];
                    boolean justified = pa[0] < unitArea.spec.paragraphs().size()
                            && unitArea.spec.paragraphs().get(pa[0]).style().getTextAlignment() == javafx.scene.text.TextAlignment.JUSTIFY;
                    boolean last = k + 1 >= own.size() || unit.paragraphAt(own.get(k + 1).from())[0] != pa[0];
                    // A line Typst ends at a hard break (AsciiDoc's trailing +) is ragged like a paragraph's last.
                    boolean forced = endsAtHardBreak(unitArea.spec, pa[0], line.to() - paragraphStart);
                    spec = unitArea.spec.slice(pa[0], pa[1], line.to() - paragraphStart, line.hyphenated(), justified && !last && !forced);
                }
                if (spec.paragraphs().isEmpty()) {
                    continue;
                }
                // A listing's tint is one block behind its lines, as Typst draws it: a line is taller than its pitch,
                // and painted line by line each would cover the one above.
                PageLine laid = new PageLine(unitArea, spec, u, k, line, whole, unitArea.listing);
                laid.lastOfUnit = k == own.size() - 1;
                laid.tail = laid.lastOfUnit ? tail : null;
                checkLine(laid, line, unit);
                pageLines.add(laid);
            }
        }
        if (view == View.PAGE_LAYOUT) {
            render();
        }
    }

    /**
     * Builds a laid line's area: a line-run area over the characters of its unit's text, the
     * selection and the highlighted commit as they stand, and for a table row its grid of cells.
     * No chrome of the control's own on a page line: the sheet is the paper.
     */
    private Area buildLine(PageLine laid) {
        Area unitArea = laid.unitArea;
        Area area = new Area(unitArea.member, unitArea.block, laid.spec.lines(), laid.spec, false, unitArea.section);
        area.modelOrdinal = unitArea.modelOrdinal;
        area.unit = laid.unit;
        area.lineOfUnit = laid.lineOfUnit;
        area.page = laid.page;
        area.x = laid.x;
        area.baseline = laid.baseline;
        if (unitArea.drawn != null) {
            area.drawn = unitArea.drawn;
        } else {
            area.note = unitArea.note;
            area.splittable = unitArea.splittable;
            area.heading = unitArea.heading;
            area.whole = laid.whole;
            area.listing = laid.listing;
            if (laid.whole && !unitArea.cellSpecs.isEmpty()) {
                pageRow(area, unitArea);
            }
            area.area.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0;");
            area.area.getStyleClass().add("stamped-page-line");
            area.area.setId("stampedDocumentPageLine" + laid.index);
            areaByNode.put(area.area, area);
            if (!unitArea.entryTarget.isEmpty()) {
                area.frontList = true;
                area.entryTarget = unitArea.entryTarget;
                area.entryDepth = unitArea.entryDepth;
                entryGesture(area.area, unitArea.entryTarget);
            }
        }
        if (highlightedCommit != 0) {
            area.highlightedStamp = highlightedCommit;
            area.decorate();
        }
        pages.add(area);
        laid.area = area;
        return area;
    }

    /** Whether a paragraph's drawn text ends a source line at the offset, and that source line ends in a hard break. */
    private static boolean endsAtHardBreak(LineRunModel.Spec spec, int paragraph, int to) {
        if (paragraph >= spec.paragraphs().size()) {
            return false;
        }
        LineRunModel.Paragraph p = spec.paragraphs().get(paragraph);
        int at = 0;
        for (int i = 0; i < p.lines().size(); i++) {
            if (i > 0) {
                at += p.separator().length();
            }
            LineRunModel.Line line = p.lines().get(i);
            at += line.text().isEmpty() && p.lines().size() == 1 ? 1 : line.text().length();
            String source = line.line().text().stripTrailing();
            boolean hard = source.endsWith(" +") || source.endsWith("\\");
            if (hard && to >= at - 3 && to <= at) {
                return true;                                      // the break's own marker, and the space after it, may trail the typst line's end
            }
            if (at >= to) {
                return false;
            }
        }
        return false;
    }

    /** The page lines' self-check: how many were laid, how many draw other text than Typst's line, how many start left of the measure. */
    private int linesChecked;
    private int linesTextMismatch;
    private int linesLeftOfMeasure;
    private final List<String> lineFindings = new ArrayList<>();

    /** Checks a page line against the oracle's: the text it draws is Typst's line, and it starts at or right of the measure's left edge. */
    private void checkLine(PageLine laid, network.ike.komet.document.print.LayoutOracle.Line line, network.ike.komet.document.print.LayoutOracle.Unit unit) {
        linesChecked++;
        String drawn = LineRunModel.Spec.text(laid.spec.paragraphs().getFirst()).stripTrailing();
        String expected = laid.whole ? unit.text() : line.text(unit).stripTrailing();
        if (!drawn.equals(expected)) {
            linesTextMismatch++;
            if (lineFindings.size() < 8) {
                lineFindings.add("line " + pageLines.size() + " of unit " + unit.index() + " draws '" + drawn + "' for Typst's '" + expected + "'");
            }
        }
        if (line.x() < pageContentLeft - 0.5) {
            linesLeftOfMeasure++;
            if (lineFindings.size() < 8) {
                lineFindings.add("line " + pageLines.size() + " of unit " + unit.index() + " starts at x " + line.x() + ", left of the measure at " + pageContentLeft);
            }
        }
    }

    private double pageContentLeft;

    /** Resets the self-check for a layout about to be laid; the card tells the measure's left edge. */
    public void beginLineCheck(double contentLeft) {
        pageContentLeft = contentLeft;
        linesChecked = 0;
        linesTextMismatch = 0;
        linesLeftOfMeasure = 0;
        lineFindings.clear();
    }

    /** @return the self-check's report, one line */
    public String lineCheck() {
        return "page lines: " + linesChecked + " laid, " + linesTextMismatch + " drawing other text than Typst's line, " + linesLeftOfMeasure
                + " starting left of the measure" + (lineFindings.isEmpty() ? "" : "\n  " + String.join("\n  ", lineFindings));
    }

    /** @return the tint behind each listing on a page: Typst's block, its fill and its inset, the lines drawn over it */
    public List<PageStackView.Placed> backdropsOn(int page, double contentLeft, double contentWidth) {
        Map<Integer, double[]> spans = new java.util.LinkedHashMap<>();
        for (PageLine laid : pageLines) {
            if (laid.page == page && laid.listing) {
                double[] m = metrics(laid.style());
                double top = laid.baseline - m[0];
                double bottom = top + m[1];
                spans.merge(laid.unit, new double[] {top, bottom},
                        (a, b) -> new double[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            }
        }
        List<PageStackView.Placed> out = new ArrayList<>();
        // The rule above the page's footnotes: under it Typst keeps a gap, then the first note's cap height.
        pageLines.stream().filter(laid -> laid.page == page && laid.note() > 0).min(Comparator.comparingDouble(laid -> laid.baseline)).ifPresent(laid -> {
            double size = laid.style().getFontSize();
            Region rule = new Region();
            rule.setMouseTransparent(true);
            rule.setStyle("-fx-background-color: black;");
            double top = laid.baseline - size * network.ike.komet.document.print.LayoutOracle.CAP_HEIGHT - size / network.ike.komet.document.print.LayoutOracle.FOOTNOTE_SCALE * network.ike.komet.document.print.LayoutOracle.FOOTNOTE_GAP;
            out.add(new PageStackView.Placed(rule, contentLeft, top, 0, contentWidth * network.ike.komet.document.print.LayoutOracle.FOOTNOTE_RULE, 0.5, false));
        });
        for (double[] span : spans.values()) {
            Region tint = new Region();
            tint.setMouseTransparent(true);
            tint.setStyle("-fx-background-color: rgb(247,247,247); -fx-background-radius: 3;");
            double top = span[0] - LISTING_INSET;
            out.add(new PageStackView.Placed(tint, contentLeft, top, 0, contentWidth, span[1] + LISTING_INSET - top, false));
        }
        return out;
    }

    /** The inset of a listing's block on a page, in points, what the Typst writer gives {@code raw}. */
    private static final double LISTING_INSET = 8;

    /**
     * A table row on a page: the row's cells as texts of their own, standing in a grid at the
     * table's column widths in place of the row's one text, which the Reading view's rows do too.
     * The grid is the area's drawing, placed by {@link #linesOn}; the row's own text stays its
     * model, unshown, so the row is selected and hovered as one.
     */
    private void pageRow(Area area, Area unitArea) {
        area.columns = unitArea.columns;
        area.tableFirst = unitArea.tableFirst;
        area.cellSpecs = unitArea.cellSpecs;
        List<CellText> cells = new ArrayList<>();
        List<Node> texts = new ArrayList<>();
        for (LineRunModel.Spec spec : area.cellSpecs) {
            if (spec == null) {
                texts.add(null);
                continue;
            }
            CellText cell = new CellText(area, spec);
            cell.rta.setContentPadding(new Insets(network.ike.komet.document.print.TypstOracle.TABLE_INSET));
            cell.rta.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0;");
            cell.rta.getStyleClass().add("stamped-page-line");
            cells.add(cell);
            texts.add(cell.rta);
        }
        area.cells = List.copyOf(cells);
        area.drawing = new network.ike.komet.document.print.PageTableRow(texts, area.columns, area.tableFirst);
        areaByNode.put(area.drawing, area);
    }

    /**
     * The line areas on a page, placed for the page stack. A line's area is built the first time
     * its page is asked for, and kept: a page scrolled back to costs nothing to show again.
     *
     * @param page         the 0-based page
     * @param contentLeft  the measure's left edge on the page
     * @param contentWidth the measure's width
     * @param bandHeight   the page's content height, which bounds a figure
     * @return the page's lines, in order
     */
    public List<PageStackView.Placed> linesOn(int page, double contentLeft, double contentWidth, double bandHeight) {
        List<PageStackView.Placed> out = new ArrayList<>();
        for (PageLine laid : pageLines) {
            if (laid.page != page) {
                continue;
            }
            Area area = laid.area == null ? buildLine(laid) : laid.area;
            if (area.drawn != null) {
                double scale = network.ike.komet.document.print.LayoutOracle.Unit.figureScale(area.drawn.width(), area.drawn.height(), contentWidth, bandHeight);
                if (area.drawing == null) {
                    area.drawing = network.ike.komet.document.print.FigureNode.of(area.drawn, scale);
                    areaByNode.put(area.drawing, area);
                }
                area.drawnHeight = area.drawn.height() * scale;
                laid.drawnHeight = area.drawnHeight;
                out.add(new PageStackView.Placed(area.drawing, contentLeft, area.baseline, area.drawnHeight, area.drawn.width() * scale, area.drawnHeight, false));
                continue;
            }
            StyleAttributeMap style = area.spec.paragraphs().getFirst().style();
            double[] m = metrics(style);
            if (area.drawing != null) {
                // A table row: its cells across the content width, the first cell's first line on the row's baseline.
                double rowAscent = m[0] + network.ike.komet.document.print.TypstOracle.TABLE_INSET + 1;
                out.add(new PageStackView.Placed(area.drawing, contentLeft, area.baseline, rowAscent, contentWidth,
                        m[1] + 2 * network.ike.komet.document.print.TypstOracle.TABLE_INSET, false));
                continue;
            }
            double ascent = m[0] + 2 + 1;                            // the area's content padding above the first line, and a point of air
            // JavaFX lays the glyphs without kerning, so a line Typst fit by a hair may not fit here: the area
            // is as wide as the content, or as the line's natural width when that is wider, so the line's last
            // word stays on it and justification spreads the excess over the spaces.
            double available = contentLeft + contentWidth - area.x;
            double natural = area.whole ? 0 : naturalWidth(area);
            // The measure the line is laid in: the content's, or the line's own width when that is wider; two
            // spaces of slack so the trailing space before the justify filler never wraps the last word away.
            double slack = style.getFontSize() == null ? 8 : style.getFontSize() * 0.6 + 2;
            double width = Math.max(40, Math.max(available, natural + slack) + 8);
            // A line wider than Typst's measure is scaled down to it, so both edges stay where Typst put them.
            // Not a listing's: the page draws its rows at Typst's size, so a row too long to break runs over the edge as it does in print.
            double scale = area.listing ? 1 : network.ike.komet.document.print.LayoutOracle.lineScale(natural, slack, available,
                    Boolean.TRUE.equals(style.get(LineRunModel.JUSTIFY_FILL)));
            double height;
            boolean clip;
            if (area.whole) {
                // A unit JavaFX wraps itself: a row of cells or an image line; its height is its own.
                height = Region.USE_COMPUTED_SIZE;
                clip = false;
            } else {
                height = m[1] + 4 + 2;                              // one line and the padding; the justify filler's line is clipped away
                clip = true;
            }
            // A front list's entry ends in a gap, its leader and its target's page, from the text's end to the measure's
            // right edge, as the Typst writer's entry block sets them — or, when the gap and the number have no room on
            // the text's last line, on a line of their own under it, as Typst breaks the entry there. Which line is
            // Typst's to say: the oracle read it from the measured source; the greedy estimate goes by the writer's rule.
            boolean entryEnd = !area.entryTarget.isEmpty() && laid.lastOfUnit;
            double size = style.getFontSize() == null ? st.base() : style.getFontSize();
            double gap = size * network.ike.komet.document.print.TypstWriter.ENTRY_GAP;
            double pitch = size * network.ike.komet.document.print.LayoutOracle.PITCH;
            boolean tailOwnLine = false;
            if (entryEnd) {
                if (area.entryTail == null) {
                    String number = Integer.toString(entryPages.getOrDefault(area.entryTarget, 0));
                    area.entryTail = entryTail(style, number, m[0]);
                    area.entryNumberWidth = textWidth(style, number);
                    areaByNode.put(area.entryTail, area);
                    entryGesture(area.entryTail, area.entryTarget);
                }
                tailOwnLine = laid.tail != null ? laid.tail.baseline() > area.baseline + pitch / 2
                        : !network.ike.komet.document.print.FrontLists.tailFits(available - natural * scale, gap, area.entryNumberWidth);
                if (!tailOwnLine && natural > 0) {
                    // JavaFX sets the text without kerning, a hair wider than Typst did: where Typst kept the gap and the
                    // number on the text's line, the text gives way to them rather than run under the number.
                    scale = Math.min(scale, (available - gap - area.entryNumberWidth) / natural);
                }
            }
            out.add(new PageStackView.Placed(area.area, area.x - 4, area.baseline, ascent, width, height, clip, scale));
            if (entryEnd) {
                if (tailOwnLine) {
                    double x = laid.tail != null ? laid.tail.x() : area.x;
                    double baseline = laid.tail != null ? laid.tail.baseline() : area.baseline + pitch;
                    out.add(new PageStackView.Placed(area.entryTail, x, baseline, m[0], contentLeft + contentWidth - x, m[1], false));
                } else {
                    double end = area.x + natural * scale;
                    out.add(new PageStackView.Placed(area.entryTail, end + gap, area.baseline, m[0], contentLeft + contentWidth - end - gap, m[1], false));
                }
            }
        }
        return out;
    }

    /**
     * The tail of a front list's entry: a leader of dots spread over the room from the text's
     * end to the page number, and the number against the measure's right edge, as the Typst
     * writer's entry block sets them ({@code box(width: 1fr, inset: (x: 0.25em), repeat[.])}).
     *
     * @param style  the entry's style, for the font
     * @param page   the page as text
     * @param ascent the line's ascent: where the baseline stands under the tail's top
     */
    private Region entryTail(StyleAttributeMap style, String page, double ascent) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
        javafx.scene.text.Text number = new javafx.scene.text.Text(page);
        number.setFont(Font.font(family, size));
        number.setTextOrigin(javafx.geometry.VPos.BASELINE);
        javafx.scene.shape.Line leader = new javafx.scene.shape.Line();
        leader.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        leader.setStrokeWidth(Math.max(1, size * 0.09));
        leader.setStroke(Color.BLACK);
        double inset = size * 0.25;
        javafx.scene.layout.Pane tail = new javafx.scene.layout.Pane(leader, number) {
            @Override
            protected void layoutChildren() {
                double width = getWidth();
                double numberWidth = number.getLayoutBounds().getWidth();
                number.relocate(Math.max(0, width - numberWidth), ascent - number.getBaselineOffset());
                // the dots spread evenly over the room between the insets, one every quarter em or so, as Typst's repeat justifies them
                double from = inset;
                double to = width - numberWidth - inset;
                int dots = (int) Math.floor((to - from) / (size * 0.3));
                leader.setVisible(dots >= 1);
                if (dots >= 1) {
                    double gap = dots == 1 ? 0 : (to - from) / (dots - 1);
                    leader.setStartX(from);
                    leader.setEndX(dots == 1 ? from + 0.01 : to);
                    leader.setStartY(ascent - 0.5);
                    leader.setEndY(ascent - 0.5);
                    leader.getStrokeDashArray().setAll(0.01, Math.max(0.5, gap));
                }
            }
        };
        tail.setMouseTransparent(false);
        tail.getStyleClass().add("stamped-page-entry-tail");
        tail.setMinHeight(0);
        return tail;
    }

    /** @return the width of a text set in a style's font, as the page draws it */
    private double textWidth(StyleAttributeMap style, String text) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
        javafx.scene.text.Text measured = new javafx.scene.text.Text(text);
        measured.setFont(Font.font(family, size));
        return measured.getLayoutBounds().getWidth();
    }

    /** A front list's entry on a page goes where it points when clicked. */
    private void entryGesture(Node node, String target) {
        node.setCursor(Cursor.HAND);
        node.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && !e.isShiftDown() && e.isStillSincePress()) {
                revealTarget(target);
                e.consume();
            }
        });
    }

    /** @return the unit and line-of-unit a placed line stands for */
    public int[] identity(PageStackView.Placed placed) {
        Area area = areaByNode.get(placed.node());
        return area == null ? new int[] {-1, -1} : new int[] {area.unit, area.lineOfUnit};
    }

    /**
     * Every line's place on a page, in order, without building anything: what the page stack
     * finds and restores the reader's place by. A line's top is where {@link #linesOn} puts its
     * area's top edge.
     *
     * @param page         the 0-based page
     * @param contentWidth the measure's width
     * @param bandHeight   the page's content height, which bounds a figure
     * @return the places
     */
    public List<PageStackView.Place> placesOn(int page, double contentWidth, double bandHeight) {
        List<PageStackView.Place> out = new ArrayList<>();
        for (PageLine laid : pageLines) {
            if (laid.page != page) {
                continue;
            }
            double top;
            if (laid.unitArea.drawn != null) {
                network.ike.komet.document.DecisionFigure drawn = laid.unitArea.drawn;
                double scale = network.ike.komet.document.print.LayoutOracle.Unit.figureScale(drawn.width(), drawn.height(), contentWidth, bandHeight);
                top = laid.baseline - drawn.height() * scale;
            } else {
                double[] m = metrics(laid.style());
                top = laid.baseline - (laid.whole && !laid.unitArea.cellSpecs.isEmpty()
                        ? m[0] + network.ike.komet.document.print.TypstOracle.TABLE_INSET + 1 : m[0] + 2 + 1);
            }
            out.add(new PageStackView.Place(laid.unit, laid.lineOfUnit, top));
        }
        return out;
    }

    /** @return how many laid lines there are, and how many have their area built, as {@code {laid, built}}; for tests */
    int[] pageLineCounts() {
        return new int[] {pageLines.size(), pages.size()};
    }

    /** Draws the selection in the line areas of a page, once the stack has laid them out in their sheet. */
    void renderSelection(int page) {
        for (PageLine laid : pageLines) {
            if (laid.page == page && laid.area != null) {
                applySelection(laid.area);
            }
        }
    }

    /** @return the marginalia of the units whose first line is on a page: the introducing identicons, with the hover highlight, and an edited mark */
    List<PageStackView.Mark> marginaliaOn(int page) {
        List<PageStackView.Mark> out = new ArrayList<>();
        for (PageLine laid : pageLines) {
            if (laid.page != page || laid.lineOfUnit != 0 || laid.note() > 0 || laid.unitArea.frontList) {
                continue;                                         // a note's line is its marked block's; a list's entry names a block, it is not one
            }
            Node node = unitMarginalia(laid.unit);
            if (node != null) {
                // a figure's marks stand at its head, as a block's stand at its first line
                double ascent = metrics(laid.style())[0];
                out.add(new PageStackView.Mark(node, laid.unitArea.drawn == null ? laid.baseline : laid.baseline - laid.drawnHeight + ascent, ascent));
            }
        }
        return out;
    }

    /** @return the section the running head names on a page: that of the first line on it, blank for none */
    String sectionOn(int page) {
        for (PageLine laid : pageLines) {
            if (laid.page == page) {
                return laid.unitArea.section == null ? "" : laid.unitArea.section;
            }
        }
        return "";
    }

    /** The width of a page line's text, each inline span in its own font, single spaces between words, as JavaFX lays it. */
    private double naturalWidth(Area line) {
        LineRunModel.Paragraph paragraph = line.spec.paragraphs().getFirst();
        String text = LineRunModel.Spec.text(paragraph);
        double width = 0;
        for (LineRunModel.StyledPiece piece : paragraph.pieces(0, text.length())) {
            StyleAttributeMap style = piece.style();
            double size = style.getFontSize() == null ? st.base() : style.getFontSize();
            String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
            javafx.scene.text.Text probe = new javafx.scene.text.Text(text.substring(piece.from(), piece.to()));
            probe.setFont(Font.font(family, style.isBold() ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                    style.isItalic() ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, size));
            width += probe.getLayoutBounds().getWidth();
        }
        return width;
    }

    /** @return the ascent and the line height of a style's font, in points */
    private double[] metrics(StyleAttributeMap style) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
        boolean bold = style.isBold();
        boolean italic = style.isItalic();
        String key = family + "|" + size + "|" + bold + "|" + italic;
        return metrics.computeIfAbsent(key, k -> {
            javafx.scene.text.Text probe = new javafx.scene.text.Text("Hgjp");
            probe.setFont(Font.font(family, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                    italic ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, size));
            return new double[] {probe.getBaselineOffset(), probe.getLayoutBounds().getHeight()};
        });
    }

    /** @return the Page layout's units' top-level print-model ordinals, for the fidelity report */
    int[] pageOrdinals() {
        return units.stream().mapToInt(a -> a.modelOrdinal).toArray();
    }

    /** The right-margin marginalia of a unit: its introducing identicons, with the hover highlight, and an edited mark. */
    private Node unitMarginalia(int index) {
        if (index < 0 || index >= units.size() || !layers.contains(Layer.STAMPS)) {
            return null;
        }
        Area area = units.get(index);
        Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
        for (StoredLine line : area.lines) {
            if (line.introduced() != null && line.introduced().time() != Long.MAX_VALUE) {
                distinct.putIfAbsent(line.introduced().nid(), line.introduced());
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
        stamps.sort(Comparator.comparingLong(StampEntity::time));
        FlowPane icons = new FlowPane(2, 2);
        icons.getStyleClass().add("stamped-document-page-marginalia");
        icons.getChildren().add(new dev.ikm.komet.framework.controls.StampSigil(12));   // one leads the cluster
        int shown = 0;
        for (StampEntity<?> stamp : stamps) {
            if (shown++ == 4) {
                Label more = new Label("+" + (stamps.size() - 4));
                more.setFont(Font.font(9));
                icons.getChildren().add(more);
                break;
            }
            icons.getChildren().add(identicon(stamp, 12, area));
        }
        if (area.lines.stream().anyMatch(DocumentSurfaceView::uncommitted)) {
            icons.getChildren().add(mark("edited", "#b26a00", "Edited in Komet, uncommitted"));
        }
        return icons;
    }

    // ── the Provenance layer ──────────────────────────────────────────────────

    /** The right margin: every introducing identicon, then the marks; the left margin's date beside it. */
    private void fillMarginalia(Area area) {
        if (area.documentBlock != null && !area.documentBlock.isRealized()) {
            return;                                                      // filled when the surface builds the block
        }
        HBox marginalia = area.marginalia;
        marginalia.getChildren().clear();
        List<StoredLine> lines = area.lines;
        Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
        for (StoredLine line : lines) {
            if (line.introduced() != null) {
                distinct.putIfAbsent(line.introduced().nid(), line.introduced());
            }
        }
        List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
        stamps.removeIf(stamp -> stamp.time() == Long.MAX_VALUE);
        stamps.sort(Comparator.comparingLong(StampEntity::time));
        boolean dates = layers.contains(Layer.DATES);
        area.date.setText(dates && !stamps.isEmpty() ? DAY.format(Instant.ofEpochMilli(stamps.getFirst().time())) : "");
        area.date.setVisible(dates);
        area.date.setManaged(dates);
        if (layers.contains(Layer.STAMPS)) {
            FlowPane icons = new FlowPane(3, 3);
            icons.setPrefWrapLength(120);
            if (!stamps.isEmpty()) {
                icons.getChildren().add(new dev.ikm.komet.framework.controls.StampSigil(18));   // one leads the cluster
            }
            int shown = 0;
            for (StampEntity<?> stamp : stamps) {
                if (shown++ == 8) {
                    Label more = new Label("+" + (stamps.size() - 8));
                    more.setFont(SMALL);
                    more.setStyle("-fx-text-fill: #5f6b7a;");
                    icons.getChildren().add(more);
                    break;
                }
                icons.getChildren().add(identicon(stamp, 18, area));
            }
            marginalia.getChildren().add(icons);
        }
        long edited = lines.stream().filter(DocumentSurfaceView::uncommitted).count();
        if (edited > 0) {
            marginalia.getChildren().add(mark("edited", "#b26a00", edited + " of these " + lines.size() + " line(s) edited in Komet and not yet committed in git"));
        }
        if (layers.contains(Layer.ANNOTATION_MARKS)) {
            long affirmed = lines.stream().filter(l -> !l.affirmers().isEmpty()).count();
            long refuted = lines.stream().filter(l -> !l.refuters().isEmpty()).count();
            long commented = lines.stream().filter(l -> l.inForce().stream().anyMatch(a -> a.kind() == AnnotationKind.COMMENT)).count();
            if (refuted > 0) {
                marginalia.getChildren().add(mark("✗ " + refuted, "#c62828", refuted + " of " + lines.size() + " line(s) refuted"));
            }
            if (affirmed > 0) {
                marginalia.getChildren().add(mark(affirmed == lines.size() ? "✓" : "✓ " + affirmed + "/" + lines.size(), "#2e7d32",
                        affirmed == lines.size() ? "Every line of this block is affirmed" : affirmed + " of " + lines.size() + " lines affirmed"));
            }
            if (commented > 0) {
                marginalia.getChildren().add(mark("… " + commented, "#5f6b7a", commented + " line(s) commented on"));
            }
        }
        if (layers.contains(Layer.REVIEW_QUEUE)) {
            long queued = lines.stream().filter(DocumentSurfaceView::queued).count();
            if (queued > 0) {
                marginalia.getChildren().add(mark("◦ " + queued, "#b26a00", queued + " line(s) still to review: introduced, affirmed by no one"));
            }
        }
        fillNotes(area);
    }

    /**
     * The area each note stands beside, by its annotation's STAMP: the first block its lines are
     * in, so an annotation that covers lines in several blocks is read once. Kept by area, not as
     * a set of the notes drawn, since a block is filled again every time the surface builds it or
     * a layer is switched, and a note drawn once must be drawn again in the same place.
     */
    private Map<Long, Area> noteHomes;
    /** The area a note is being written beside, or null. */
    private Area composing;
    /** Whether the Reading view was last stacked with a right margin. */
    private boolean rightMargin;

    /** Whether an annotation has words for the margin: a comment, or a refutation with a reason. */
    private static boolean spoken(GitDocumentMaterializer.StoredAnnotation annotation) {
        return annotation.stamp() != null && (annotation.kind() == AnnotationKind.COMMENT
                || annotation.kind() == AnnotationKind.REFUTATION && annotation.text() != null && !annotation.text().isBlank());
    }

    private Map<Long, Area> noteHomes() {
        if (noteHomes == null) {
            Map<Long, Area> homes = new HashMap<>();
            for (Area area : areas) {
                for (StoredLine line : area.lines) {
                    for (GitDocumentMaterializer.StoredAnnotation annotation : line.inForce()) {
                        if (spoken(annotation)) {
                            homes.putIfAbsent(annotation.stamp().nid(), area);
                        }
                    }
                }
            }
            noteHomes = homes;
        }
        return noteHomes;
    }

    /**
     * Whether the right margin has anything to stand in it: the stamps, a note being written, the
     * comments in force while their layer is on, or the marks of the annotation and review layers.
     * A layer that is on is drawn, whether or not the stamps are.
     */
    private boolean rightMarginInUse() {
        if (layers.contains(Layer.STAMPS) || composing != null) {
            return true;
        }
        if (assembly == null) {
            return false;
        }
        if (layers.contains(Layer.COMMENTS) && !noteHomes().isEmpty()) {
            return true;
        }
        boolean marks = layers.contains(Layer.ANNOTATION_MARKS);
        boolean queue = layers.contains(Layer.REVIEW_QUEUE);
        if (!marks && !queue) {
            return false;
        }
        for (StoredLine line : assembly.lines()) {
            if (queue && queued(line)) {
                return true;
            }
            if (marks && (!line.affirmers().isEmpty() || !line.refuters().isEmpty()
                    || line.inForce().stream().anyMatch(a -> a.kind() == AnnotationKind.COMMENT))) {
                return true;
            }
        }
        return false;
    }

    /** Stacks the view again where the reader is: a margin came or went, and the place must not. */
    private void restackInPlace() {
        // the pages and the graph stand in the surface's place: there is nothing of its own to keep
        DocumentSurface.Viewpoint at = view == View.READING || view == View.SOURCE ? surface.viewpoint() : null;
        stack();
        if (at != null) {
            surface.restoreViewpoint(new DocumentSurface.Viewpoint(at.blockIndex(), at.fraction(), null));
        }
    }

    /**
     * The margin's notes, while the Comments layer is on: every comment in force on the area's
     * lines, and every refutation with a reason, each beside the first block its lines are in,
     * with who wrote it and when. A note being written stays whatever the layer says.
     */
    private void fillNotes(Area area) {
        area.notes.getChildren().removeIf(n -> !n.getStyleClass().contains(COMPOSER_STYLE));
        if (!layers.contains(Layer.COMMENTS)) {
            return;
        }
        Set<Long> drawn = new HashSet<>();
        for (StoredLine line : area.lines) {
            for (GitDocumentMaterializer.StoredAnnotation annotation : line.inForce()) {
                if (spoken(annotation) && noteHomes().get(annotation.stamp().nid()) == area && drawn.add(annotation.stamp().nid())) {
                    area.notes.getChildren().add(note(annotation, area));
                }
            }
        }
    }

    private static final String COMPOSER_STYLE = "stamped-document-note-composer";

    /** One note: the author's identicon and the day, then the text, on a quiet rule; its tooltip says what it is. */
    private Node note(GitDocumentMaterializer.StoredAnnotation annotation, Area area) {
        StampEntity<?> stamp = annotation.stamp();
        Label when = new Label(DAY.format(Instant.ofEpochMilli(stamp.time())));
        when.setFont(SMALL);
        when.setStyle("-fx-text-fill: #8a93a0;");
        HBox who = new HBox(4, identicon(stamp, 12, area), when);
        who.setAlignment(Pos.CENTER_LEFT);
        Label text = new Label(annotation.text() == null ? "" : annotation.text().strip());
        text.setFont(SMALL);
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        text.setStyle("-fx-text-fill: " + (annotation.kind() == AnnotationKind.REFUTATION ? "#a32d2d" : "#4b5563") + ";");
        VBox note = new VBox(1, who, text);
        note.setFillWidth(true);
        note.getStyleClass().add("stamped-document-note");
        note.setStyle("-fx-border-color: transparent transparent transparent " + (annotation.kind() == AnnotationKind.REFUTATION ? "#e0a0a0" : "#c8ced8")
                + "; -fx-border-width: 0 0 0 2; -fx-padding: 1 0 1 6;");
        Tooltip.install(note, new Tooltip((annotation.kind() == AnnotationKind.REFUTATION ? "Refutation" : "Comment") + " by "
                + StampNames.name(host.view(), stamp.authorNid()) + " · " + DAY.format(Instant.ofEpochMilli(stamp.time()))));
        return note;
    }

    /**
     * Opens a note in the margin beside the selection's first block, for the person to write an
     * annotation's text where it will live: Enter records it, Shift+Enter breaks a line, Escape
     * discards it. A note the kind requires does not record empty.
     *
     * @param kind     the annotation's kind
     * @param prompt   what the note asks for
     * @param required whether the text is required
     * @param signed   whether the commit will be signed, said quietly in the note
     * @param record   takes the text when the person is done
     */
    public void composeAnnotation(AnnotationKind kind, String prompt, boolean required, boolean signed, java.util.function.Consumer<String> record) {
        int[] span = selectedAreas();
        if (span == null) {
            host.status(view == View.READING ? "Select the lines first, then " + kind.verb().toLowerCase() + " them."
                    : "A note is written in the Reading view's margin; switch to the Reading view to " + kind.verb().toLowerCase() + " these lines.");
            return;
        }
        closeComposer(false);
        Area area = areas.get(span[0]);
        javafx.scene.control.TextArea text = new javafx.scene.control.TextArea();
        text.setId("stampedDocumentNoteText");
        text.setPromptText(prompt);
        text.setWrapText(true);
        text.setPrefRowCount(3);
        text.setFont(SMALL);
        text.setMaxWidth(Double.MAX_VALUE);
        Label hint = new Label((signed ? "signed · " : "") + "Enter records, Esc discards");
        hint.setFont(Font.font(10));
        hint.setStyle("-fx-text-fill: #8a93a0;");
        hint.setWrapText(true);
        VBox composer = new VBox(2, text, hint);
        composer.setFillWidth(true);
        composer.getStyleClass().add(COMPOSER_STYLE);
        composer.setStyle("-fx-border-color: transparent transparent transparent #378add; -fx-border-width: 0 0 0 2; -fx-padding: 1 0 1 6;");
        text.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> InlineMarks.forKey(e).ifPresent(mark -> {
            // the same keys as the block editor's, over the note's whole text
            javafx.scene.control.IndexRange range = text.getSelection();
            InlineMarks.Edit edit = mark.toggle(text.getText(), range.getStart(), range.getEnd());
            text.setText(edit.text());
            text.selectRange(edit.start(), edit.end());
            e.consume();
        }));
        text.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                closeComposer(true);
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                String body = text.getText().strip();
                if (required && body.isEmpty()) {
                    hint.setText("A reason is needed");
                    hint.setStyle("-fx-text-fill: #a32d2d;");
                } else {
                    closeComposer(false);                           // the record reads the document again, which stacks it
                    record.accept(body);
                }
                e.consume();
            }
        });
        area.notes.getChildren().addFirst(composer);
        composing = area;
        area.documentBlock.setPinned(true);                         // stays built while it is written in, wherever the reader scrolls
        // the note is focused when it joins the scene: its block may be built, or its margin stacked, a pulse from now
        text.sceneProperty().addListener((obs, was, is) -> {
            if (is != null) {
                javafx.application.Platform.runLater(text::requestFocus);
            }
        });
        if (!layers.contains(Layer.COMMENTS)) {
            // what is written will stand in the margin once recorded, so the layer it stands in comes on
            layers.add(Layer.COMMENTS);
            host.layerShown(Layer.COMMENTS);
            redecorate();
        } else if (!rightMargin) {
            restackInPlace();                                       // the margin comes for the note, whatever else is off
        }
        unfoldToShow(span[0]);
        surface.ensureBuilt(span[0]);
        javafx.application.Platform.runLater(text::requestFocus);   // once it is in the scene and laid out
    }

    /** @return whether a note is being written in the margin */
    boolean composing() {
        return composing != null;
    }

    /**
     * Takes away the note being written, if one is.
     *
     * @param restack whether to give the margin back to the body when nothing else stands in it
     */
    private void closeComposer(boolean restack) {
        Area area = composing;
        if (area == null) {
            return;
        }
        composing = null;
        area.notes.getChildren().removeIf(n -> n.getStyleClass().contains(COMPOSER_STYLE));
        if (area.documentBlock != null && area != editing) {
            area.documentBlock.setPinned(false);
        }
        if (restack && view == View.READING && rightMargin && !rightMarginInUse()) {
            restackInPlace();
        }
    }

    private static Label mark(String text, String colour, String tooltip) {
        Label mark = new Label(text);
        mark.setFont(SMALL);
        mark.setStyle("-fx-text-fill: " + colour + "; -fx-font-weight: bold;");
        mark.setTooltip(new Tooltip(tooltip));
        return mark;
    }

    /** Whether a line's latest version was written in Komet and is not yet committed. */
    public static boolean uncommitted(StoredLine line) {
        return line.latest() != null && line.latest().time() == Long.MAX_VALUE;
    }

    /** Whether a line is in the review queue: introduced, committed, affirmed by no one (refuted lines stay in it). */
    public static boolean queued(StoredLine line) {
        return line.introduced() != null && line.introduced().time() != Long.MAX_VALUE && line.affirmers().isEmpty();
    }

    /** An identicon of the STAMP; in a block's margin, hovering it highlights the runs that commit introduced. */
    private Node identicon(StampEntity<?> stamp, double size, Area area) {
        Image image = badges.computeIfAbsent(stamp.nid(), nid -> renderIdenticon(stamp));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitHeight(size);
        if (area != null) {
            long introduced = area.lines.stream().filter(l -> l.introduced() != null && l.introduced().nid() == stamp.nid()).count();
            Tooltip.install(view, new Tooltip(StampNames.name(host.view(), stamp.authorNid()) + " · " + DAY.format(Instant.ofEpochMilli(stamp.time()))
                    + host.summaries().summary(stamp.nid()).map(s -> "\n" + s).orElse("")
                    + "\nIntroduced " + introduced + " of these " + area.lines.size() + " line(s)"));
            view.setOnMouseEntered(e -> {
                area.highlightedStamp = stamp.nid();
                area.decorate();
                applySelection(area);
            });
            view.setOnMouseExited(e -> {
                area.highlightedStamp = 0;
                area.decorate();
                applySelection(area);
            });
        }
        return view;
    }

    /** @return the STAMP's identicon, rendered once per STAMP */
    Image badge(StampEntity<?> stamp) {
        return badges.computeIfAbsent(stamp.nid(), nid -> renderIdenticon(stamp));
    }

    private Image renderIdenticon(StampEntity<?> stamp) {
        UUID seed = stamp.publicId().asUuidArray()[0];
        byte[] png = KonceptIdenticon.png(PublicIds.of(seed).idString(), KonceptIdenticon.DISPLAY_MODULE_SIZE);
        return new Image(new ByteArrayInputStream(png));
    }

    /** The commit's tint: a pastel hue derived from the STAMP's identicon seed, the same everywhere. */
    static Color tint(StampEntity<?> stamp) {
        UUID seed = stamp.publicId().asUuidArray()[0];
        int hue = (int) (Math.floorMod(seed.getMostSignificantBits(), 360L));
        return Color.hsb(hue, 0.22, 1.0);
    }

    /** Highlights every run of a commit, in every area, as a History view's selection does; 0 clears. */
    void highlightCommit(long stampNid) {
        highlightedCommit = stampNid;
        for (Area area : areas) {
            if (area != editing) {
                area.highlightedStamp = stampNid;
                area.decorate();
                applySelection(area);
            }
        }
        for (Area source : sources) {
            source.highlightedStamp = stampNid;
            source.decorate();
            applySelection(source);
        }
        for (Area page : pages) {
            page.highlightedStamp = stampNid;
            page.decorate();
            applySelection(page);
        }
        if (stampNid != 0 && view == View.READING) {
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).lines.stream().anyMatch(l -> l.introduced() != null && l.introduced().nid() == stampNid)) {
                    surface.revealBlock(i);
                    break;
                }
            }
        }
    }

    private LineRunModel.Decoration decoration(Area area) {
        boolean tints = layers.contains(Layer.TINTS);
        boolean queue = layers.contains(Layer.REVIEW_QUEUE);
        boolean marks = layers.contains(Layer.ANNOTATION_MARKS);
        long highlighted = area.highlightedStamp;
        return new LineRunModel.Decoration() {
            @Override
            public Color tint(StoredLine line) {
                return tints && line.introduced() != null && line.introduced().time() != Long.MAX_VALUE ? DocumentSurfaceView.tint(line.introduced()) : null;
            }

            @Override
            public boolean underline(StoredLine line) {
                return queue && queued(line);
            }

            @Override
            public boolean wavy(StoredLine line) {
                return marks && !line.refuters().isEmpty();
            }

            @Override
            public Color highlight(StoredLine line) {
                return highlighted != 0 && line.introduced() != null && line.introduced().nid() == highlighted ? HOVER_HIGHLIGHT : null;
            }
        };
    }

    /** Redraws the layer over every area and the marginalia, restacks when the margins came or went, keeping the selection. */
    private void redecorate() {
        for (Area area : areas) {
            if (area != editing) {
                area.decorate();
            }
            fillMarginalia(area);
        }
        for (Area source : sources) {
            source.decorate();
            source.area.setLeftDecorator(new LedgerDecorator(source));
        }
        for (Area page : pages) {
            page.decorate();
        }
        restackInPlace();
    }

    /**
     * One cell of a table row in the Reading view: a text of its own over the source lines the
     * cell's text comes from, so a cell hovers, selects and copies as a line does anywhere else.
     */
    private final class CellText {
        final Area row;
        final LineRunModel.Spec spec;
        final RichTextArea rta = new RichTextArea();
        LineRunModel model;

        CellText(Area row, LineRunModel.Spec spec) {
            this.row = row;
            this.spec = spec;
            rta.setEditable(false);
            rta.setWrapText(true);
            rta.setUseContentHeight(true);
            rta.setDisplayCaret(false);
            rta.setHighlightCurrentParagraph(false);
            rta.setContentPadding(new Insets(3, 6, 3, 6));
            rta.getStyleClass().addAll("doc-prose-block", "doc-table-cell-text");
            wire(row, rta, () -> model);
            decorate();
        }

        void decorate() {
            model = spec.build(decoration(row));
            applying = true;
            try {
                rta.setModel(model);
            } finally {
                applying = false;
            }
        }
    }

    // ── hover ─────────────────────────────────────────────────────────────────

    /**
     * Wires a text of an area to the surface's gestures: the area's own, or a table cell's.
     *
     * @param model the text's model as it stands when a gesture arrives, since redrawing replaces it
     */
    private void wire(Area area, RichTextArea rta, java.util.function.Supplier<LineRunModel> model) {
        rta.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (area == editing) {
                return;
            }
            Optional<LineRun> run = model.get().runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
            if (run.isEmpty()) {
                hidePopover();
                return;
            }
            if (hovered == null || hovered.nid() != run.get().nid()) {
                hidePopover();
                hovered = run.get();
                hoverAt = new double[] {e.getScreenX(), e.getScreenY()};
                hoverArea = rta;
                hoverDelay.playFromStart();
            }
        });
        rta.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hidePopover());
        rta.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> hidePopover());
        rta.selectionProperty().addListener((obs, was, is) -> snap(area, model.get(), is));
        rta.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (area == editing) {
                return;
            }
            if (e.getButton() == MouseButton.SECONDARY) {
                e.consume();                                    // the context menu handles it; the caret stays
                return;
            }
            if (e.isShiftDown() && e.getButton() == MouseButton.PRIMARY && selection != null) {
                Optional<LineRun> run = model.get().runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
                if (run.isPresent()) {
                    selection = selection.extendTo(ordinal(run.get().nid()));
                    render();
                    host.selectionChanged();
                    e.consume();
                }
            }
        });
        rta.setOnContextMenuRequested(e -> {
            if (area == editing) {
                // a block in edit has the marks' menu: the same Format items, with their keys shown
                if (area.structuredEditor == null) {
                    editingMenu.show(rta, e.getScreenX(), e.getScreenY());
                }
                e.consume();
                return;
            }
            hidePopover();
            Optional<LineRun> run = model.get().runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
            if (run.isPresent()) {
                int o = ordinal(run.get().nid());
                if (selection == null || !selection.contains(o)) {
                    selection = new LineSelection(o, o);
                    render();
                    host.selectionChanged();
                }
            }
            boolean structural = editableBlockOfSelection().isEmpty() || editing != null;
            editItem.setDisable(structural);
            deleteItem.setDisable(structural);
            insertMenu.setDisable(structural);
            formatMenu.setDisable(editing == null || editing.structuredEditor != null);
            refreshInsertMenu();
            refreshFoldItem();
            signItem.setSelected(host.signsAnnotations());
            viewItems.forEach((candidate, item) -> item.setSelected(candidate == view));
            menu.show(rta, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void showPopover() {
        if (hovered == null || hoverArea == null || hoverArea.getScene() == null || hoverArea.getScene().getWindow() == null) {
            return;
        }
        StoredLine line = assembly == null ? hovered.line() : assembly.line(hovered.nid()).orElse(hovered.line());
        Node content = ProvenancePopover.content(line, host.view(), host.summaries(), s -> badges.computeIfAbsent(s.nid(), n -> renderIdenticon(s)), null);
        content.setId("stampedDocumentProvenancePopover");
        popover.getContent().setAll(content);
        popover.show(hoverArea.getScene().getWindow(), hoverAt[0] + 14, hoverAt[1] + 18);
    }

    private void hidePopover() {
        hoverDelay.stop();
        hovered = null;
        if (popover.isShowing()) {
            popover.hide();
        }
    }

    // ── selection ─────────────────────────────────────────────────────────────

    /** Snaps a gesture's selection in one area to whole lines and makes it the document's. */
    private void snap(Area area, LineRunModel model, SelectionSegment is) {
        if (applying || area == editing || is == null || model == null) {
            return;
        }
        Optional<LineRun> anchor = model.runAt(is.getAnchor());
        Optional<LineRun> caret = model.runAt(is.getCaret());
        if (anchor.isEmpty() || caret.isEmpty()) {
            return;
        }
        LineSelection wanted = new LineSelection(ordinal(anchor.get().nid()), ordinal(caret.get().nid()));
        if (wanted.anchor() < 0 || wanted.caret() < 0) {
            return;
        }
        selection = wanted;
        render();
        host.selectionChanged();
    }

    /** Draws the selection in every area of the current view: whole runs, nothing elsewhere. */
    private void render() {
        for (Area area : shownAreas()) {
            applySelection(area);
        }
    }

    /** The areas of the view shown. */
    private List<Area> shownAreas() {
        return switch (view) {
            case READING -> areas;
            case SOURCE -> sources;
            case PAGE_LAYOUT -> pages;
            case GRAPH -> List.of();                             // the graph draws the selection itself
        };
    }

    private void applySelection(Area area) {
        if (area == editing || area.model == null) {
            return;
        }
        applying = true;
        try {
            applySelection(area.area, area.model);
            for (CellText cell : area.cells) {
                applySelection(cell.rta, cell.model);
            }
        } finally {
            applying = false;
        }
    }

    /** Draws the selection in one text: the runs of its model the selection holds, as one stretch. */
    private void applySelection(RichTextArea rta, LineRunModel model) {
        LineRun first = null;
        LineRun last = null;
        if (selection != null) {
            for (LineRun run : model.runs()) {
                if (selection.contains(ordinal(run.nid()))) {
                    if (first == null) {
                        first = run;
                    }
                    last = run;
                }
            }
        }
        if (first == null || !inWindow(rta)) {
            rta.clearSelection();
        } else if (selection.caret() >= selection.anchor()) {
            rta.select(first.startPos(), last.endPos());
        } else {
            rta.select(last.endPos(), first.startPos());
        }
    }

    /**
     * Whether a RichTextArea can draw a selection: its shapes are mapped through the screen, and within one
     * paragraph the control does not survive the null it gets off a window.
     */
    private static boolean inWindow(RichTextArea area) {
        return area.getScene() != null && area.getScene().getWindow() != null;
    }

    /** Scrolls the first selected block into view. */
    private void reveal() {
        if (selection == null) {
            return;
        }
        List<Area> shown = shownAreas();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).frontList) {
                continue;                                         // a list's entry stands on its target's line; the target is what is revealed
            }
            if (shown.get(i).model.runs().stream().anyMatch(r -> selection.contains(ordinal(r.nid())))) {
                if (view == View.READING) {
                    unfoldToShow(i);                                 // a block scrolled to is seen, folded or not
                }
                if (view == View.PAGE_LAYOUT) {
                    Area line = shown.get(i);
                    host.revealPage(line.page, line.baseline - metrics(line.spec.paragraphs().getFirst().style())[0]);
                } else {
                    surface.revealBlock(i);
                }
                return;
            }
        }
    }

    // ── the context menu ──────────────────────────────────────────────────────

    private void buildMenu() {
        menu.setId("stampedDocumentContextMenu");
        // Annotate: the acts on a committed selection
        MenuItem affirm = item("Affirm…", "stampedDocumentAffirm", () -> host.annotate(AnnotationKind.AFFIRMATION));
        MenuItem refute = item("Refute…", "stampedDocumentRefute", () -> host.annotate(AnnotationKind.REFUTATION));
        MenuItem comment = item("Comment…", "stampedDocumentComment", () -> host.annotate(AnnotationKind.COMMENT));
        MenuItem retract = item("Retract…", "stampedDocumentRetract", () -> host.annotate(AnnotationKind.RETRACTION));
        // Block: the verbs on the editable block the selection starts in
        editItem.setId("stampedDocumentEditBlock");
        editItem.setOnAction(e -> editSelectedBlock());
        deleteItem.setId("stampedDocumentDeleteBlock");
        deleteItem.setOnAction(e -> host.deleteSelectedBlock());
        // Insert: filled when the menu is asked for, since what the kinds offer reads the shown document
        insertMenu.setId("stampedDocumentInsertMenu");
        // View: the four views, the shown one checked
        for (View candidate : View.values()) {
            CheckMenuItem item = new CheckMenuItem(switch (candidate) {
                case SOURCE -> "Source";
                case READING -> "Reading";
                case PAGE_LAYOUT -> "Page layout";
                case GRAPH -> "Graph";
            });
            item.setId("stampedDocumentMenuView" + DocumentInserts.camel(candidate.name().toLowerCase(java.util.Locale.ROOT)));
            item.setOnAction(e -> {
                item.setSelected(candidate == view);                // the card switches; the check follows the view
                host.viewRequested(candidate);
            });
            viewItems.put(candidate, item);
        }
        // Format: the inline marks of the block being edited, each with its key, which the menu teaches;
        // in the surface's menu as a group, and as the whole menu of a block in edit
        formatMenu.setId("stampedDocumentFormatMenu");
        editingMenu.setId("stampedDocumentEditingMenu");
        for (InlineMarks mark : InlineMarks.values()) {
            formatMenu.getItems().add(formatItem(mark, "stampedDocumentFormat"));
            editingMenu.getItems().add(formatItem(mark, "stampedDocumentEditing"));
        }
        MenuItem cancelEditing = new MenuItem("Cancel edit");
        cancelEditing.setId("stampedDocumentEditingCancel");
        cancelEditing.setOnAction(e -> cancelEdit(editing));
        editingMenu.getItems().addAll(new SeparatorMenuItem(), cancelEditing);
        // Fold: the reader's view of the selected blocks, or of a heading's section
        foldItem.setId("stampedDocumentFold");
        foldItem.setOnAction(e -> toggleSelection());
        // Record: what the document is and was, and how an annotation is recorded
        MenuItem history = item("Show history", "stampedDocumentShowHistory", host::showHistory);
        MenuItem print = item("Print to PDF…", "stampedDocumentMenuPrintPdf", host::printPdf);
        signItem.setId("stampedDocumentSignAnnotations");
        signItem.setOnAction(e -> host.setSignsAnnotations(signItem.isSelected()));
        menu.getItems().addAll(affirm, refute, comment, retract, new SeparatorMenuItem(), editItem, deleteItem, new SeparatorMenuItem(), insertMenu,
                formatMenu, new SeparatorMenuItem(), foldItem, new SeparatorMenuItem());
        menu.getItems().addAll(viewItems.values());
        menu.getItems().addAll(new SeparatorMenuItem(), history, print, signItem);
    }

    /** A menu item for an inline mark, its key shown beside it, acting on the block in edit. */
    private MenuItem formatItem(InlineMarks mark, String idPrefix) {
        MenuItem item = new MenuItem(mark.label());
        item.setId(idPrefix + DocumentInserts.camel(mark.name().toLowerCase(java.util.Locale.ROOT)));
        item.setAccelerator(mark.key());
        item.setOnAction(e -> {
            if (editing != null && editing.structuredEditor == null) {
                applyMark(editing.area, mark);
                editing.area.requestFocus();
            }
        });
        return item;
    }

    /** The Fold item says what it would do to the selection: open a fold, fold a section, or fold the selected blocks. */
    private void refreshFoldItem() {
        int[] span = selectedAreas();
        if (span == null) {
            foldItem.setText("Fold");
            foldItem.setDisable(true);
            return;
        }
        foldItem.setDisable(false);
        if (folding.at(span[0]).isPresent()) {
            foldItem.setText("Unfold");
        } else if (span[0] == span[1] && levelAt(span[0]) >= 1) {
            foldItem.setText("Fold section");
        } else {
            int blocks = span[1] - span[0] + 1;
            foldItem.setText(blocks == 1 ? "Fold block" : "Fold " + blocks + " blocks");
        }
    }

    private static MenuItem item(String text, String id, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        item.setOnAction(e -> action.run());
        return item;
    }

    /**
     * Fills the Insert submenu for the shown document: the standard blocks, the admonitions, then
     * every kind that offers a skeleton, read over the document's block context so a rendering
     * names the model the document has.
     */
    private void refreshInsertMenu() {
        insertMenu.getItems().clear();
        for (DocumentInserts.Insertion insertion : DocumentInserts.standard()) {
            insertMenu.getItems().add(insertItem(insertion));
        }
        Menu admonitions = new Menu("Admonition");
        admonitions.setId("stampedDocumentInsertAdmonition");
        for (DocumentInserts.Insertion insertion : DocumentInserts.admonitions()) {
            admonitions.getItems().add(insertItem(insertion));
        }
        insertMenu.getItems().add(admonitions);
        StructuredBlockKind.Context context = new StructuredBlockKind.Context(host.view(), host.viewProperties(), blocks, st.base(), null);
        List<MenuItem> ours = new ArrayList<>();
        for (StructuredBlockKind kind : StructuredBlocks.kinds()) {
            DocumentInserts.of(kind, context).ifPresent(insertion -> ours.add(insertItem(insertion)));
        }
        if (!ours.isEmpty()) {
            insertMenu.getItems().add(new SeparatorMenuItem());
            insertMenu.getItems().addAll(ours);
        }
    }

    private MenuItem insertItem(DocumentInserts.Insertion insertion) {
        MenuItem item = new MenuItem(insertion.label());
        item.setId(insertion.id());
        item.setOnAction(e -> host.insertAfterSelection(insertion));
        return item;
    }

    /** @return the editable block the selection starts in, for the host's structural verbs */
    Optional<StoredBlock> selectedEditableBlock() {
        return editableBlockOfSelection().map(a -> a.block);
    }

    /** The editable block the selection starts in, if it starts in one. */
    private Optional<Area> editableBlockOfSelection() {
        if (selection == null || assembly == null || selection.min() < 0 || selection.min() >= assembly.lines().size()) {
            return Optional.empty();
        }
        long nid = assembly.lines().get(selection.min()).nid();
        return areas.stream().filter(a -> a.editable && a.lines.stream().anyMatch(l -> l.nid() == nid)).findFirst();
    }

    /** Opens the block the selection starts in for editing in place, in the Reading view. */
    void editSelectedBlock() {
        Optional<Area> area = editableBlockOfSelection();
        if (area.isEmpty()) {
            host.status("Select a line of a paragraph or listing to edit its block.");
            return;
        }
        if (editing != null) {
            host.status("Finish the edit in progress first: Save or Cancel it.");
            return;
        }
        if (view != View.READING) {
            host.viewRequested(View.READING);
            setView(View.READING);
        }
        startEdit(area.get());
    }

    /** Opens a block for editing by its nid, for the host's automation. */
    public boolean editBlock(long blockNid) {
        Area area = areasByBlock.get(blockNid);
        if (area == null || !area.editable || editing != null) {
            return false;
        }
        if (view != View.READING) {
            host.viewRequested(View.READING);
            setView(View.READING);
        }
        startEdit(area);
        return true;
    }

    // ── editing in place ──────────────────────────────────────────────────────

    /** Flips the block's area to an editable model, one paragraph per source line, with Save and Cancel beneath. */
    private void startEdit(Area area) {
        hidePopover();
        if (area.structured != null) {
            Optional<StructuredBlockKind.Editor> editor = structuredEditor(area);
            if (editor.isPresent()) {
                startStructuredEdit(area, editor.get());
                return;
            }
            // No editor of its own: the block is edited as its source text.
            showStructured(area, false);
        }
        editing = area;
        if (area.documentBlock != null) {
            area.documentBlock.setPinned(true);                          // stays built while it is edited, wherever the reader scrolls
        }
        RichTextArea rta = area.area;
        RichTextModel model = new RichTextModel();
        applying = true;
        try {
            rta.setModel(model);
            rta.appendText(area.lines.stream().map(StoredLine::text).collect(Collectors.joining("\n")), Styles.SCREEN.mono());
        } finally {
            applying = false;
        }
        rta.setEditable(true);
        rta.setDisplayCaret(true);
        rta.setWrapText(true);
        rta.setId("stampedDocumentBlockEditor");
        rta.setStyle("-fx-background-color: #FFFDF5; -fx-border-color: #b26a00; -fx-border-radius: 3;");
        // the inline marks: their keys, a mark typed over a selection, and a bar beside Save that teaches the keys
        rta.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> InlineMarks.forKey(e).ifPresent(mark -> {
            applyMark(rta, mark);
            e.consume();
        }));
        rta.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, e -> {
            if (e.isShortcutDown() || e.isControlDown() || e.isMetaDown()) {
                return;
            }
            jfx.incubator.scene.control.richtext.SelectionSegment selection = rta.getSelection();
            if (selection == null || selection.isCollapsed()) {
                return;
            }
            InlineMarks.forTyped(e.getCharacter()).ifPresent(mark -> {
                applyMark(rta, mark);
                e.consume();
            });
        });
        Button save = new Button("Save");
        save.setId("stampedDocumentSaveEdit");
        Button cancel = new Button("Cancel");
        cancel.setId("stampedDocumentCancelEdit");
        Label hint = new Label("Saved edits stay uncommitted in Komet until you commit the document in git.");
        hint.setFont(SMALL);
        hint.setStyle("-fx-text-fill: #5f6b7a;");
        // the marks on a row of their own, so neither they nor Save are squeezed in a narrow column
        HBox marks = new HBox(4);
        marks.getChildren().addAll(formatBar(rta));
        marks.setAlignment(Pos.CENTER_LEFT);
        // the hint gives way before the buttons do
        save.setMinWidth(Region.USE_PREF_SIZE);
        cancel.setMinWidth(Region.USE_PREF_SIZE);
        hint.setWrapText(true);
        hint.setMinWidth(0);
        HBox.setHgrow(hint, javafx.scene.layout.Priority.ALWAYS);
        HBox actions = new HBox(6, save, cancel, hint);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox buttons = new VBox(4, marks, actions);
        buttons.setId("stampedDocumentEditButtons");
        area.holder.getChildren().add(area.holder.getChildren().indexOf(rta) + 1, buttons);
        cancel.setOnAction(e -> cancelEdit(area));
        save.setOnAction(e -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < rta.getParagraphCount(); i++) {
                if (i > 0) {
                    text.append('\n');
                }
                text.append(rta.getPlainText(i));
            }
            host.saveEdit(area.block, text.toString());
        });
        rta.requestFocus();
    }

    /** The format bar: a button for each inline mark, its key in its tooltip, so the keys are learned where they are used. */
    private List<Button> formatBar(RichTextArea rta) {
        List<Button> bar = new ArrayList<>();
        for (InlineMarks mark : InlineMarks.values()) {
            Button button = new Button(switch (mark) {
                case BOLD -> "B";
                case ITALIC -> "I";
                case UNDERLINE -> "U";
                case STRIKE -> "S";
                case MONO -> "`";
                case HIGHLIGHT -> "#";
                case LINK -> "Link";
            });
            button.setId("stampedDocumentMark" + DocumentInserts.camel(mark.name().toLowerCase(java.util.Locale.ROOT)));
            button.setFont(Font.font("System", mark == InlineMarks.BOLD ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                    mark == InlineMarks.ITALIC ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, 12));
            if (mark == InlineMarks.UNDERLINE) {
                button.setUnderline(true);
            }
            button.setFocusTraversable(false);
            button.setMinWidth(26);
            button.setPadding(new Insets(2, 6, 2, 6));
            button.setTooltip(new Tooltip(mark.label() + "  " + mark.keyText()));
            button.setOnAction(e -> {
                applyMark(rta, mark);
                rta.requestFocus();
            });
            bar.add(button);
        }
        return bar;
    }

    /**
     * Toggles an inline mark on the editor's selection, within one line: the changed stretch of
     * the line is replaced and the selection set as the rule says. A selection across lines is
     * left alone, since a mark holds within a line.
     */
    static void applyMark(RichTextArea rta, InlineMarks mark) {
        jfx.incubator.scene.control.richtext.SelectionSegment selection = rta.getSelection();
        jfx.incubator.scene.control.richtext.TextPos min = selection == null ? rta.getCaretPosition() : selection.getMin();
        jfx.incubator.scene.control.richtext.TextPos max = selection == null ? min : selection.getMax();
        if (min == null || max == null || min.index() != max.index()) {
            return;
        }
        int index = min.index();
        String line = rta.getPlainText(index);
        InlineMarks.Edit edit = mark.toggle(line, min.offset(), max.offset());
        // only the stretch that changed is replaced, so the rest of the line keeps its style
        int prefix = 0;
        while (prefix < line.length() && prefix < edit.text().length() && line.charAt(prefix) == edit.text().charAt(prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < line.length() - prefix && suffix < edit.text().length() - prefix
                && line.charAt(line.length() - 1 - suffix) == edit.text().charAt(edit.text().length() - 1 - suffix)) {
            suffix++;
        }
        rta.replaceText(jfx.incubator.scene.control.richtext.TextPos.ofLeading(index, prefix),
                jfx.incubator.scene.control.richtext.TextPos.ofLeading(index, line.length() - suffix),
                edit.text().substring(prefix, edit.text().length() - suffix));
        rta.select(jfx.incubator.scene.control.richtext.TextPos.ofLeading(index, edit.start()),
                jfx.incubator.scene.control.richtext.TextPos.ofLeading(index, edit.end()));
    }

    /** Cancels the in-place edit: the block returns to its runs. */
    void cancelEdit(Area area) {
        if (area == null || editing != area) {
            return;
        }
        if (area.documentBlock != null) {
            area.documentBlock.setPinned(false);
        }
        if (area.structuredEditor != null) {
            area.holder.getChildren().remove(area.structuredEditor.node());
            area.holder.getChildren().removeIf(n -> "stampedDocumentEditButtons".equals(n.getId()));
            area.structuredEditor = null;
            editing = null;
            showStructured(area, true);
            return;
        }
        RichTextArea rta = area.area;
        rta.setEditable(false);
        rta.setDisplayCaret(false);
        rta.setId(null);
        rta.setStyle(area.block != null && area.block.kind() == Block.Kind.ADMONITION ? "-fx-background-color: #FFF8E1; -fx-background-radius: 4;"
                : area.block != null && (area.block.kind() == Block.Kind.LISTING || area.block.kind() == Block.Kind.LITERAL)
                ? "-fx-background-color: #F4F3F1; -fx-background-radius: 4;" : "");
        area.holder.getChildren().removeIf(n -> "stampedDocumentEditButtons".equals(n.getId()));
        editing = null;
        area.decorate();
        applySelection(area);
        if (area.structured != null) {
            showStructured(area, true);
        }
    }

    // ── structured blocks (IKE-Network/ike-issues#1312) ───────────────────────

    /**
     * Reads a listing block through its structured kind, when a registered kind names its style:
     * the kind's control takes the place of the block's text, which stays the area's model, so the
     * block's lines keep their identity, dates and annotations. A kind that fails to read the block
     * leaves the text in place and says why.
     */
    private void attachStructured(Area area, StoredDocument document, StoredBlock block) {
        Optional<StructuredBlockKind> kind = StructuredBlocks.kindOf(block);
        if (kind.isEmpty()) {
            return;
        }
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        Node view;
        try {
            // a write back is an uncommitted edit of the block's lines, as the structured editor's Save is
            view = kind.get().view(source, new StructuredBlockKind.Context(host.view(), host.viewProperties(), blocks, st.base(),
                    area.editable ? text -> host.saveEdit(block, text) : null));
        } catch (RuntimeException failure) {
            LOG.warn("The {} block {} could not be read; it is shown as its text", kind.get().name(), block.nid(), failure);
            host.status("The " + kind.get().name() + " block could not be read; it is shown as its text: " + failure.getMessage());
            return;
        }
        area.structured = kind.get();
        area.structuredSource = source;
        area.structuredView = view;
        view.getStyleClass().add("stamped-document-structured-block");
        view.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && area.editable && editing == null) {
                startEdit(area);
                e.consume();
            }
        });
        area.holder.getChildren().add(area.holder.getChildren().indexOf(area.area) + 1, view);
        showStructured(area, true);
    }

    /** Shows a structured block's control (true) or its text (false). */
    private static void showStructured(Area area, boolean structured) {
        area.area.setVisible(!structured);
        area.area.setManaged(!structured);
        if (area.structuredView != null) {
            area.structuredView.setVisible(structured);
            area.structuredView.setManaged(structured);
        }
    }

    private Optional<StructuredBlockKind.Editor> structuredEditor(Area area) {
        try {
            return area.structured.editor(area.structuredSource, new StructuredBlockKind.Context(host.view(), blocks, st.base()));
        } catch (RuntimeException failure) {
            host.status("The " + area.structured.name() + " editor could not open; the block is edited as its text: " + failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Opens a structured block's own editor in place of its control, with Save and Cancel beneath.
     * Save writes the editor's content between the block's fences and saves the block's text as
     * the person's pending edit, the same edit an in-place text edit saves.
     */
    private void startStructuredEdit(Area area, StructuredBlockKind.Editor editor) {
        editing = area;
        area.structuredEditor = editor;
        Node node = editor.node();
        node.setId("stampedDocumentStructuredEditor");
        area.structuredView.setVisible(false);
        area.structuredView.setManaged(false);
        int at = area.holder.getChildren().indexOf(area.structuredView) + 1;
        area.holder.getChildren().add(at, node);
        Button save = new Button("Save");
        save.setId("stampedDocumentSaveEdit");
        Button cancel = new Button("Cancel");
        cancel.setId("stampedDocumentCancelEdit");
        Label hint = new Label("Saved edits stay uncommitted in Komet until you commit the document in git.");
        hint.setFont(SMALL);
        hint.setStyle("-fx-text-fill: #5f6b7a;");
        HBox buttons = new HBox(6, save, cancel, hint);
        buttons.setId("stampedDocumentEditButtons");
        buttons.setAlignment(Pos.CENTER_LEFT);
        area.holder.getChildren().add(at + 1, buttons);
        cancel.setOnAction(e -> cancelEdit(area));
        save.setOnAction(e -> {
            String content;
            try {
                content = editor.content();
            } catch (IllegalStateException incomplete) {
                host.status(incomplete.getMessage());
                return;
            }
            host.saveEdit(area.block, area.structuredSource.withContent(content));
        });
        node.requestFocus();
    }

    /** @return the control reading a structured block, for tests and automation */
    public Optional<Node> structuredViewOfBlock(long blockNid) {
        return Optional.ofNullable(areasByBlock.get(blockNid)).map(a -> a.structuredView);
    }

    /** Cancels whatever edit is in progress. */
    void cancelEdit() {
        if (editing != null) {
            cancelEdit(editing);
        }
    }

    /** Highlights (or unhighlights) a block's row, for the restore dialog. */
    void highlight(long block, boolean on) {
        Area area = areasByBlock.get(block);
        if (area == null || area.documentBlock == null) {
            return;
        }
        Node row = area.documentBlock.stackNode();
        row.setStyle(on ? "-fx-background-color: #FFF3C4; -fx-border-color: #b26a00; -fx-border-width: 2; -fx-border-radius: 4;" : "");
        if (on) {
            row.setId("stampedDocumentRestoreHighlight");
        } else if ("stampedDocumentRestoreHighlight".equals(row.getId())) {
            row.setId(null);
        }
    }

    /** @return the model of the editable block holding the line, for tests and automation */
    Optional<StyledTextModel> modelOfBlock(long blockNid) {
        return Optional.ofNullable(areasByBlock.get(blockNid)).map(a -> a.model);
    }
}
