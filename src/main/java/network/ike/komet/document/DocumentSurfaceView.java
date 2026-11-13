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
 * the header carries the document's title and the section at the top of the viewport. A date
 * and a block's STAMPs are drawn where they differ from the block above's, and under the
 * pointer; the header keeps those of the block at the top of the viewport in sight. The
 * <em>Source view</em> is the ledger, one paragraph per line in areas of
 * {@link #LEDGER_CHUNK} lines with the number, identicon and mark as a side decorator, stacked
 * on the same surface with the margins off; a line the same STAMP introduced as the line above
 * carries a bar in the identicon's place. Its lines are not wrapped: the surface's one
 * horizontal scrollbar moves every area's text together, the side decorators standing still.
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

        /**
         * The working trees a document's picture files are read from, the one it was materialized
         * from first: the store holds a document's lines, not the pictures its image blocks name.
         *
         * @return the trees, none when the card knows of none
         */
        default List<java.nio.file.Path> workingTrees() {
            return List.of();
        }

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

        /** The reader turned a layer on or off from the surface's menu: the card does what its own layer menu does. */
        default void layerRequested(Layer layer, boolean on) { }

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
    enum Layer { DATES, STAMPS, COMMENTS, TINTS, REVIEW_QUEUE, ANNOTATION_MARKS, LEFT_OUT }

    /** The views: three on the surface's areas, and the Graph, a tree the card hosts in their place. */
    enum View { READING, SOURCE, PAGE_LAYOUT, GRAPH }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Color HOVER_HIGHLIGHT = Color.web("#FFE082");
    private static final Font SMALL = Font.font(11);
    /** Lines per ledger area: a content-height {@code RichTextArea} stops growing at the incubator's 10,000 px safeguard. */
    private static final int LEDGER_CHUNK = 200;
    /** The width of the ledger's left column: number, identicon and mark. */
    private static final double LEDGER_SIDE = 96;
    /** The padding around an area's text, a ledger chunk's too. */
    private static final Insets LEDGER_PADDING = new Insets(2, 4, 2, 4);
    /** How faint a passage a conditional leaves out is drawn. */
    private static final double LEFT_OUT_OPACITY = 0.5;

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
    /**
     * How many lists hold the item being built: 1 for an item of a list that stands in no other. An
     * item's bullet on the pages follows this, as Typst's does. An example, a sidebar, a quote, an
     * open block or an admonition around a list is no list, and adds none.
     */
    private int listDepth;

    private final Host host;
    private final DocumentSurface surface;
    private final Map<Long, Image> badges = new HashMap<>();
    private final Set<Layer> layers = EnumSet.of(Layer.DATES, Layer.STAMPS, Layer.COMMENTS, Layer.ANNOTATION_MARKS);
    /** The shown assembly's conditional directives, read when first asked for. */
    private DocumentConditions conditions;
    /** With {@link Layer#LEFT_OUT} on: each showing with the passages its conditionals leave out left in, the directives' own lines gone. */
    private final Map<DocumentAssembly.Member, StoredDocument> leftIn = new java.util.IdentityHashMap<>();
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
    /** Fold: what can be folded where the menu was asked for, from the block out to its section, and every fold at once. */
    private final Menu foldMenu = new Menu("Fold");
    /** Layers: what is drawn over the document, as the toolbar's layer menu has it. */
    private final Menu layersMenu = new Menu("Layers");
    private final Map<Layer, CheckMenuItem> layerItems = new java.util.EnumMap<>(Layer.class);
    /** By showing: each block's parent in the document the showing reads, for the folds that take in a whole table or list. */
    private final Map<DocumentAssembly.Member, Map<Long, StoredBlock>> parents = new java.util.IdentityHashMap<>();
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
    /** The date and STAMPs of what is at the top of the viewport: a block shows them only where they change, so the header keeps them in sight. */
    private final Label headerRunDate = new Label();
    private final HBox headerRunStamps = new HBox(3);
    private final HBox headerRun = new HBox(6, headerRunDate, headerRunStamps);
    private final Region headerGap = new Region();
    private final VBox header = new VBox(1, headerTitle, new HBox(headerSection, headerGap, headerRun));
    /** What {@link #headerRun} was last drawn for, so a scroll within one run draws nothing. */
    private List<Object> headerRunShown = List.of();

    private View view = View.READING;
    private DocumentAssembly assembly;
    private LineSelection selection;
    private boolean applying;
    private LineRun hovered;
    private Area editing;
    private double[] hoverAt;
    private RichTextArea hoverArea;

    /**
     * The reader's own selection: the words chosen in one text, which that text's control keeps
     * as they were chosen. While there is one, {@link #selection} is the lines it touches, which
     * is what an annotation records, and no other text draws a selection; without one the
     * selection is lines, drawn whole wherever they stand (topic {@code dev-document-edit-menu},
     * revised 2026-11-10: a block takes the focus and its words are chosen freely).
     */
    private RichTextArea exactRta;
    /** The area the exact selection's text belongs to: a block, a ledger chunk, a page line. */
    private Area exactArea;
    /** The model of the exact selection's text as it stands, since redrawing replaces it. */
    private java.util.function.Supplier<LineRunModel> exactModel;
    private jfx.incubator.scene.control.richtext.TextPos exactAnchor;
    private jfx.incubator.scene.control.richtext.TextPos exactCaret;
    /** The Reading view's block that holds the focus: the one last clicked in, marked by a bar at its left edge. */
    private Area focused;
    /** The marks offered at a selection of words: shown where the selecting gesture ended, gone at the next press. */
    private final Popup selectionBar = new Popup();
    /** The words to select again once a mark is saved and the document is read again, so a second mark needs no second selection. */
    private Reselect reselect;

    /**
     * Words to select after the document is read again.
     *
     * @param block   the block they are in
     * @param line    the stored line they are on
     * @param words   the words as drawn
     * @param nth     which occurrence of them in the line's drawn text, from 0
     * @param showing which showing of the block's file they were selected in, as its place among the assembly's members
     */
    private record Reselect(long block, long line, String words, int nth, int showing) {

        /** Whether an area is of the block where the words were selected: the same block, in the same showing of its file. */
        boolean in(Area area, DocumentAssembly assembly) {
            return area.block != null && area.block.nid() == block
                    && (assembly == null || showing < 0 || showing >= assembly.members().size() || assembly.members().get(showing) == area.member);
        }
    }

    public DocumentSurfaceView(Host host, DocumentSurface surface) {
        this.host = host;
        this.surface = surface;
        this.target = areas;
        headerTitle.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
        headerTitle.setId("stampedDocumentHeaderTitle");
        headerSection.setFont(SMALL);
        headerSection.setStyle("-fx-text-fill: #5f6b7a;");
        headerSection.setId("stampedDocumentHeaderSection");
        headerRunDate.setFont(SMALL);
        headerRunDate.setStyle("-fx-text-fill: #5f6b7a;");
        headerRun.setId("stampedDocumentHeaderRun");
        headerRun.setAlignment(Pos.CENTER_RIGHT);
        headerRunStamps.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(headerGap, javafx.scene.layout.Priority.ALWAYS);
        header.setPadding(new Insets(4, 12, 4, 12));
        header.setId("stampedDocumentHeader");
        surface.setHeader(header);
        selectionBar.setAutoHide(true);
        selectionBar.setConsumeAutoHidingEvents(false);              // the press that hides it is still the surface's
        selectionBar.setAutoFix(true);
        surface.scrollPositionProperty().addListener((obs, was, is) -> {
            hidePopover();
            selectionBar.hide();
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
        clearExact();                                             // the words were one view's text; the lines they touch carry over
        stack();
    }

    /** @return whether the layer's toggle is on */
    boolean layer(Layer layer) {
        return layers.contains(layer);
    }

    /** Turns a layer toggle on or off and redraws the layer. */
    void setLayer(Layer layer, boolean on) {
        if (on ? layers.add(layer) : layers.remove(layer)) {
            if (layer == Layer.LEFT_OUT) {
                return;                                          // it changes what is drawn, not how: the host shows the document again
            }
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
        clearExact();
        reselect = null;
        selection = wanted;
        render();
        reveal();
        host.selectionChanged();
    }

    /** Clears the selection. */
    void clearSelection() {
        clearExact();
        reselect = null;
        selection = null;
        render();
        host.selectionChanged();
    }

    /** Forgets the reader's own selection of words: the selection is lines again, drawn whole. */
    private void clearExact() {
        selectionBar.hide();
        exactRta = null;
        exactArea = null;
        exactModel = null;
        exactAnchor = null;
        exactCaret = null;
    }

    /** @return whether the reader has words selected, rather than lines */
    boolean wordsSelected() {
        return exactRta != null && exactAnchor != null && exactCaret != null && !exactAnchor.equals(exactCaret);
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

    /** @return the ordinal of a line by nid in surface order, where it is first shown, or {@code -1} */
    int ordinal(long nid) {
        return assembly == null ? -1 : assembly.ordinal(nid);
    }

    /**
     * The ordinal of a line in one showing of its file (topic {@code dev-document-front-matter}):
     * a line that an abstract and the body both show stands at two ordinals, and a selection is
     * of the place the reader pointed at.
     *
     * @param member the showing
     * @param nid    the line
     * @return the ordinal in surface order, or {@code -1} when that showing does not read the line
     */
    int ordinal(DocumentAssembly.Member member, long nid) {
        return assembly == null ? -1 : assembly.ordinal(member, nid);
    }

    /** The ordinal of a line where an area shows it: every area is of one showing, so a line shown twice is told apart by its area. */
    private int ordinal(Area area, long nid) {
        return area == null || area.member == null ? ordinal(nid) : ordinal(area.member, nid);
    }

    // ── building ──────────────────────────────────────────────────────────────

    /**
     * One area of a view: a block of the Reading view, a chunk of the Source view's ledger, a
     * line of the Page layout — or a unit of the Page layout, which is bare: the oracles place a
     * unit by its text and style alone, so a unit has no control of its own and no model, only
     * what the lines built from it need.
     */
    private final class Area {
        /** The showing the area belongs to: the same file's showing in a later reading of the document, when the area is kept. */
        DocumentAssembly.Member member;
        /** Whether the area is where its block is found by nid: the block's first showing that can be edited. */
        boolean byBlock;
        final StoredBlock block;
        final List<StoredLine> lines;
        final LineRunModel.Spec spec;
        /** The control that draws the area; {@code null} for a bare unit. */
        final RichTextArea area;
        /** The area's column in the surface, the control and what stands with it; {@code null} for a bare unit. */
        final VBox holder;
        final boolean editable;
        final String section;
        /** The caption drawn with the block, as it reads: a label, not text of the area, so Find reads it here. */
        String captionText = "";
        /**
         * With the Passages left out layer on: every line the showing holds of the block, where the area
         * draws fewer, a conditional's directives among them. An edit or a mark saves these, so nothing
         * the area does not draw is lost. Null where the area draws all the block's lines.
         */
        List<StoredLine> source;
        /** The areas one block is drawn as when a conditional parts its lines, this one among them; null for a block in one area. */
        List<Area> parts;
        /** The conditional that leaves the area's lines out of the document as it reads; null for lines that are read. */
        DocumentConditions.Condition leftOut;
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
        /** The row whose pointer the area follows, so that its repeated date and STAMPs are drawn while the pointer is beside it. */
        Node followed;
        /** The area before this one in the Reading view, whose date and STAMPs this one's are read against; null for the first. */
        Area above;
        /** The STAMPs that introduced the area's lines, oldest first, read once. */
        List<StampEntity<?>> introducers;
        /** The STAMPs' identicons in the right margin, while the STAMPs layer is on. */
        Node icons;
        /** Whether the date, and the STAMPs, are those of the block above: then they are drawn only under the pointer. */
        boolean dateRepeats;
        boolean stampsRepeat;
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
        /**
         * In the Page layout: whether the area opens a chapter — a part's or a top-level section's heading, a
         * front list's title, as the writer's level-1 headings — so its page carries no running head.
         */
        boolean chapter;
        /** In the Page layout: whether the area is the author or the date beneath the document's title. */
        boolean byline;
        /** In the Page layout: whether the area is a block's title, a unit of its own above the block. */
        boolean caption;
        /** In the Page layout: whether the area is part of the document's abstract, which the title page prints beneath the title. */
        boolean abstractPart;
        /** In the Page layout: whether the area is a front list's title or entry, generated from the header's request. */
        boolean frontList;
        /** In the Page layout: the key of the block a front list's entry names ({@link network.ike.komet.document.print.StoredDocumentModel#key}); empty for any other area. */
        String entryTarget = "";
        /** For a table row drawn from the layout's lines: its top rule above its baseline, and its height; 0 when its cells wrap. */
        double rowAscent;
        double rowHeight;
        /** In the Page layout: how far a front list's entry is indented, in ems of the body. */
        int entryDepth;
        /** In the Page layout: whether a front list's entry is of a list that has levels, whose page numbers have room for bold digits. */
        boolean entryLevelled;
        /** A page line of a front list's entry, its unit's last: the leader and page drawn after its text. */
        Region entryTail;
        /** The width of the column {@link #entryTail} draws the entry's page number in, 0 until a page asked for the line. */
        double entryColumn;
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
        /** Whether the area's text is its lines' prose read, the markup gone; false for text drawn as written. */
        boolean inline;
        /** A page line: the paragraph of its unit's text it is a line of, and where in that paragraph's drawn text it begins. */
        int sliceParagraph;
        int sliceFrom;

        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section) {
            this(member, block, lines, spec, editable, section, false);
        }

        /**
         * @param bare whether the area is a unit of the Page layout, with no control and no model: it is never shown,
         *             only placed by the oracles and read by the lines built from it
         */
        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section,
             boolean bare) {
            this(member, block, lines, spec, editable, section, bare, false);
        }

        /**
         * @param ledger whether the area is a chunk of the Source view's ledger: unwrapped, and moved sideways with
         *               every other chunk by the surface's one horizontal scrollbar
         */
        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section,
             boolean bare, boolean ledger) {
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
            area = ledger ? surface.horizontalScroll().area() : new RichTextArea();
            holder = new VBox();
            area.setEditable(false);
            area.setWrapText(!ledger);
            area.setUseContentHeight(true);
            area.setDisplayCaret(true);                           // a click leaves a caret where it landed, as in any text; it shows in the text that has the focus
            area.setHighlightCurrentParagraph(false);
            area.setContentPadding(LEDGER_PADDING);
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

        /** @return the lines an edit or a mark of the area's block saves: its source where the area draws fewer, else what it draws */
        List<StoredLine> saved() {
            return source != null ? source : lines;
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
        /** The paragraph of the unit's text the line is a line of, and where in that paragraph's drawn text it begins; 0 for a whole unit. */
        int paragraph;
        int from;
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
        show(shown, false);
    }

    /**
     * Shows a document that was read again, after an edit, a mark or a comment: what a block drew
     * the last time is kept where the block, and everything its drawing reads, is as it was, so
     * only what changed is drawn again and the rest of the view does not move. A document that is
     * not the one shown, or whose numbering, attributes, includes or references changed, is shown
     * as {@link #show} shows it.
     *
     * @param shown the host document with its children resolved
     */
    public void showChanged(DocumentAssembly shown) {
        show(shown, true);
    }

    /** What a block drew, and what its drawing read: its place among its parent's blocks and every line and block in it. */
    private record Drawn(int depth, int ordinal, String section, List<StoredLine> lines, List<StoredBlock> blocks, List<Area> areas,
                         int notesFrom, int notesTo) { }

    /** By showing and block: what each block drew in the Reading view the last time, while {@link #drawnUnder} holds. */
    private final Map<String, Drawn> drawn = new HashMap<>();
    /** What every block's drawing reads beside its own lines: see {@link #readBy}. */
    private List<Object> drawnUnder;
    /** Whether this showing keeps what the last one drew. */
    private boolean keeping;
    private final Map<DocumentAssembly.Member, String> showings = new java.util.IdentityHashMap<>();
    /** How many areas the last showing kept, and how many it drew: for the log and for tests. */
    private int keptAreas;
    private int drawnAreas;

    /** @return how many areas of the Reading view the last showing kept from the one before */
    int keptAreas() {
        return keptAreas;
    }

    /** @return how many areas of the Reading view the last showing drew */
    int drawnAreas() {
        return drawnAreas;
    }

    /**
     * What the drawing of any block may read beside the block's own lines: the files shown and
     * their order, every attribute entry, conditional and include line, the numbers and words of
     * the headings and captions, what the cross references find, and how the view is set. While
     * two readings agree in all of it, a block whose own lines are unchanged draws the same.
     */
    private List<Object> readBy(DocumentAssembly shown) {
        List<String> files = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (DocumentAssembly.Member member : shown.members()) {
            files.add(member.path() + '|' + member.tags() + '|' + member.depth());
            for (StoredLine line : member.document().lines()) {
                String text = line.text();
                if (text.startsWith(":") || text.startsWith("include::") || text.startsWith("ifdef::") || text.startsWith("ifndef::")
                        || text.startsWith("ifeval::") || text.startsWith("endif::")) {
                    entries.add(line.nid() + "|" + text);
                }
            }
        }
        List<String> numbered = new ArrayList<>();
        captions.headings().forEach(heading -> numbered.add(heading.block() + "|" + heading.level() + '|' + heading.read()));
        captions.all().forEach(caption -> numbered.add(caption.block() + "|" + caption.text()));
        List<Object> read = new ArrayList<>();
        read.add(files);
        read.add(entries);
        read.add(numbered);
        read.add(shown.targets().drawn());
        read.add(List.copyOf(shown.set()));
        read.add(st);
        read.add(st.base());
        read.add(host.view());
        read.add(past);
        return read;
    }

    /** A showing's name across readings of the document: its file, its tags, and which showing of them it is. */
    private String showing(DocumentAssembly.Member member) {
        if (showings.isEmpty()) {
            Map<String, Integer> seen = new HashMap<>();
            for (DocumentAssembly.Member each : assembly.members()) {
                String file = each.path() + '|' + each.tags();
                showings.put(each, file + '#' + seen.merge(file, 1, Integer::sum));
            }
        }
        return showings.getOrDefault(member, member.path() + '|' + member.tags());
    }

    /** Every line and block under a block, in document order, and whether any of the lines is an include. */
    private boolean under(DocumentAssembly.Member member, StoredDocument document, StoredBlock block, List<StoredLine> lines, List<StoredBlock> held) {
        boolean includes = false;
        held.add(block);
        for (long nid : block.members()) {
            Optional<StoredLine> line = document.line(nid);
            if (line.isPresent()) {
                lines.add(line.get());
                includes |= assembly.includeOf(member, nid).isPresent();
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    includes |= under(member, document, child, lines, held);
                }
            }
        }
        return includes;
    }

    private static boolean same(List<?> one, List<?> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int i = 0; i < one.size(); i++) {
            if (one.get(i) != other.get(i) && !one.get(i).equals(other.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void show(DocumentAssembly shown, boolean keep) {
        hidePopover();
        if (keep) {
            // a block that is kept must be as a reader's block is: one left open for editing, or with a note being
            // written beside it, would stay so with nothing to close it
            cancelEdit();
            closeComposer(false);
        }
        editing = null;
        boolean sameAsShown = keep && assembly != null && view == View.READING && !layers.contains(Layer.LEFT_OUT)
                && assembly.host().document().anchor().equals(shown.host().document().anchor());
        assembly = shown;
        showings.clear();
        parents.clear();
        conditions = null;
        leftIn.clear();
        printed.clear();
        pictures.clear();
        fittedColumns.clear();                                   // the document shown may have other tables, or other words in these
        // the same document's folds are carried across the rebuild by what the blocks say; another document's are what the card remembers of it
        boolean sameDocument = foldedDocument != null && foldedDocument.equals(shown.host().document().anchor());
        List<String> foldKeys = sameDocument ? folding.keys(this::foldKey) : host.rememberedFolds();
        foldedDocument = shown.host().document().anchor();
        // the same document read again, after a mark or an edit, stands where the reader had it
        DocumentSurface.Viewpoint place = sameDocument && (view == View.READING || view == View.SOURCE) ? surface.viewpoint() : null;
        // the block at the top of the view, by itself: where blocks are kept, it is found again wherever it now stands
        network.ike.komet.document.surface.DocumentBlock top = sameAsShown && place != null && place.blockIndex() >= 0
                && place.blockIndex() < surface.blockCount() ? surface.block(place.blockIndex()) : null;
        if (focused != null && focused.holder != null) {
            focused.holder.setStyle("");                              // a block that is kept does not keep the focus bar of the reading before
        }
        areas.clear();
        pages.clear();
        pageLines.clear();
        units.clear();
        areaByNode.clear();
        areasByBlock.clear();
        badges.clear();
        clearExact();
        focused = null;
        if (!sameDocument) {
            reselect = null;
        }
        if (selection != null && (selection.max() >= shown.lines().size())) {
            selection = null;
        }
        StoredDocument document = shown.host().document();
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        network.ike.komet.document.print.DocumentModel.Lists matter = network.ike.komet.document.print.StoredDocumentModel.lists(document);
        captions = Captions.of(shown, DocumentSurfaceView::isFigure, matter.sectionNumbers() ? matter.sectionLevels() : 0);
        List<Object> read = readBy(shown);
        keeping = sameAsShown && !drawn.isEmpty() && read.equals(drawnUnder);
        drawnUnder = read;
        if (!keeping) {
            drawn.clear();
            // the structured blocks of this document read each other here: trees folded in, statements named, values computed.
            // Blocks that are kept go on reading the one they were drawn in.
            blocks = new BlockContext();
        }
        keptAreas = 0;
        noteHomes = null;
        composing = null;
        readingNotes = new network.ike.komet.document.print.InlineText.Numbering();
        long t0 = System.nanoTime();
        renderMember(shown.host(), 0, "");
        long t1 = System.nanoTime();
        if (title.isEmpty() && document.root() != null) {
            title = document.root().title();
        }
        headerTitle.setText(title.isEmpty() ? StampNames.name(host.view(), dev.ikm.tinkar.common.service.PrimitiveData.nid(document.anchor())) : title);
        headerSection.setText("");
        buildSource(shown);
        long t2 = System.nanoTime();
        folding.clear();
        folding.restore(foldKeys, key -> areaIndexOf(key), this::levelAt, areas.size());
        drawLeftOut();
        drawnAreas = areas.size() - keptAreas;
        stack(keeping);
        if (keeping) {
            // what a margin shows depends on the block above it, which may be one of those drawn again
            for (Area area : areas) {
                fillMarginalia(area);
            }
        }
        LOG.info("Drew {} reading block(s) and kept {} in {} ms, the source view in {} ms, and stacked the view shown in {} ms",
                drawnAreas, keptAreas, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (System.nanoTime() - t2) / 1_000_000);
        if (place != null) {
            int at = place.blockIndex();
            if (top != null) {
                for (int i = 0; i < areas.size(); i++) {
                    if (areas.get(i).documentBlock == top) {
                        at = i;
                        break;
                    }
                }
            }
            if (at >= 0 && at < surface.blockCount()) {
                surface.restoreViewpoint(new DocumentSurface.Viewpoint(at, place.fraction(), null));
            }
        }
        selectAgain();
    }

    // ── what a conditional leaves out (the Passages left out layer) ───────────

    /** Whether the Reading view draws the passages a conditional leaves out: the layer is on, and this is not a page. */
    private boolean leftOutShown() {
        return !print && assembly != null && layers.contains(Layer.LEFT_OUT);
    }

    private DocumentConditions conditions() {
        if (conditions == null) {
            conditions = DocumentConditions.of(assembly);
        }
        return conditions;
    }

    /**
     * What a showing is drawn from: the document as it reads; or, with the Passages left out
     * layer on, the same lines with those a conditional leaves out left in, so a person can read
     * and change them. The directives' own lines are in neither: the label over a passage says
     * its condition.
     */
    private StoredDocument reads(DocumentAssembly.Member member) {
        if (!leftOutShown() || !conditions().touches(member)) {
            return assembly.read(member);
        }
        return leftIn.computeIfAbsent(member, m -> {
            Set<Long> kept = new HashSet<>();
            for (StoredLine line : m.shown().lines()) {
                if (!conditions().directive(m, line.nid())) {
                    kept.add(line.nid());
                }
            }
            StoredDocument drawn = heldTextAsProse(DocumentAssembly.project(m.document(), kept));
            return m.host() ? DocumentAssembly.withHeader(drawn) : drawn;   // an author line under the title is the header's, in this too
        });
    }

    /**
     * A conditional that holds its text on its own line, {@code ifdef::draft[Not yet reviewed.]},
     * is a paragraph of that text here whether or not its condition is met, as the document as it
     * reads has the ones that are met ({@link DocumentAssembly#read}): one left out is drawn as
     * its words, faint, under its condition, and not as a line of markup.
     */
    private static StoredDocument heldTextAsProse(StoredDocument document) {
        Map<Long, StoredBlock> blocks = new java.util.LinkedHashMap<>(document.blocks());
        boolean any = false;
        for (StoredBlock block : document.blocks().values()) {
            if (block.kind() == Block.Kind.OPAQUE && !block.members().isEmpty() && block.members().stream()
                    .allMatch(nid -> document.line(nid).map(line -> DocumentAttributes.holdsText(line.text())).orElse(false))) {
                blocks.put(block.nid(), new StoredBlock(block.nid(), Block.Kind.PARAGRAPH, block.level(), block.title(), block.parameters(),
                        block.members(), block.stamp(), block.tree()));
                any = true;
            }
        }
        if (!any) {
            return document;
        }
        StoredBlock root = document.root() == null ? null : blocks.get(document.root().nid());
        return new StoredDocument(document.anchor(), document.commit(), document.lines(), document.manifestVersions(), document.attributes(),
                root, blocks, document.references(), document.sentences());
    }

    /**
     * Whether a block is edited where this showing has it. With the Passages left out layer on,
     * a block a conditional cuts is edited as its whole source, directives and all
     * ({@link Area#source}), so only a tagged region's edge keeps it from being edited.
     */
    private boolean held(DocumentAssembly.Member member, StoredBlock block) {
        if (leftOutShown() && block != null) {
            StoredBlock shown = member.shown().blocks().get(block.nid());
            return shown != null && member.holds(shown);
        }
        return assembly.holds(member, block);
    }

    /** A block's lines split where they pass from read to left out, or from one condition to another, in order. */
    private List<List<StoredLine>> runs(DocumentAssembly.Member member, List<StoredLine> lines) {
        List<List<StoredLine>> runs = new ArrayList<>();
        DocumentConditions.Condition last = null;
        for (StoredLine line : lines) {
            DocumentConditions.Condition at = conditions().leftOut(member, line.nid()).orElse(null);
            if (runs.isEmpty() || at != last) {
                runs.add(new ArrayList<>());
            }
            runs.getLast().add(line);
            last = at;
        }
        return runs;
    }

    /**
     * The lines an edit of a block replaces, where the area draws fewer: every line the showing
     * holds of the block, its conditional directives among them. Null when the area's lines are
     * not the block's own or its structured source, which no caller that edits passes.
     */
    private List<StoredLine> sourceOf(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines) {
        StoredDocument drawn = reads(member);
        StoredDocument whole = member.shown();
        StoredBlock stored = whole.blocks().get(block.nid());
        if (stored == null) {
            return null;
        }
        List<Long> nids = lines.stream().map(StoredLine::nid).toList();
        if (nids.equals(own(drawn, block).stream().map(StoredLine::nid).toList())) {
            return own(whole, stored);
        }
        if (nids.equals(StructuredBlocks.lines(drawn, block).stream().map(StoredLine::nid).toList())) {
            return StructuredBlocks.lines(whole, stored);
        }
        return null;
    }

    private static List<StoredLine> own(StoredDocument document, StoredBlock block) {
        List<StoredLine> own = new ArrayList<>();
        for (long nid : block.members()) {
            document.line(nid).ifPresent(own::add);
        }
        return own;
    }

    /**
     * Marks an area as a passage a conditional leaves out, under a label where the passage begins.
     * It is drawn faint once the document is built ({@link #drawLeftOut}), when everything the
     * area stands with is in place: a table's rows, a picture, a title.
     */
    private void leftOut(Area area, DocumentConditions.Condition condition) {
        area.leftOut = condition;
        area.holder.getStyleClass().add("stamped-document-left-out");
        int at = target.indexOf(area);
        Area before = at > 0 ? target.get(at - 1) : null;
        if (before != null && before.leftOut == condition) {
            return;                                              // the passage goes on: its label stands over its first block
        }
        Label label = new Label(condition.label());
        label.setFont(SMALL);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #8a6d3b; -fx-font-style: italic;");
        label.setPadding(new Insets(6, 0, 0, 0));
        label.getStyleClass().add("stamped-document-left-out-label");
        if (condition.line() != null && condition.member() != null) {
            int index = assembly.indexInMember(assembly.ordinal(condition.member(), condition.line().nid()));
            label.setTooltip(new Tooltip(condition.directive() + (index < 0 ? "" : "\nline " + (index + 1)
                    + (condition.member().path().isEmpty() ? "" : " of " + condition.member().fileName()))));
        }
        area.holder.getChildren().addFirst(label);
    }

    /** Draws every area a conditional leaves out faint, each with its label, if it has one, above all it stands with. */
    private void drawLeftOut() {
        for (Area area : areas) {
            if (area.leftOut != null && area.holder != null) {
                faint(area, LEFT_OUT_OPACITY);
            }
        }
    }

    /**
     * Draws all an area stands with at a strength: its text, and a table row's grid, a picture, a
     * block's title, its references; not the label that says the passage is left out, which is
     * read at full strength and stands first, above a table's or a picture's title.
     */
    private static void faint(Area area, double opacity) {
        Node label = null;
        for (Node child : area.holder.getChildren()) {
            if (child.getStyleClass().contains("stamped-document-left-out-label")) {
                label = child;
            } else {
                child.setOpacity(opacity);
            }
        }
        if (label != null && area.holder.getChildren().indexOf(label) > 0) {
            area.holder.getChildren().remove(label);
            area.holder.getChildren().addFirst(label);
        }
    }

    /** Renders one member's blocks, or its lines as a ledger when the store holds no structure for it. */
    private void renderMember(DocumentAssembly.Member member, int depth, String section) {
        StoredDocument document = reads(member);                         // an include that names tags shows its regions alone
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
        stack(false);
    }

    /** @param keep whether the surface keeps the blocks it already shows that are still among the view's */
    private void stack(boolean keep) {
        if (view == View.PAGE_LAYOUT || view == View.GRAPH) {
            return;                                              // the card shows the pages or the graph in the surface's place
        }
        if (view == View.READING) {
            // the left margin is the dates'; the right stands while any layer that is on has something to draw in it
            rightMargin = rightMarginInUse();
            surface.setMarginTracks(layers.contains(Layer.DATES) ? 2 : 0, rightMargin ? 2 : 0);
            for (int i = 0; i < areas.size(); i++) {
                areas.get(i).above = i == 0 ? null : areas.get(i - 1);
            }
            List<network.ike.komet.document.surface.DocumentBlock> stacked = areas.stream().map(a -> a.documentBlock).toList();
            if (keep) {
                surface.updateBlocks(stacked);
            } else {
                surface.setBlocks(stacked);
            }
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
            showRunningStamps(List.of());
            return;
        }
        DocumentSurface.Viewpoint at = surface.viewpoint();
        int top = at == null ? -1 : at.blockIndex();
        if (top < 0 || top >= shown.size()) {
            return;
        }
        Area topArea = shown.get(top);
        headerSection.setText(view == View.SOURCE ? "Source" : topArea.section);
        if (view == View.READING) {
            showRunningStamps(introducers(topArea));
        } else if (view == View.SOURCE && !topArea.lines.isEmpty()) {
            // the line at the top of the viewport, by how far down its chunk the viewport's top is
            StoredLine line = topArea.lines.get(Math.clamp((int) (at.fraction() * topArea.lines.size()), 0, topArea.lines.size() - 1));
            showRunningStamps(committedStamp(line) == 0 ? List.of() : List.of(line.introduced()));
        } else {
            showRunningStamps(List.of());
        }
        host.showingMember(topArea.member);
    }

    /** How many STAMPs the header draws before it counts the rest. */
    private static final int HEADER_STAMPS = 4;

    /** Draws the date and STAMPs of what is at the top of the viewport at the header's right, as a block's margins would. */
    private void showRunningStamps(List<StampEntity<?>> stamps) {
        boolean dates = layers.contains(Layer.DATES);
        boolean icons = layers.contains(Layer.STAMPS);
        List<Object> showing = new ArrayList<>(List.of(dates, icons));
        stamps.forEach(stamp -> showing.add(stamp.nid()));
        if (showing.equals(headerRunShown)) {
            return;
        }
        headerRunShown = showing;
        headerRunDate.setText(dates ? dateOf(stamps) : "");
        headerRunStamps.getChildren().clear();
        if (!icons || stamps.isEmpty()) {
            return;
        }
        headerRunStamps.getChildren().add(new dev.ikm.komet.framework.controls.StampSigil(14));
        for (StampEntity<?> stamp : stamps.subList(0, Math.min(HEADER_STAMPS, stamps.size()))) {
            Node icon = identicon(stamp, 14, null);
            Tooltip.install(icon, new Tooltip(StampNames.name(host.view(), stamp.authorNid()) + " · " + DAY.format(Instant.ofEpochMilli(stamp.time()))
                    + host.summaries().summary(stamp.nid()).map(summary -> "\n" + summary).orElse("")
                    + "\nIntroduced what is at the top of the view"));
            headerRunStamps.getChildren().add(icon);
        }
        if (stamps.size() > HEADER_STAMPS) {
            Label more = new Label("+" + (stamps.size() - HEADER_STAMPS));
            more.setFont(SMALL);
            more.setStyle("-fx-text-fill: #5f6b7a;");
            headerRunStamps.getChildren().add(more);
        }
    }

    /**
     * The ledger: one paragraph per line, in areas of at most {@link #LEDGER_CHUNK} lines so no
     * area outgrows the safeguard. The lines run in surface order, so the host's ledger lists
     * each resolved include line followed by the child's own ledger; the areas break where the
     * member changes, each member's lines numbered as its file is, with the path above a
     * child's ledger and again where the host's resumes. Each area says how tall it will stand,
     * its lines times the font's line, so the stack is its full height before any area is built
     * and a long scroll lands where it was aimed.
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
            Area source = new Area(member, null, chunk, new LineRunModel.Spec(paragraphs), false, "Source", false, true);
            source.numbers = numbers;
            source.area.setId(chunkIndex == 0 ? "stampedDocumentSource" : "stampedDocumentSource" + chunkIndex);
            source.area.setLeftDecorator(new LedgerDecorator(source));
            // every line of a ledger is one unwrapped row of the one font, so how tall a chunk stands is known before it is built
            double room = chunk.size() * ledgerPitch() + ledgerChrome;
            if (previous != null && previous != member) {
                Label path = new Label((member.host() || member.depth() < previous.depth() ? "↩ " : "↳ ") + member.path()
                        + (member.tags().isEmpty() ? "" : " · " + member.tags()));
                path.setFont(Font.font("Menlo", 11));
                path.setStyle("-fx-text-fill: #5f6b7a;");
                path.setPadding(new Insets(6, 0, 2, 8));
                path.getStyleClass().add("stamped-document-member-path");
                source.holder.getChildren().add(path);
                room += ledgerPathHeight();
                path.heightProperty().addListener((obs, was, is) -> {
                    if (is.doubleValue() > 0) {
                        ledgerPath = is.doubleValue();
                    }
                });
            }
            source.holder.getChildren().add(source.area);
            source.holder.setMinWidth(0);
            // What a line measures to and what an area makes of it can differ by a fraction, and the area's border is
            // the stylesheet's to say: a chunk that has been laid out says both, for the chunks stacked after it.
            int count = chunk.size();
            RichTextArea laid = source.area;
            laid.heightProperty().addListener((obs, was, is) -> {
                double chrome = laid.snappedTopInset() + laid.snappedBottomInset() + LEDGER_PADDING.getTop() + LEDGER_PADDING.getBottom();
                double line = (is.doubleValue() - chrome) / count;
                if (count >= 10 && Math.abs(line - ledgerPitch()) < 1) {
                    ledgerPitch = line;
                    ledgerChrome = chrome;
                }
            });
            source.documentBlock = DocumentBlock.prose(source.holder, source.area, source.model,
                    chunk.stream().map(StoredLine::text).collect(Collectors.joining("\n"))).withEstimatedHeight(room);
            sources.add(source);
            previous = member;
            chunkIndex++;
            at = end;
        }
    }

    /** How tall one line of the ledger stands: the mono font's line, as a text area rounds it. */
    private static double ledgerPitch() {
        if (ledgerPitch == 0) {
            StyleAttributeMap mono = Styles.SCREEN.mono();
            ledgerPitch = lineHeight(Font.font(mono.getFontFamily(), mono.getFontSize()));
        }
        return ledgerPitch;
    }

    /** How tall the path over a member's ledger stands: its line and the padding around it, until one has been laid out. */
    private static double ledgerPathHeight() {
        if (ledgerPath == 0) {
            ledgerPath = lineHeight(Font.font("Menlo", 11)) + 8;
        }
        return ledgerPath;
    }

    /** A line of text in the font, rounded up to the half pixel a region is sized to on a dense display. */
    private static double lineHeight(Font font) {
        javafx.scene.text.Text line = new javafx.scene.text.Text("Mg");
        line.setFont(font);
        return Math.ceil(line.getLayoutBounds().getHeight() * 2) / 2;
    }

    /** One ledger line's height, the padding and border around a chunk's lines, and a path's height: measured, then as laid out. */
    private static double ledgerPitch;
    private static double ledgerChrome = LEDGER_PADDING.getTop() + LEDGER_PADDING.getBottom();
    private static double ledgerPath;

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
            return LEDGER_SIDE;
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
            long stamp = line == null ? 0 : committedStamp(line);
            if (stamp != 0 && (paragraph == 0 || committedStamp(chunk.lines.get(paragraph - 1)) != stamp)) {
                // the identicon stands where the STAMP changes, and at the head of every chunk
                row.getChildren().add(identicon(line.introduced(), LEDGER_IDENTICON, null));
            } else if (stamp != 0) {
                row.getChildren().add(runBar(line.introduced()));
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

    /** The STAMP that introduced a committed line, or 0 for a line no commit holds yet. */
    private static long committedStamp(StoredLine line) {
        return line.introduced() == null || line.introduced().time() == Long.MAX_VALUE ? 0 : line.introduced().nid();
    }

    /**
     * What stands in the identicon's place on a line the same STAMP introduced as the line above:
     * a bar in the STAMP's hue, the row's height, so a run of lines reads as one mark from its
     * identicon down. A column of the same identicon says nothing after its first.
     */
    private static Region runBar(StampEntity<?> stamp) {
        Color colour = runColour(stamp);
        Region bar = new Region();
        bar.setMinWidth(LEDGER_IDENTICON);
        bar.setPrefWidth(LEDGER_IDENTICON);
        bar.setMaxWidth(LEDGER_IDENTICON);
        bar.setMaxHeight(Double.MAX_VALUE);
        bar.getStyleClass().add("stamped-document-run");
        double inset = (LEDGER_IDENTICON - RUN_BAR_WIDTH) / 2;
        bar.setStyle(String.format(java.util.Locale.ROOT, "-fx-background-color: #%02x%02x%02x; -fx-background-insets: 0 %.1f 0 %.1f;",
                Math.round(colour.getRed() * 255), Math.round(colour.getGreen() * 255), Math.round(colour.getBlue() * 255), inset, inset));
        return bar;
    }

    /** How wide the bar down a run of lines is. */
    private static final double RUN_BAR_WIDTH = 3;

    /** The colour of a STAMP's run bar: its tint's hue, deep enough to read as a line. */
    static Color runColour(StampEntity<?> stamp) {
        return Color.hsb(tint(stamp).getHue(), 0.45, 0.72);
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
        if (print || target != areas) {
            draw(member, block, depth, ordinal, section);
            return;
        }
        // The Reading view remembers what each block drew. A block whose lines, and the lines of every block in it, are
        // as they were the last time, in the same place among its parent's blocks, is not drawn again: its areas are
        // put back as they stand. A block that holds an include is drawn each time, since what it shows is another file's.
        List<StoredLine> lines = new ArrayList<>();
        List<StoredBlock> held = new ArrayList<>();
        boolean includes = under(member, reads(member), block, lines, held);
        String key = showing(member) + ':' + block.nid();
        String under = section == null ? "" : section;
        Drawn last = drawn.get(key);
        if (keeping && last != null && !includes && last.depth() == depth && last.ordinal() == ordinal && last.section().equals(under)
                && last.notesFrom() == readingNotes.last() && same(last.lines(), lines) && same(last.blocks(), held)) {
            for (Area area : last.areas()) {
                area.member = member;
                target.add(area);
                if (area.byBlock) {
                    areasByBlock.putIfAbsent(area.block.nid(), area);
                }
            }
            readingNotes.countedTo(last.notesTo());
            keptAreas += last.areas().size();
            return;
        }
        int from = target.size();
        int notesFrom = readingNotes.last();
        int names = readingNotes.names();
        draw(member, block, depth, ordinal, section);
        if (includes || readingNotes.names() != names) {
            drawn.remove(key);                                    // a note counted by name is read by the blocks after it
        } else {
            drawn.put(key, new Drawn(depth, ordinal, under, lines, held, List.copyOf(target.subList(from, target.size())), notesFrom, readingNotes.last()));
        }
    }

    private void draw(DocumentAssembly.Member member, StoredBlock block, int depth, int ordinal, String section) {
        StoredDocument document = reads(member);
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
                            title.chapter = !member.host() && offsetOf(member) <= 1;
                        }
                        if (member.host()) {
                            byline(member, block, heading, depth, under);
                        }
                    } else {
                        addProse(member, block, heading, paragraphs(heading, " ", st.title(),
                                t -> captions.heading(block, t.startsWith("= ") ? t.substring(2) : t)), depth, false, under);
                    }
                }
                // the host's abstract, written before its first section: it stands before the lists, not where it was written
                StoredBlock summary = member.host() ? network.ike.komet.document.print.StoredDocumentModel.abstractOf(document).orElse(null) : null;
                if (print && member.host()) {
                    // on the title page, beneath the title and the author, then the lists the header asks for, before the
                    // body, as the print model has them
                    if (summary != null) {
                        abstractAreas(member, summary, depth, under);
                    }
                    frontLists(member, document);
                }
                List<StoredLine> front = own.stream().filter(l -> !l.text().startsWith("= "))
                        .filter(l -> !print || assembly.includeOf(member, l.nid()).map(DocumentAssembly.Include::resolved).orElse(false)).toList();
                Area frontArea = null;
                if (!front.isEmpty()) {
                    frontArea = add(member, block, front, paragraphs(front, "", st.monoGrey(), null), depth, false, under);
                }
                if (!print && member.host()) {
                    // the abstract beneath the header's own lines, then the lists the header asks for, before the body
                    Area abstractEnd = summary == null ? null : abstractAreas(member, summary, depth, under);
                    Area beneath = abstractEnd != null ? abstractEnd : frontArea != null ? frontArea : target.isEmpty() ? null : target.getLast();
                    if (beneath != null) {
                        generatedLists(beneath, document);
                    }
                }
                renderChildren(member, summary == null ? children : children.stream().filter(child -> child.nid() != summary.nid()).toList(), depth, under);
            }
            case SECTION -> {
                List<StoredLine> heading = own.stream().filter(l -> l.text().startsWith("=")).toList();
                // The print model offsets an included document's headings by its include's leveloffset.
                StyleAttributeMap style = st.heading(print ? block.level() + offsetOf(member) : block.level());
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                Area area = addProse(member, block, heading.isEmpty() ? own : heading, paragraphs(heading.isEmpty() ? own : heading, " ", style,
                        t -> captions.heading(block, t.replaceFirst("^=+\\s*", ""))), depth, false, block.title());
                if (print && area != null) {
                    area.chapter = block.level() + offsetOf(member) <= 1;     // a part or a top-level section, as the writer's level 1
                }
                renderChildren(member, children, depth, block.title());
            }
            case PARAGRAPH -> {
                if (print && printable(own).stream().map(l -> printProse(l.text())).allMatch(String::isBlank)) {
                    return;                                      // the print model drops a paragraph of index terms alone
                }
                if (print && nesting == 0 && !printable(own).isEmpty()) {
                    modelOrdinal++;
                }
                // a comment among a paragraph's lines, an end::brief[] under its last words, draws nothing, as it prints nothing
                LineRunModel.Spec spec = paragraphs(own, " ", st.prose(), print ? DocumentSurfaceView::printProse
                        : t -> t.strip().startsWith("//") ? "" : readingProse(t));
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
                        attachStructured(area, reads(member), block);
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
                listDepth++;
                int n = 0;
                for (StoredBlock item : children) {
                    render(member, item, depth + 1, ++n, section);
                }
                listDepth--;
                nesting--;
            }
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> {
                String marker = block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY ? "▪" : ordinalMarker(own, ordinal, print ? Math.max(0, listDepth - 1) : -1);
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
                    // The table's own lines, its attributes, its title and its delimiters, are its source, which the Source view
                    // shows: the Reading view draws the table, and its caption above it. The caption stands on the title's line,
                    // whose text is not drawn; an untitled table has nothing above its rows.
                    if (!block.title().isEmpty()) {
                        List<StoredLine> titled = own.stream().filter(l -> BLOCK_TITLE_LINE.matcher(l.text().strip()).matches()).limit(1).toList();
                        List<StoredLine> carrier = titled.isEmpty() ? own.subList(0, 1) : titled;
                        Area caption = add(member, block, carrier, paragraphs(carrier, "", st.smallGrey(), null), depth, false, section);
                        if (caption != null) {
                            if (caption.area != null) {
                                caption.area.setVisible(false);
                                caption.area.setManaged(false);
                            }
                            title(caption, block);
                        }
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
                // in the Reading view a picture that is found is shown, and the lines that name it stand beneath it small and
                // grey; one that is not found is its lines, marked as a picture's
                Optional<javafx.scene.image.Image> shown = print ? Optional.<javafx.scene.image.Image>empty()
                        : pictureOf(document, block).flatMap(this::pictureImage);
                Area area = add(member, block, own, shown.isPresent() ? paragraphs(own, "", st.smallGrey(), null)
                        : paragraphs(own, " ", st.prose(), t -> "🖼 " + t), depth, false, section);
                if (area != null && !print) {
                    title(area, block);
                    // the picture itself above the lines that name it, no wider than the text beside it
                    pictureOf(document, block).ifPresent(picture -> shown.ifPresent(image -> {
                        javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
                        view.setPreserveRatio(true);
                        view.setSmooth(true);
                        view.setFitWidth(Math.min(picture.width() / DocumentPictures.PIXEL, 520));
                        view.getStyleClass().add("stamped-document-picture");
                        area.holder.getChildren().add(Math.max(0, area.holder.getChildren().indexOf(area.area)), view);
                    }));
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
     * The host's abstract ({@code dev-document-front-matter}), drawn as its block is drawn anywhere,
     * in the place the front of a document gives it: on the pages straight after the title and the
     * author, so the title page prints it; in the Reading view beneath the header's lines and
     * above the lists.
     *
     * @return the last area it drew, or null when it drew none
     */
    private Area abstractAreas(DocumentAssembly.Member host, StoredBlock block, int depth, String section) {
        int before = target.size();
        render(host, block, depth, 1, section);
        for (Area area : target.subList(before, target.size())) {
            area.abstractPart = true;
        }
        return target.size() > before ? target.getLast() : null;
    }

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
    /**
     * The author and the date beneath the document's title, as the print model has them
     * ({@link network.ike.komet.document.print.StoredDocumentModel#byline}): each a unit of its own
     * in the title's block, standing on the header line that names it, or on the title's line
     * when the date is the newest commit's.
     */
    private void byline(DocumentAssembly.Member host, StoredBlock block, List<StoredLine> titleLines, int depth, String section) {
        StoredDocument document = host.document();
        network.ike.komet.document.print.DocumentModel.Byline byline = network.ike.komet.document.print.StoredDocumentModel.byline(
                document, assembly.members().stream().map(DocumentAssembly.Member::document).toList());
        network.ike.komet.document.print.StoredDocumentModel.Header header = network.ike.komet.document.print.StoredDocumentModel.header(document);
        String[] texts = {byline.author(), byline.date()};
        StoredLine[] carriers = {header.authorLine(), header.dateLine()};
        double[] scales = {network.ike.komet.document.print.TypstWriter.AUTHOR_SCALE, network.ike.komet.document.print.TypstWriter.DATE_SCALE};
        for (int i = 0; i < texts.length; i++) {
            String text = texts[i];
            if (text.isEmpty()) {
                continue;
            }
            List<StoredLine> carrier = carriers[i] != null ? List.of(carriers[i]) : titleLines;
            StyleAttributeMap style = StyleAttributeMap.builder().merge(st.prose()).setFontSize(st.base() * scales[i]).setBold(false)
                    .setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build();
            Area area = addArea(host, block, carrier, paragraphs(carrier, " ", style, t -> text), depth, false, section, true);
            area.byline = true;
            area.splittable = false;
        }
    }

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
            title.chapter = true;
            // the outermost entries of a list that has levels are its chief ones, set bold as the Typst writer's entry block sets them
            boolean levelled = network.ike.komet.document.print.FrontLists.levelled(
                    list.entries().stream().map(network.ike.komet.document.print.DocumentModel.Entry::depth).toList());
            for (network.ike.komet.document.print.DocumentModel.Entry entry : list.entries()) {
                boolean chief = levelled && entry.depth() <= 0;
                Object[] target = targets.get(entry.target());
                DocumentAssembly.Member member = target == null ? host : (DocumentAssembly.Member) target[0];
                StoredBlock block = target == null ? document.root() : assembly.read(member).blocks().get((Long) target[1]);
                List<StoredLine> carrier = block == null ? hostTitle : titleLine(assembly.read(member), block, hostTitle);
                Area area = addArea(member, block == null ? document.root() : block, carrier,
                        paragraphs(carrier, " ", chief ? StyleAttributeMap.builder().merge(st.ragged()).setBold(true).build() : st.ragged(), t -> entry.text()),
                        0, false, list.title(), true);
                area.heading = false;                             // it names a section; it is not one
                area.splittable = false;
                area.frontList = true;
                area.entryTarget = entry.target();
                area.entryDepth = Math.max(0, entry.depth());
                area.entryLevelled = levelled;
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

    /**
     * Follows a link into the document to the block it names: on the pages to the page the block
     * begins on, in the Reading view to the block.
     *
     * @param key the block's key, as the link is written with it
     */
    void follow(String key) {
        if (view == View.PAGE_LAYOUT) {
            revealTarget(key);
        } else if (assembly != null) {
            assembly.targets().block(key).ifPresent(this::revealBlock);
        }
    }

    /** Scrolls the Reading view to a block's area, opening any fold that hides it. */
    private void revealBlock(long blockNid) {
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            if (area.block != null && area.block.nid() == blockNid) {
                unfoldToShow(i);
                surface.revealBlock(i);
                settleOn(i, 3);
                return;
            }
        }
    }

    // ── find ──────────────────────────────────────────────────────────────────

    /**
     * Every place a text stands in the view shown, in reading order, whatever its case: in the
     * text of the Reading view's blocks and in the captions of its figures and tables, or in the
     * lines of the Source view. The pages and the graph are not read: they are other drawings of
     * the same lines, and a match is shown where the lines are text.
     *
     * @param text the text to find; blank finds nothing
     * @return the matches, each naming its block by its place in the view
     */
    List<Match> find(String text) {
        if (text == null || text.isBlank() || view == View.PAGE_LAYOUT || view == View.GRAPH) {
            return List.of();
        }
        List<Match> found = new ArrayList<>();
        if (view != View.READING) {
            for (DocumentSurface.DocumentMatch match : surface.findAll(text)) {
                found.add(new Match(match.blockIndex(), -1, match.paragraphIndex(), match.start(), match.end(), match.selectable()));
            }
            return found;
        }
        Map<Integer, List<DocumentSurface.DocumentMatch>> inText = new HashMap<>();
        for (DocumentSurface.DocumentMatch match : surface.findAll(text)) {
            inText.computeIfAbsent(match.blockIndex(), i -> new ArrayList<>()).add(match);
        }
        String needle = text.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            List<DocumentSurface.DocumentMatch> own = inText.getOrDefault(i, List.of());
            int before = found.size();
            if (!area.cells.isEmpty()) {
                // a table row is drawn as its cells, each a text of its own: the words are found where they are drawn
                for (int c = 0; c < area.cells.size(); c++) {
                    LineRunModel words = area.cells.get(c).model;
                    if (words != null) {
                        for (dev.ikm.komet.markdown.richtext.RichTextSearch.Match match : dev.ikm.komet.markdown.richtext.RichTextSearch.findAll(words, text)) {
                            found.add(new Match(i, c, match.paragraphIndex(), match.start(), match.end(), true));
                        }
                    }
                }
            } else if (area.area != null && !area.area.isVisible()) {
                // a block drawn as a figure, its source out of sight: found once, and shown by the figure
                if (!own.isEmpty()) {
                    found.add(new Match(i, -1, -1, -1, -1, false));
                }
            } else {
                for (DocumentSurface.DocumentMatch match : own) {
                    found.add(new Match(i, -1, match.paragraphIndex(), match.start(), match.end(), match.selectable()));
                }
            }
            // a caption is a label; where the block's own text holds its words, they are found there already
            if (found.size() == before && area.captionText.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                found.add(new Match(i, -1, -1, -1, -1, false));
            }
        }
        return found;
    }

    /**
     * One place a text was found in the view shown.
     *
     * @param blockIndex     the block, by its place in the view
     * @param cell           which cell of a table row the words are in, or -1 for the block's own text
     * @param paragraphIndex the paragraph of that text, or -1 for a match shown by its block alone
     * @param start          where the words begin in the paragraph
     * @param end            where they end
     * @param selectable     whether the words can be selected where they are drawn: a caption's and a figure's cannot
     */
    record Match(int blockIndex, int cell, int paragraphIndex, int start, int end, boolean selectable) {

        /** @return whether this match stands before another in the view */
        boolean before(Match other) {
            if (blockIndex != other.blockIndex) {
                return blockIndex < other.blockIndex;
            }
            if (cell != other.cell) {
                return cell < other.cell;
            }
            if (paragraphIndex != other.paragraphIndex) {
                return paragraphIndex < other.paragraphIndex;
            }
            return start < other.start;
        }

        private DocumentSurface.DocumentMatch inBlock() {
            return new DocumentSurface.DocumentMatch(blockIndex, cell < 0 ? paragraphIndex : -1, cell < 0 ? start : -1, cell < 0 ? end : -1,
                    selectable && cell < 0);
        }
    }

    /** The text of a table cell that holds the match last shown, whose selection is taken away with the match. */
    private RichTextArea matchedCell;

    /**
     * Goes to a match: opens any fold that hides its block, scrolls to it, and selects the words
     * where they are drawn, in the block's text or in a table cell's. A caption's match, and a
     * figure's, is shown by its block alone.
     *
     * @param match a match of {@link #find}
     */
    void showMatch(Match match) {
        if (view == View.PAGE_LAYOUT || view == View.GRAPH) {
            return;
        }
        if (view == View.READING && match.blockIndex() < areas.size()) {
            unfoldToShow(match.blockIndex());
        }
        clearMatch();
        DocumentSurface.DocumentMatch inBlock = match.inBlock();
        surface.select(inBlock);
        if (match.cell() >= 0 && view == View.READING && match.blockIndex() < areas.size() && match.cell() < areas.get(match.blockIndex()).cells.size()) {
            RichTextArea cell = areas.get(match.blockIndex()).cells.get(match.cell()).rta;
            if (inWindow(cell)) {
                cell.select(jfx.incubator.scene.control.richtext.TextPos.ofLeading(match.paragraphIndex(), match.start()),
                        jfx.incubator.scene.control.richtext.TextPos.ofLeading(match.paragraphIndex(), match.end()));
                matchedCell = cell;
            }
            settleOn(match.blockIndex(), 3);
        } else if (!match.selectable()) {
            settleOn(match.blockIndex(), 3);
        } else {
            // far from where the reader was, the blocks on the way are built as they are scrolled past and move the
            // target down the stack: the match is shown again once they have their heights
            javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(() -> surface.select(inBlock)));
        }
    }

    /**
     * Replaces the words of a match with other words, in the source of the lines they are drawn
     * from, and saves the block as an uncommitted edit, as a mark over selected words is saved:
     * the match is selected as a reader would select it, its words are found in the source, and
     * the source between them is replaced. A match that cannot be placed exactly, as one inside a
     * caption, a heading or a table cell, or one that takes half of a marked phrase, is left as
     * it stands and the status says why.
     *
     * @param match a match of {@link #find}
     * @param with  the words to put in its place; empty takes the match away
     * @return whether an edit was begun: the host reads the document again when it is saved
     */
    boolean replaceMatch(Match match, String with) {
        if (view == View.PAGE_LAYOUT || view == View.GRAPH) {
            host.status("Find and replace work in the Reading and Source views.");
            return false;
        }
        if (match.cell() >= 0) {
            host.status("A table cell's words are changed in the table's source: Edit block opens it.");
            return false;
        }
        if (!match.selectable()) {
            host.status("A caption's words, and a figure's, are changed in the block's source: Edit block opens it.");
            return false;
        }
        if (view == View.READING && match.blockIndex() < areas.size()) {
            unfoldToShow(match.blockIndex());
        }
        clearMatch();
        clearExact();
        surface.select(match.inBlock());                          // the control's selection is taken as the reader's own
        return replaceSelection(with == null ? "" : with);
    }

    /** Writes other words in place of the words selected; see {@link #replaceMatch} and {@link #markSelection}. */
    private boolean replaceSelection(String with) {
        selectionBar.hide();
        if (editing != null) {
            host.status("Finish the edit in progress first: Save or Cancel it.");
            return false;
        }
        if (past) {
            host.status("The document is read as of a past time, which is not edited: move the view's time to the latest to change it.");
            return false;
        }
        if (!wordsSelected() || exactModel == null || exactModel.get() == null) {
            host.status("The words found could not be selected to replace them.");
            return false;
        }
        LineRunModel model = exactModel.get();
        boolean forward = exactAnchor.compareTo(exactCaret) <= 0;
        jfx.incubator.scene.control.richtext.TextPos min = forward ? exactAnchor : exactCaret;
        jfx.incubator.scene.control.richtext.TextPos max = forward ? exactCaret : exactAnchor;
        Optional<LineRun> firstRun = model.runAt(min);
        Optional<LineRun> lastRun = max.offset() > 0 ? model.runAt(jfx.incubator.scene.control.richtext.TextPos.ofLeading(max.index(), max.offset() - 1))
                : model.runs(max.index() - 1).isEmpty() ? model.runAt(max) : Optional.of(model.runs(max.index() - 1).getLast());
        Optional<Area> home = firstRun.flatMap(run -> blockAreaOf(run.nid()));
        boolean inline = exactArea != null && exactArea.inline;
        // prose read with its markup gone is placed in the source as a mark's words are; text drawn as written is its source
        if (firstRun.isEmpty() || lastRun.isEmpty() || home.isEmpty() || inline && !marksIn(home.get().block)) {
            host.status("Words are replaced in a paragraph, a list item or an admonition, or in the Source view: elsewhere, Edit block opens the source.");
            return false;
        }
        LineRun first = firstRun.get();
        LineRun last = lastRun.get();
        Area block = home.get();
        List<StoredLine> stored = block.saved();
        List<String> lines = new ArrayList<>(stored.stream().map(StoredLine::text).toList());
        int a = -1;
        int b = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).nid() == first.nid()) {
                a = i;
            }
            if (stored.get(i).nid() == last.nid()) {
                b = i;
            }
        }
        if (a < 0 || b < a) {
            host.status("The words found run from one block into another: they are replaced in each block's source with Edit block.");
            return false;
        }
        Share from = share(exactArea, model, first);
        Share to = share(exactArea, model, last);
        int start = from.base() + Math.max(0, min.offset() - first.start());
        int end = to.base() + (last.paragraph() == max.index() ? Math.max(0, max.offset() - last.start()) : last.end() - last.start());
        if (a == b) {
            Optional<SourceSpan.Located> at = SourceSpan.locate(lines.get(a), inline, from.text(), start, end);
            if (at.isEmpty()) {
                host.status("Those words cannot be replaced where they stand: they hold something other than words, or half of a marked phrase. Edit block opens the source.");
                return false;
            }
            String line = lines.get(a);
            lines.set(a, line.substring(0, at.get().from()) + with + line.substring(at.get().to()));
        } else {
            // words that run over lines of the source: the lines become one, the words between its two ends replaced
            java.util.OptionalInt opens = SourceSpan.start(lines.get(a), inline, from.text(), start);
            java.util.OptionalInt closes = SourceSpan.end(lines.get(b), inline, to.text(), end);
            if (opens.isEmpty() || closes.isEmpty()) {
                host.status("Those words cannot be replaced where they stand: a marked phrase begins or ends among them. Edit block opens the source.");
                return false;
            }
            lines.set(a, lines.get(a).substring(0, opens.getAsInt()) + with + lines.get(b).substring(closes.getAsInt()));
            for (int i = b; i > a; i--) {
                lines.remove(i);
            }
        }
        reselect = null;
        clearExact();
        host.saveEdit(block.block, String.join("\n", lines));
        return true;
    }

    /** @return where the reader is in the view shown, to be put back with {@link #restore}; null before any layout */
    DocumentSurface.Viewpoint place() {
        return view == View.READING || view == View.SOURCE ? surface.viewpoint() : null;
    }

    /** Puts the reader back where {@link #place} found them. */
    void restore(DocumentSurface.Viewpoint place) {
        if (place != null && (view == View.READING || view == View.SOURCE) && place.blockIndex() >= 0 && place.blockIndex() < surface.blockCount()) {
            surface.restoreViewpoint(new DocumentSurface.Viewpoint(place.blockIndex(), place.fraction(), null));
        }
    }

    /** Takes the selection of the last match shown away. */
    void clearMatch() {
        surface.clearSelection();
        if (matchedCell != null) {
            if (inWindow(matchedCell)) {
                matchedCell.clearSelection();
            }
            matchedCell = null;
        }
    }

    /** @return the place in the view of the block at the top of the viewport, or zero before any layout */
    int topBlock() {
        DocumentSurface.Viewpoint at = surface.viewpoint();
        return at == null ? 0 : Math.max(0, at.blockIndex());
    }

    /**
     * Scrolls to a block again once the layout has settled, and again after that. Far from where
     * the reader was, the blocks around a target are built only when it is scrolled to, and their
     * heights then move it down the stack: one scroll lands short, on what stood there before.
     *
     * @param index the block's index among the Reading view's blocks
     * @param times how many more times to scroll
     */
    private void settleOn(int index, int times) {
        if (times > 0) {
            surface.restoreViewpoint(new DocumentSurface.Viewpoint(index, 0, null), () -> settleOn(index, times - 1));
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

            @Override
            public List<Integer> columns(StoredBlock table, List<List<String>> rows, List<List<Integer>> spans, int headerRows, List<Integer> widths) {
                return fittedColumns(table, rows, spans, headerRows, widths);
            }

            @Override
            public Optional<network.ike.komet.document.print.DocumentModel.Picture> picture(network.ike.komet.document.DecisionFigure figure) {
                return Optional.ofNullable(pictures.get(figure));
            }
        };
    }

    /** {@code image::target[attributes]}, the line of an image block that names its file. */
    private static final Pattern IMAGE_LINE = Pattern.compile("^image::([^\\[]*)\\[(.*)\\]$");

    /** The picture each figure of an image block is drawn from, by the figure itself: two blocks may show the same file. */
    private final Map<network.ike.komet.document.DecisionFigure, network.ike.komet.document.print.DocumentModel.Picture> pictures = new java.util.IdentityHashMap<>();
    /** The images of the picture files drawn so far, by file; empty for a file that could not be drawn. */
    private final Map<java.nio.file.Path, Optional<javafx.scene.image.Image>> pictureImages = new HashMap<>();

    /**
     * The picture file an image block names, looked for in the working trees the card knows
     * ({@link DocumentPictures#file}), its target read with the attributes in force at its line.
     *
     * @param document the block's document, as a showing reads it
     * @param block    an image block
     * @return the picture, or empty when no tree holds the file or its size cannot be read
     */
    private Optional<network.ike.komet.document.print.DocumentModel.Picture> pictureOf(StoredDocument document, StoredBlock block) {
        if (assembly == null || block.kind() != Block.Kind.IMAGE) {
            return Optional.empty();
        }
        for (long nid : block.members()) {
            Optional<StoredLine> line = document.line(nid);
            java.util.regex.Matcher image = line.isEmpty() ? null : IMAGE_LINE.matcher(line.get().text().strip());
            if (image != null && image.matches()) {
                // the file the line is in, by the line: the view may be reading a document made for a layer, which is no showing's own
                DocumentAssembly.Member member = assembly.memberOf(nid).orElse(null);
                if (member == null) {
                    return Optional.empty();
                }
                String target = assembly.reading(member).text(image.group(1), nid);
                String asked = image.group(2);
                return DocumentPictures.file(host.workingTrees(), assembly.host().path(), member.path(), target).flatMap(DocumentPictures::picture)
                        .map(picture -> DocumentPictures.sized(picture, asked, fitWidth));
            }
        }
        return Optional.empty();
    }

    /** The image of a picture file, drawn once and kept. */
    private Optional<javafx.scene.image.Image> pictureImage(network.ike.komet.document.print.DocumentModel.Picture picture) {
        return pictureImages.computeIfAbsent(picture.file(), file -> network.ike.komet.document.print.PictureRaster.image(picture));
    }

    /** The theme and the measure the tables' columns are fitted for; none until a page is to be set. */
    private network.ike.komet.document.surface.print.settings.DocumentTheme fitTheme;
    private double fitWidth;
    /** Each table's printed column widths, by the table's block: the model and the pages set a table in the same ones. */
    private final Map<Long, List<Integer>> fittedColumns = new HashMap<>();

    /**
     * Says what the document is printed on, before its print model or its pages are asked for: a
     * table's columns are fitted to that measure in that theme's fonts.
     *
     * @param theme        the print theme
     * @param contentWidth the measure, in points
     */
    public void printOn(network.ike.komet.document.surface.print.settings.DocumentTheme theme, double contentWidth) {
        if (theme != fitTheme || contentWidth != fitWidth) {
            fittedColumns.clear();
            fitTheme = theme;
            fitWidth = contentWidth;
        }
    }

    /**
     * The widths a table's columns are printed with ({@link TableColumnFit#printed}): each column
     * at least as wide as its longest word that no line can break, measured in the page's fonts,
     * a header's bold. A word breaks where Typst breaks one, after a hyphen or a slash, so each
     * piece between is measured. A cell over several columns has their room together and widens
     * none of them.
     */
    private List<Integer> fittedColumns(StoredBlock table, List<List<String>> rows, List<List<Integer>> spans, int headerRows, List<Integer> widths) {
        if (fitTheme == null || fitWidth <= 0 || widths.size() < 2) {
            return widths;
        }
        return fittedColumns.computeIfAbsent(table.nid(), nid -> {
            network.ike.komet.document.print.DocumentFonts.loadForJavaFx();
            Styles page = Styles.page(fitTheme);
            double[] least = new double[widths.size()];
            for (int r = 0; r < rows.size(); r++) {
                List<Integer> own = r < spans.size() && spans.get(r).size() == rows.get(r).size() ? spans.get(r) : null;
                StyleAttributeMap style = r < headerRows ? page.headerCell() : page.cell();
                int column = 0;
                for (int k = 0; k < rows.get(r).size() && column < least.length; k++) {
                    int span = own == null ? 1 : Math.max(1, own.get(k));
                    if (span == 1) {
                        network.ike.komet.document.print.InlineText read = network.ike.komet.document.print.InlineText.of(rows.get(r).get(k));
                        least[column] = Math.max(least[column], widestWord(read, style, r < headerRows));
                    }
                    column += span;
                }
            }
            return TableColumnFit.printed(widths, least, fitWidth);
        });
    }

    /** The room a cell's widest unbreakable piece of a word needs: its width, the cell's inset either side, and a point to spare. */
    private double widestWord(network.ike.komet.document.print.InlineText read, StyleAttributeMap style, boolean header) {
        String text = read.text();
        double widest = 0;
        int at = 0;
        while (at < text.length()) {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
            int end = at;
            while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end > at) {
                int start = at;
                boolean bold = header;
                boolean mono = false;
                for (network.ike.komet.document.print.InlineText.Run run : read.runs()) {
                    if (run.from() < end && run.to() > start) {
                        bold |= run.bold();
                        mono |= run.mono();
                    }
                }
                StyleAttributeMap in = mono ? StyleAttributeMap.builder().merge(style).setFontFamily(network.ike.komet.document.print.DocumentFonts.MONO)
                        .setFontSize(style.getFontSize() * network.ike.komet.document.print.TypstWriter.RAW_SCALE).build() : style;
                String word = text.substring(at, end);
                int from = 0;
                for (int cut : network.ike.komet.document.print.TypstOracle.ownCuts(word)) {
                    widest = Math.max(widest, textWidth(in, word.substring(from, cut), bold));
                    from = cut;
                }
                widest = Math.max(widest, textWidth(in, word.substring(from), bold));
            }
            at = end;
        }
        return widest == 0 ? 0 : widest + 2 * network.ike.komet.document.print.TypstOracle.TABLE_INSET + 1;
    }

    /**
     * What a block is drawn as on a page, the model and the Page layout alike.
     *
     * @param document the block's document
     * @param block    the block
     * @return its figures in order, none when it prints as text
     */
    public List<network.ike.komet.document.DecisionFigure> printFigures(StoredDocument document, StoredBlock block) {
        if (block.kind() == Block.Kind.IMAGE) {
            // an image block whose file is found is a figure of the file's size, its caption beneath it as a drawn
            // block's is; one whose file is not found prints as its name, as before
            Optional<network.ike.komet.document.print.DocumentModel.Picture> picture = pictureOf(document, block);
            if (picture.isEmpty()) {
                return List.of();
            }
            // an image written with no title has its target for one, which is no caption
            boolean titled = block.members().stream().map(document::line).flatMap(Optional::stream).map(line -> IMAGE_LINE.matcher(line.text().strip()))
                    .filter(java.util.regex.Matcher::matches).noneMatch(image -> image.group(1).strip().equals(block.title()));
            String caption = titled ? captions.text(block) : "";
            return printed.computeIfAbsent("image\n" + picture.get().file() + "\n" + caption + "\n" + block.nid(), key -> {
                network.ike.komet.document.DecisionFigure bare = new network.ike.komet.document.DecisionFigure(
                        picture.get().width(), picture.get().height(), "image", List.of());
                String read = network.ike.komet.document.print.InlineText.of(network.ike.komet.document.print.InlineText.withoutNotes(caption)).text();
                network.ike.komet.document.DecisionFigure figure = read.isEmpty() ? bare
                        : network.ike.komet.document.DecisionFigureText.inSerif(() -> network.ike.komet.document.TextFigure.captioned(read, bare));
                pictures.put(figure, picture.get());
                return List.of(figure);
            });
        }
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

    /**
     * The item's marker: its number, or the bullet — on pages Typst's bullet for the item's depth among
     * lists, 0 for an item of a list that stands in no other; on screen, a negative depth, a plain one.
     */
    private static String ordinalMarker(List<StoredLine> own, int ordinal, int depth) {
        String bullet = depth < 0 ? "•" : network.ike.komet.document.print.ModelUnits.bullet(depth);
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
                assembly.read(member).line(nid).ifPresent(line -> {
                    body.add(line);
                    blockOf.put(line.nid(), row);
                });
            }
        }
        List<List<network.ike.komet.document.print.TableCells.Cell>> rows =
                network.ike.komet.document.print.TableCells.rows(body.stream().map(StoredLine::text).toList(), table.parameters());
        // set in the widths the print model sets it in: a column too narrow for its longest word is widened once, for both
        DocumentAttributes in = assembly.attributes(member, table);
        List<List<String>> texts = rows.stream().map(row -> {
            DocumentReading.Block references = assembly.reading(member).block();
            return row.stream().map(cell -> references.line(cell.text(), in)).toList();
        }).toList();
        List<List<Integer>> spans = rows.stream().anyMatch(row -> row.stream().anyMatch(cell -> cell.span() > 1))
                ? rows.stream().map(network.ike.komet.document.print.TableCells::spans).toList() : List.of();
        List<Integer> widths = fittedColumns(table, texts, spans, header && !rows.isEmpty() ? 1 : 0,
                network.ike.komet.document.print.StoredDocumentModel.widths(table.parameters(), network.ike.komet.document.print.TableCells.columns(rows)));
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
            int notesBefore = printNotes.last();
            Area area = add(member, blockOf.get(lines.getFirst().nid()), lines, spec, depth, false, section);
            if (area != null) {
                // a row's cells are as wide as the columns they take: a cell over every column is the table's width
                area.columns = network.ike.komet.document.print.TableCells.weights(network.ike.komet.document.print.TableCells.spans(row), widths);
                area.tableFirst = r == 1;
                area.cellSpecs = cellSpecs(row, body, row.size(), head ? st.headerCell() : st.cell(), assembly.reading(member),
                        network.ike.komet.document.print.InlineText.Numbering.after(notesBefore));
            }
        }
    }

    /**
     * A table row's cells as a page prints them, each the lines its words come from: one spec per
     * column of the table, {@code null} where the row's cell is empty or the row has fewer cells
     * than the table.
     */
    private static List<LineRunModel.Spec> cellSpecs(List<network.ike.komet.document.print.TableCells.Cell> row, List<StoredLine> body, int columns, StyleAttributeMap style,
                                                    DocumentReading reading,
                                                    network.ike.komet.document.print.InlineText.Numbering notes) {
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
            // keeps the number the row gave it: the cells are counted on from where the row began, in the row's order
            LineRunModel.Spec raw = new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(own, " ", style)));
            specs.add(raw.inlined(notes, reading).spec());
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
                reads(member).line(nid).ifPresent(line -> {
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
            List<LineRunModel.FootNote> rowNotes = new ArrayList<>();
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
            }
            List<TableGrid.Placed> placed = new ArrayList<>();
            int at = 0;                                          // the column the next cell begins in
            for (int k = 0; at < widths.size(); k++) {
                // a cell takes the columns it spans; a row short of cells is filled out with empty ones
                network.ike.komet.document.print.TableCells.Cell written = k < row.size() ? row.get(k) : null;
                int span = written == null ? 1 : Math.clamp(written.span(), 1, widths.size() - at);
                int c = at;
                VBox box = new VBox();
                box.getStyleClass().add("doc-table-cell");
                if (c + span == widths.size()) {
                    box.getStyleClass().add("doc-table-last-cell");
                }
                box.setFillWidth(true);
                box.setMinWidth(0);
                List<LineRunModel.Line> own = written == null ? List.<LineRunModel.Line>of() : written.pieces().stream()
                        .filter(piece -> !piece.text().isEmpty())
                        .map(piece -> new LineRunModel.Line(body.get(piece.line()), piece.text())).toList();
                if (!own.isEmpty()) {
                    // a cell is prose: its marks read as a paragraph's are, and a concept it names drawn as a chip that folds to the column
                    LineRunModel.Inlined inlined = onScreen(new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(own, " ", style))))
                            .inlined(readingNotes, assembly.reading(member));
                    LineRunModel.Spec read = inlined.spec();
                    rowNotes.addAll(inlined.notes());
                    CellText cell = new CellText(area, read.withInlines(text -> conceptChips(text, true)));
                    cells.add(cell);
                    box.getChildren().add(cell.rta);
                    String words = read.paragraphs().getFirst().lines().stream().map(LineRunModel.Line::text)
                            .collect(java.util.stream.Collectors.joining(" ")).strip();
                    if (span == 1) {
                        // a cell over several columns has their room together: its words widen no one column
                        fit.words(c, words, head);
                    }
                    if (head) {
                        fit.heads(c, span, words);
                    }
                }
                grid.add(box, c, 0, span, 1);
                placed.add(new TableGrid.Placed(box, c, span, own.isEmpty()));
                at += span;
            }
            area.cells = List.copyOf(cells);
            area.area.setVisible(false);
            area.area.setManaged(false);
            area.holder.getChildren().add(area.holder.getChildren().indexOf(area.area) + 1, grid);
            // a cell's note stands beneath its row, as a paragraph's stands beneath the paragraph
            int beneath = area.holder.getChildren().indexOf(grid) + 1;
            for (LineRunModel.FootNote foot : rowNotes) {
                area.holder.getChildren().add(beneath++, noteLabel(foot.note()));
            }
            if (!fit.rows.isEmpty()) {
                // the rows are blocks of the surface, which stands its blocks apart: a row after the first closes that space
                VBox.setMargin(grid, new Insets(-DocumentSurface.BLOCK_SPACING, 0, 0, 0));
            }
            fit.add(grid, head, placed);
        }
    }

    /**
     * The rows of one table in the Reading view, which share their columns' widths: each column
     * its weight's share of the row, but no narrower than its widest word, nor than holds its
     * longest cell in three lines, up to the width a line of running text is read in, as
     * {@link TableColumnFit} shares them. A row too narrow for that is not drawn as a grid, whose columns would stand a few
     * letters wide and a page tall: each row's cells stand one under another, each with the name
     * its column's header gives it, and the header row, whose words they are, is not drawn. Fitted
     * again whenever the rows' width changes.
     */
    private final class TableGrid {
        /** What a cell's text stands in from its column's edges: the text's own padding and the rule beside it. */
        private static final double CELL_INSET = 20;
        /** The least width a cell of running text is read in, in ems: narrower, and a sentence stands two words to a line. */
        private static final double READ_EMS = 9;
        /** The size a stacked cell's name is drawn in, which the stylesheet gives it, and the room about its words. */
        private static final double NAME_SIZE = 11;
        private static final double NAME_INSET = 14;

        /** A cell's box in its row: the column it begins in, the columns it takes, and whether it holds no words. */
        record Placed(VBox box, int column, int span, boolean empty) {
        }

        /** A row of the table: its grid, whether it is the header, its cells, and the names drawn over them when stacked. */
        private record Row(javafx.scene.layout.GridPane grid, boolean head, List<Placed> cells, Map<Placed, Label> names) {
        }

        final List<Integer> weights;
        final List<Row> rows = new ArrayList<>();
        /** By column: its words, each with whether a header cell draws it bold. */
        final List<Map<String, Boolean>> words = new ArrayList<>();
        /** By column: the longest text one cell of it holds. */
        final String[] longest;
        /** By column: the longest text one cell of it holds under the header. */
        final String[] longestValue;
        /** By column: the words of its header cell, empty when the table has no header or the cell none. */
        final String[] heads;
        /** Measures a word in the font the cells draw it in, which the scene's stylesheets name. */
        final javafx.scene.text.Text probe = new javafx.scene.text.Text();
        /** Each column's least width, once measured in a scene: what keeps its words whole and its text read. */
        double[] least;
        /** By column, once measured: the width its header's words take as a stacked cell's name. */
        double[] nameWide;
        /** By column, once measured: the width its longest cell under the header takes on one line. */
        double[] valueWide;
        double fitted = -1;
        /** Whether the rows stand as stacks of cells, not as a grid; {@code null} before the first fit. */
        Boolean stacked;

        TableGrid(List<Integer> weights) {
            this.weights = weights;
            for (int c = 0; c < weights.size(); c++) {
                words.add(new HashMap<>());
            }
            longest = new String[weights.size()];
            heads = new String[weights.size()];
            longestValue = new String[weights.size()];
            java.util.Arrays.fill(longestValue, "");
            java.util.Arrays.fill(longest, "");
            java.util.Arrays.fill(heads, "");
            probe.setManaged(false);
            probe.setVisible(false);
        }

        void words(int column, String text, boolean bold) {
            for (String word : text.strip().split("\\s+")) {
                if (!word.isEmpty()) {
                    words.get(column).merge(word, bold, Boolean::logicalOr);
                }
            }
            if (text.length() > longest[column].length()) {
                longest[column] = text;
            }
            if (!bold && text.length() > longestValue[column].length()) {
                longestValue[column] = text;
            }
        }

        /** The header's words for the columns one of its cells takes. */
        void heads(int column, int span, String text) {
            for (int c = column; c < column + span && c < heads.length; c++) {
                heads[c] = text;
            }
        }

        void add(javafx.scene.layout.GridPane grid, boolean head, List<Placed> cells) {
            if (rows.isEmpty()) {
                grid.getChildren().add(probe);
            }
            // every row asks: the header's own grid is out of the layout while the rows are stacked, and hears of no width
            grid.widthProperty().addListener((obs, was, is) -> fit(grid));
            grid.sceneProperty().addListener((obs, was, is) -> fit(grid));
            rows.add(new Row(grid, head, List.copyOf(cells), new HashMap<>()));
        }

        /** Whether the rows stand as stacks of cells; for tests. */
        boolean stacked() {
            return Boolean.TRUE.equals(stacked);
        }

        private void fit(javafx.scene.layout.GridPane asked) {
            if (!asked.isManaged() || asked.getScene() == null) {
                return;
            }
            fit(asked.getWidth() - asked.snappedLeftInset() - asked.snappedRightInset());
        }

        /** Fits the rows to a width: as a grid when each column can be read at its share, and else as stacks. */
        void fit(double width) {
            javafx.scene.layout.GridPane first = rows.getFirst().grid();
            if (width <= 0 || Math.abs(width - fitted) < 0.5) {
                return;
            }
            if (least == null) {
                if (first.getScene() == null) {
                    return;
                }
                double size = st.cell().getFontSize();
                least = new double[weights.size()];
                nameWide = new double[weights.size()];
                valueWide = new double[weights.size()];
                for (int c = 0; c < least.length; c++) {
                    for (Map.Entry<String, Boolean> word : words.get(c).entrySet()) {
                        least[c] = Math.max(least[c], Math.ceil(measured(word.getKey(), size, word.getValue())) + CELL_INSET);
                    }
                    // running text wants a line it is read in; a cell of a few words wants three lines, and a word or a number itself
                    double read = Math.min(measured(longest[c], size, false) / 3, READ_EMS * size);
                    least[c] = Math.max(least[c], Math.ceil(read) + CELL_INSET);
                    nameWide[c] = Math.ceil(measured(heads[c], NAME_SIZE, true)) + NAME_INSET;
                    valueWide[c] = Math.ceil(measured(longestValue[c], size, false)) + CELL_INSET;
                }
                probe.setText("");
            }
            fitted = width;
            boolean stack = !TableColumnFit.holds(least, width);
            if (stack) {
                stack(width);
            } else {
                grid(width);
            }
            stacked = stack;
        }

        private double measured(String text, double size, boolean bold) {
            if (text.isEmpty()) {
                return 0;
            }
            probe.setText(text);
            probe.setStyle("-fx-font-size: " + size + "px;" + (bold ? " -fx-font-weight: bold;" : ""));
            probe.applyCss();
            return probe.getLayoutBounds().getWidth();
        }

        /** The rows as a grid: every cell in its columns, at the widths the columns share. */
        private void grid(double width) {
            double[] share = TableColumnFit.shares(weights, least, width);
            for (Row row : rows) {
                javafx.scene.layout.GridPane grid = row.grid();
                if (Boolean.TRUE.equals(stacked)) {
                    grid.getStyleClass().remove("doc-table-stacked");
                    grid.getChildren().removeAll(row.names().values());
                    grid.setVisible(true);
                    grid.setManaged(true);
                    grid.getColumnConstraints().clear();
                    for (int c = 0; c < share.length; c++) {
                        javafx.scene.layout.ColumnConstraints column = new javafx.scene.layout.ColumnConstraints();
                        column.setFillWidth(true);
                        grid.getColumnConstraints().add(column);
                    }
                    for (Placed cell : row.cells()) {
                        cell.box().setVisible(true);
                        cell.box().setManaged(true);
                        javafx.scene.layout.GridPane.setConstraints(cell.box(), cell.column(), 0, cell.span(), 1);
                    }
                }
                for (int c = 0; c < share.length; c++) {
                    grid.getColumnConstraints().get(c).setPercentWidth(100.0 * share[c] / width);
                }
            }
        }

        /**
         * The rows as stacks: a row's cells one under another, each named by its column's header.
         * A column whose longest cell stands on one line beside its name is drawn so in every
         * row, the names in a column of their own so the cells line up; a column of longer cells
         * has its name over the cell, which takes the row's whole width. A cell with no words is
         * not drawn, and neither is the header row when there are rows to carry its names.
         */
        private void stack(double width) {
            boolean named = rows.size() > 1 && rows.getFirst().head() && java.util.Arrays.stream(heads).anyMatch(name -> !name.isEmpty());
            boolean[] beside = new boolean[weights.size()];
            double names = 0;
            if (named) {
                // twice: a name too wide for some column's cells is no measure of the names' column
                for (int pass = 0; pass < 2; pass++) {
                    double widest = 0;
                    for (int c = 0; c < beside.length; c++) {
                        beside[c] = !heads[c].isEmpty() && Math.max(names, nameWide[c]) + valueWide[c] <= width;
                        if (beside[c]) {
                            widest = Math.max(widest, nameWide[c]);
                        }
                    }
                    names = widest;
                }
            }
            boolean two = names > 0;
            for (Row row : rows) {
                javafx.scene.layout.GridPane grid = row.grid();
                if (!grid.getStyleClass().contains("doc-table-stacked")) {
                    grid.getStyleClass().add("doc-table-stacked");
                }
                grid.getChildren().removeAll(row.names().values());
                boolean hidden = named && row.head();
                grid.setVisible(!hidden);
                grid.setManaged(!hidden);
                grid.getColumnConstraints().clear();
                if (two) {
                    javafx.scene.layout.ColumnConstraints name = new javafx.scene.layout.ColumnConstraints();
                    name.setPercentWidth(100.0 * names / width);
                    javafx.scene.layout.ColumnConstraints cell = new javafx.scene.layout.ColumnConstraints();
                    cell.setPercentWidth(100.0 - 100.0 * names / width);
                    cell.setFillWidth(true);
                    grid.getColumnConstraints().addAll(name, cell);
                } else {
                    javafx.scene.layout.ColumnConstraints whole = new javafx.scene.layout.ColumnConstraints();
                    whole.setPercentWidth(100);
                    whole.setFillWidth(true);
                    grid.getColumnConstraints().add(whole);
                }
                int whole = two ? 2 : 1;
                int line = 0;
                for (Placed cell : row.cells()) {
                    boolean shown = !cell.empty();
                    cell.box().setVisible(shown);
                    cell.box().setManaged(shown);
                    if (!shown) {
                        // out of the layout, and in a place the stack has
                        javafx.scene.layout.GridPane.setConstraints(cell.box(), 0, 0, 1, 1);
                        continue;
                    }
                    // a cell over every column is the row's own words, and has no one column's name
                    String name = !named || row.head() || cell.span() >= weights.size() ? "" : name(cell);
                    if (name.isEmpty()) {
                        javafx.scene.layout.GridPane.setConstraints(cell.box(), 0, line++, whole, 1);
                        continue;
                    }
                    Label label = row.names().computeIfAbsent(cell, made -> {
                        Label one = new Label(name);
                        one.getStyleClass().add("doc-table-cell-name");
                        one.setWrapText(true);
                        one.setMinWidth(0);
                        one.setMaxWidth(Double.MAX_VALUE);
                        return one;
                    });
                    boolean aside = cell.span() == 1 && beside[cell.column()];
                    label.pseudoClassStateChanged(NAME_BESIDE, aside);
                    if (aside) {
                        grid.add(label, 0, line, 1, 1);
                        javafx.scene.layout.GridPane.setValignment(label, javafx.geometry.VPos.TOP);
                        javafx.scene.layout.GridPane.setConstraints(cell.box(), 1, line++, 1, 1);
                    } else {
                        grid.add(label, 0, line++, whole, 1);
                        javafx.scene.layout.GridPane.setConstraints(cell.box(), 0, line++, whole, 1);
                    }
                }
            }
        }

        /** The name a cell stands under: its column's header, or its columns' headers where they differ. */
        private String name(Placed cell) {
            List<String> names = new ArrayList<>();
            for (int c = cell.column(); c < cell.column() + cell.span() && c < heads.length; c++) {
                if (!heads[c].isEmpty() && !names.contains(heads[c])) {
                    names.add(heads[c]);
                }
            }
            return String.join(" · ", names);
        }
    }

    /** On a stacked table cell's name: it stands beside its cell, not over it. */
    private static final javafx.css.PseudoClass NAME_BESIDE = javafx.css.PseudoClass.getPseudoClass("beside");

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
            // a caption is a label, in one weight: its words without their markup. A note keeps its mark in the
            // caption and its words beneath it, numbered with the document's notes as the page numbers it.
            network.ike.komet.document.print.InlineText read = network.ike.komet.document.print.InlineText.of(
                    assembly.reading(area.member).text(captions.text(block), assembly.attributes(area.member, block)), readingNotes);
            Label title = new Label(read.text());
            area.captionText = read.text();
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
            int beneath = area.holder.getChildren().indexOf(title) + 1;
            for (network.ike.komet.document.print.InlineText.Note note : read.notes()) {
                if (!note.repeat()) {
                    area.holder.getChildren().add(beneath++, noteLabel(note));
                }
            }
        }
    }

    /** A footnote's words as the Reading view shows them beneath what they annotate: the number, then the text. */
    private static Label noteLabel(network.ike.komet.document.print.InlineText.Note foot) {
        Label note = new Label(foot.drawn().text());
        note.setFont(SMALL);
        note.setWrapText(true);
        note.setMaxWidth(Double.MAX_VALUE);
        note.setStyle("-fx-text-fill: #4b5563;");
        note.getStyleClass().add("stamped-document-footnote");
        return note;
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
        List<StoredLine> lines = StructuredBlocks.lines(assembly.read(member), block);
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
        if (!leftOutShown() || block == null || lines.isEmpty() || !conditions().touches(member)) {
            return added(member, block, lines, spec, depth, editable, section, prose);
        }
        // With the Passages left out layer on, a block's lines that a conditional leaves out are drawn apart from those
        // that are read, each passage under its condition; an edit of any part is of the whole block's source.
        List<StoredLine> source = editable ? sourceOf(member, block, lines) : null;
        List<List<StoredLine>> runs = runs(member, lines);
        if (runs.size() == 1) {
            Area area = added(member, block, lines, spec, depth, editable && source != null, section, prose);
            if (area != null && source != null && source.size() != lines.size()) {
                area.source = source;
            }
            return area;
        }
        List<Area> parts = new ArrayList<>();
        for (List<StoredLine> run : runs) {
            Area area = added(member, block, run, restrict(spec, run), depth, editable && source != null, section, prose);
            if (area != null) {
                area.source = source;
                area.parts = parts;
                parts.add(area);
            }
        }
        return parts.isEmpty() ? null : parts.getFirst();
    }

    private Area added(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section,
                       boolean prose) {
        // the pages read every block the print model calls prose; the Reading view the blocks its caller says are
        boolean inline = print ? printsInline(block) : prose;
        List<DocumentAssembly.Part> parts = assembly.parts(member, lines);
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
                if (target.size() > before && !print && target.get(before).holder.getChildren().stream()
                        .noneMatch(n -> n.getStyleClass().contains("stamped-document-member-path"))) {     // an area kept from the last reading has it
                    // the file, the region of it this include shows, and any tag the include names that the file does not have
                    Label path = new Label("↳ " + child.path() + (child.tags().isEmpty() ? "" : " · " + child.tags())
                            + (part.include().note().isEmpty() ? "" : " — " + part.include().note()));
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
        LineRunModel.Inlined inlined = inline ? (print ? read.inlined(printNotes, assembly.reading(member)) : onScreen(read).inlined(readingNotes, assembly.reading(member))) : null;
        // on screen a concept the prose names is drawn as its chip; a page prints its name, as the PDF does
        LineRunModel.Spec spec = inlined == null ? read : print ? inlined.spec() : inlined.spec().withInlines(text -> conceptChips(text, false));
        // a unit of the Page layout is bare: the oracles place it by its text and style, and its lines are built from it
        // a block that a tagged region cuts is read there and edited where its file is shown whole: an edit is of the whole block
        Area area = new Area(member, block, lines, spec, editable && !past && held(member, block), section, print);
        area.inline = inlined != null;
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
            areasByBlock.putIfAbsent(block.nid(), area);              // a block shown twice is found by nid where it is first shown
            area.byBlock = true;
        }
        area.holder.getChildren().add(area.area);
        if (leftOutShown() && !lines.isEmpty()) {
            conditions().leftOut(member, lines.getFirst().nid()).ifPresent(condition -> leftOut(area, condition));
        }
        if (inlined != null) {
            // a footnote's mark stands in the text; its words stand beneath the block, where the page has them at its foot
            for (LineRunModel.FootNote foot : inlined.notes()) {
                area.holder.getChildren().add(noteLabel(foot.note()));
            }
        }
        // a footnote read into the text stands beneath it with its number, so the references line need not say it again
        Node references = references(lines, inlined == null || inlined.notes().isEmpty());
        if (references != null) {
            area.holder.getChildren().add(references);
        }
        for (StoredLine line : lines) {
            assembly.includeOf(member, line.nid()).filter(i -> !i.resolved()).ifPresent(include -> {
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
        area.date.getStyleClass().add("stamped-document-date");
        area.marginalia = new HBox(3);
        area.marginalia.setAlignment(Pos.TOP_LEFT);
        area.marginalia.getStyleClass().add("stamped-document-marginalia");
        area.notes.getStyleClass().add("stamped-document-notes");
        VBox right = new VBox(4, area.marginalia, area.notes);
        right.setFillWidth(true);
        // A figure in the body may reach into the right margin, and the margin is drawn over it. The margin
        // takes the pointer on its marks only, so what the figure has there can be clicked.
        for (javafx.scene.layout.Pane column : List.of(right, area.marginalia, area.notes)) {
            column.setPickOnBounds(false);
        }
        Node left = dateBox(area);
        // a date or a STAMP the block above already shows is drawn while the pointer is on the block or its margins
        for (Node under : List.of(area.holder, left, right)) {
            under.hoverProperty().addListener(obs -> showRepeats(area));
        }
        // the margins' images and the fold mark's binding wait until the surface builds the block near the viewport
        area.documentBlock = DocumentBlock.prose(area.holder, area.area, area.model, spec.lines().stream().map(StoredLine::text).collect(Collectors.joining("\n")))
                .withMarginalia(left, right)
                .withEstimatedHeight(estimatedHeight(area))
                .whenRealized(() -> {
                    fillMarginalia(area);
                    bindFoldMark(area);
                    followRow(area);
                    // a right-click beside the text, in either margin, asks for the menu as one in the text does
                    Node row = area.documentBlock == null ? null : area.documentBlock.stackNode();
                    if (row != null) {
                        row.setOnContextMenuRequested(e -> {
                            showMarginMenu(area, row, e.getScreenX(), e.getScreenY());
                            e.consume();
                        });
                    }
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

    /** The first and last Reading-view areas the selection touches, or null when it touches none. */
    private int[] selectedAreas() {
        if (selection == null || view != View.READING) {
            return null;
        }
        int first = -1;
        int last = -1;
        for (int i = 0; i < areas.size(); i++) {
            Area area = areas.get(i);
            if (area.model != null && area.model.runs().stream().anyMatch(r -> selection.contains(ordinal(area, r.nid())))) {
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
        readAllAgainstAbove();
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
        listDepth = 0;
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
        // what the host's header asks for on its title page, read where its attributes have all been set, over the theme's
        DocumentAttributes set = assembly == null || assembly.host().document().lines().isEmpty() ? DocumentAttributes.intrinsic()
                : assembly.attributes(assembly.host(), assembly.host().document().lines().getLast().nid());
        network.ike.komet.document.surface.print.settings.TitleSpacing titleSpacing =
                network.ike.komet.document.surface.print.settings.TitleSpacing.asked(set::value).over(theme.titleSpacing());
        network.ike.komet.document.print.LayoutOracle.Kind previous = null;
        boolean afterFront = false;                               // the unit after the front lists, or after an abstract with no list, opens the body's first page
        boolean bodyOpened = false;
        for (int i = 0; i < units.size(); i++) {
            Area area = units.get(i);
            Block.Kind blockKind = printKind(area.block);
            if (area.note > 0) {
                // No spacing and no place in the flow: an oracle sets it at the foot of the page its mark lands on.
                out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal, network.ike.komet.document.print.LayoutOracle.Kind.FOOTNOTE, area.spec.plainText(),
                        base * network.ike.komet.document.print.LayoutOracle.FOOTNOTE_SCALE, false, 0, 0, 0, false).withInline(area.spec.runs(), List.of(), area.note));
                continue;
            }
            // Each front list opens a page, and so does the body after the last of them or after an abstract with no list, as
            // the writer's page breaks set it. The abstract opens none: it stands on the title page.
            boolean front = area.frontList || area.abstractPart;
            boolean opensPage = area.frontList ? area.heading : !area.abstractPart && afterFront;
            // The body's first unit opens it: its page is page 1, and the pages before it are the front matter's (Folios).
            boolean opensBody = !front && afterFront && !bodyOpened;
            bodyOpened |= opensBody;
            afterFront = front;
            if (area.drawn != null) {
                double[] spacing = network.ike.komet.document.print.UnitSpacing.of(
                        network.ike.komet.document.print.LayoutOracle.Kind.FIGURE, previous, base, base);
                out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal,
                        network.ike.komet.document.print.LayoutOracle.Kind.FIGURE, network.ike.komet.document.print.LayoutOracle.FIGURE_WORD,
                        base, false, 0, spacing[0], spacing[1], false, area.drawn.width(), area.drawn.height()).withPageBefore(opensPage).withOpensBody(opensBody));
                previous = network.ike.komet.document.print.LayoutOracle.Kind.FIGURE;
                continue;
            }
            network.ike.komet.document.print.LayoutOracle.Kind kind = area.byline ? network.ike.komet.document.print.LayoutOracle.Kind.BYLINE
                    : area.frontList
                    ? (area.heading ? network.ike.komet.document.print.LayoutOracle.Kind.HEADING : network.ike.komet.document.print.LayoutOracle.Kind.ENTRY)
                    : area.caption ? network.ike.komet.document.print.LayoutOracle.Kind.OTHER : area.heading
                    // the host's title is the document's; an included child's title is a heading, as the writer has it
                    ? (blockKind == Block.Kind.DOCUMENT && area.member.host() ? network.ike.komet.document.print.LayoutOracle.Kind.TITLE
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
            double[] spacing = kind == network.ike.komet.document.print.LayoutOracle.Kind.ENTRY
                    ? network.ike.komet.document.print.UnitSpacing.ofEntry(bold, previous, base)   // the one bold kind of entry is a chief one
                    : network.ike.komet.document.print.UnitSpacing.of(kind, previous, size, base,
                    kind == network.ike.komet.document.print.LayoutOracle.Kind.OTHER && blockKind == Block.Kind.TABLE);
            double above = spacing[0];
            double below = spacing[1];
            // the title page's white space, as the writer's title block has it: the theme's, with what the header asks for
            if (kind == network.ike.komet.document.print.LayoutOracle.Kind.BYLINE) {
                above = previous == network.ike.komet.document.print.LayoutOracle.Kind.BYLINE ? titleSpacing.revision().in(base) : titleSpacing.authors().in(base);
            }
            if ((kind == network.ike.komet.document.print.LayoutOracle.Kind.TITLE || kind == network.ike.komet.document.print.LayoutOracle.Kind.BYLINE)
                    && i + 1 < units.size() && units.get(i + 1).abstractPart) {
                below = titleSpacing.abstractAbove().in(base);    // the last line of the title's block, with the abstract under it
            }
            out.add(new network.ike.komet.document.print.LayoutOracle.Unit(i, area.modelOrdinal, kind, area.spec.plainText(), size, bold, indent,
                    above, below, kind == network.ike.komet.document.print.LayoutOracle.Kind.HEADING).withInline(area.spec.runs(), area.marks, 0).withColumns(area.columns)
                    .withPageBefore(opensPage).withOpensBody(opensBody)
                    .withOpensChapter(kind == network.ike.komet.document.print.LayoutOracle.Kind.TITLE
                            || kind == network.ike.komet.document.print.LayoutOracle.Kind.HEADING && area.chapter));
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
        if (exactArea != null && exactArea.unit >= 0) {
            clearExact();                                         // the words were on a line that is laid again
        }
        // the pages the front lists' entries print: wherever this layout put their targets
        entryPages = network.ike.komet.document.print.FrontLists.pages(layout, entryTargets());
        rowCells = layout.cells();
        for (int u = 0; u < units.size() && u < this.units.size(); u++) {
            Area unitArea = this.units.get(u);
            network.ike.komet.document.print.LayoutOracle.Unit unit = units.get(u);
            List<network.ike.komet.document.print.LayoutOracle.Line> own = new ArrayList<>(layout.ofUnit(u));
            own.sort(Comparator.comparingInt(network.ike.komet.document.print.LayoutOracle.Line::from));
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
                int sliceParagraph = 0;
                int sliceFrom = 0;
                if (whole) {
                    spec = unitArea.spec;
                } else {
                    int[] pa = unit.paragraphAt(line.from());
                    int paragraphStart = line.from() - pa[1];
                    sliceParagraph = pa[0];
                    sliceFrom = pa[1];
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
                laid.paragraph = sliceParagraph;
                laid.from = sliceFrom;
                checkLine(laid, line, unit);
                pageLines.add(laid);
            }
        }
        if (view == View.PAGE_LAYOUT) {
            render();
            selectAgain();                                        // the words a mark was written over, on the lines as laid now
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
        area.inline = unitArea.inline;
        area.sliceParagraph = laid.paragraph;
        area.sliceFrom = laid.from;
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
                area.entryLevelled = unitArea.entryLevelled;
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

    /** The lines of each row's cells in the layout last laid, by the row's unit. */
    private Map<Integer, List<network.ike.komet.document.print.LayoutOracle.CellLine>> rowCells = Map.of();

    /**
     * Draws a row from the lines the layout gave its cells: each line a text of its own, not
     * wrapped, at the baseline the layout set it on. JavaFX measures a word a hair wider than
     * Typst sets it, so a cell left to wrap its own text breaks some lines earlier, comes out a
     * line taller, and runs into the row beneath; drawn from the layout's lines it cannot. On a
     * page a row is broken over, the row is the lines that page holds. A header set again on a
     * later page has the lines of its first setting, moved to where it stands.
     *
     * @return whether the row was drawn; false when the layout gave no lines for it, or its cells do not spell its text
     */
    private boolean setRow(Area area, Area unitArea, List<network.ike.komet.document.print.LayoutOracle.CellLine> all) {
        if (all.isEmpty() || area.cellSpecs == null) {
            return false;
        }
        // where each cell's text begins in the row's: the cells stand in it joined by bars
        String text = unitArea.spec.plainText();
        int[] starts = new int[area.cellSpecs.size()];
        int at = 0;
        for (int c = 0; c < starts.length; c++) {
            LineRunModel.Spec spec = area.cellSpecs.get(c);
            String cell = spec == null ? "" : LineRunModel.Spec.text(spec.paragraphs().getFirst());
            if (spec == null && c == starts.length - 1 && at > text.length()) {
                at = text.length();
            }
            if (!text.startsWith(cell.isBlank() ? "" : cell, Math.min(at, text.length()))) {
                return false;
            }
            starts[c] = at;
            at += cell.length() + network.ike.komet.document.print.TableCells.BAR.length();
        }
        List<network.ike.komet.document.print.LayoutOracle.CellLine> here = all.stream().filter(line -> line.page() == area.page).toList();
        double shift = 0;
        if (here.isEmpty()) {
            int first = all.getFirst().page();
            here = all.stream().filter(line -> line.page() == first).toList();
            shift = area.baseline - all.getFirst().baseline();
        }
        double top = Double.MAX_VALUE;
        double bottom = -Double.MAX_VALUE;
        for (network.ike.komet.document.print.LayoutOracle.CellLine line : here) {
            top = Math.min(top, line.baseline() + shift);
            bottom = Math.max(bottom, line.baseline() + shift);
        }
        StyleAttributeMap style = unitArea.spec.paragraphs().getFirst().style();
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        double cap = size * network.ike.komet.document.print.LayoutOracle.CAP_HEIGHT;
        double inset = network.ike.komet.document.print.TypstOracle.TABLE_INSET;
        // as Typst rules a row: its top a cap's height and the inset above its first baseline, its foot the inset beneath its last
        double rowTop = top - cap - inset;
        List<CellText> cells = new ArrayList<>();
        List<List<network.ike.komet.document.print.PageTableRow.Set>> sets = new ArrayList<>();
        for (int c = 0; c < area.cellSpecs.size(); c++) {
            sets.add(new ArrayList<>());
        }
        for (network.ike.komet.document.print.LayoutOracle.CellLine line : here) {
            LineRunModel.Spec spec = line.cell() < area.cellSpecs.size() ? area.cellSpecs.get(line.cell()) : null;
            if (spec == null) {
                continue;
            }
            LineRunModel.Spec slice = spec.slice(0, line.from() - starts[line.cell()], line.to() - starts[line.cell()], false, false);
            if (slice.paragraphs().isEmpty()) {
                continue;
            }
            CellText cell = new CellText(area, slice);
            cell.rta.setWrapText(false);
            cell.rta.setContentPadding(Insets.EMPTY);
            cell.rta.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0;");
            cell.rta.getStyleClass().add("stamped-page-line");
            cells.add(cell);
            double ascent = metrics(slice.paragraphs().getFirst().style())[0] + 1;
            sets.get(line.cell()).add(new network.ike.komet.document.print.PageTableRow.Set(cell.rta, line.baseline() + shift - ascent - rowTop));
        }
        area.cells = List.copyOf(cells);
        area.rowAscent = area.baseline - rowTop;
        area.rowHeight = bottom - top + cap + 2 * inset;
        area.drawing = new network.ike.komet.document.print.PageTableRow(sets, area.columns, area.tableFirst, area.rowHeight);
        areaByNode.put(area.drawing, area);
        return true;
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
        if (setRow(area, unitArea, rowCells.getOrDefault(area.unit, List.of()))) {
            return;
        }
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
        // a header row is the bold one: its cells stand on their last lines, a body row's begin at its top
        boolean header = !unitArea.spec.paragraphs().isEmpty() && unitArea.spec.paragraphs().getFirst().style().isBold();
        area.drawing = new network.ike.komet.document.print.PageTableRow(texts, area.columns, area.tableFirst, header);
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
                    javafx.scene.layout.Pane drawing = network.ike.komet.document.print.FigureNode.of(area.drawn, scale);
                    network.ike.komet.document.print.DocumentModel.Picture picture = pictures.get(area.drawn);
                    if (picture != null) {
                        // an image block's file at the figure's head, as the PDF has it; its caption is among the marks beneath
                        pictureImage(picture).ifPresent(image -> {
                            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(image);
                            view.setFitWidth(picture.width() * scale);
                            view.setFitHeight(picture.height() * scale);
                            view.setSmooth(true);
                            view.setMouseTransparent(true);
                            drawing.getChildren().addFirst(view);
                        });
                    }
                    area.drawing = drawing;
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
                // a row drawn from the layout's lines knows its own top and height; one whose cells wrap is as tall as they come to
                double rowAscent = area.rowHeight > 0 ? area.rowAscent : m[0] + network.ike.komet.document.print.TypstOracle.TABLE_INSET + 1;
                out.add(new PageStackView.Placed(area.drawing, contentLeft, area.baseline, rowAscent, contentWidth,
                        area.rowHeight > 0 ? area.rowHeight : m[1] + 2 * network.ike.komet.document.print.TypstOracle.TABLE_INSET, false));
                continue;
            }
            double ascent = m[0] + 2 + 1;                            // the area's content padding above the first line, and a point of air
            // JavaFX lays the glyphs without kerning, so a line Typst fit by a hair may not fit here: the area
            // is as wide as the content, or as the line's natural width when that is wider, so the line's last
            // word stays on it and justification spreads the excess over the spaces.
            double available = contentLeft + contentWidth - area.x;
            // A front list's entry keeps the page number's column, the least leader and the gap before it clear on every
            // line, as the Typst writer's entry block does: JavaFX sets the text without kerning, a hair wider than
            // Typst did, and a line that would run into that room gives way to it.
            boolean entry = !area.entryTarget.isEmpty();
            double size = style.getFontSize() == null ? st.base() : style.getFontSize();
            if (entry) {
                if (area.entryColumn == 0) {
                    area.entryColumn = network.ike.komet.document.print.FrontLists.column(
                            textWidth(style, network.ike.komet.document.print.TypstWriter.ENTRY_NUMBER_ROOM, area.entryLevelled),
                            textWidth(style, Integer.toString(entryPages.getOrDefault(area.entryTarget, 0)), style.isBold()));
                }
                available -= network.ike.komet.document.print.FrontLists.reserve(size, area.entryColumn);
            }
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
            out.add(new PageStackView.Placed(area.area, area.x - 4, area.baseline, ascent, width, height, clip, scale));
            if (entry && laid.lastOfUnit) {
                // The entry's last line ends in a gap, its leader and its target's page in the page number's column, from
                // the text's end to the measure's right edge, as the Typst writer's entry block sets them.
                if (area.entryTail == null) {
                    area.entryTail = entryTail(style, Integer.toString(entryPages.getOrDefault(area.entryTarget, 0)), m[0], area.entryColumn);
                    areaByNode.put(area.entryTail, area);
                    entryGesture(area.entryTail, area.entryTarget);
                }
                double from = area.x + natural * scale + size * network.ike.komet.document.print.TypstWriter.ENTRY_GAP;
                out.add(new PageStackView.Placed(area.entryTail, from, area.baseline, m[0], contentLeft + contentWidth - from, m[1], false));
            }
        }
        return out;
    }

    /**
     * The tail of a front list's entry: a leader of dots over the room from the text's end to
     * the page number's column, and the number against the measure's right edge, as the Typst
     * writer's entry block sets them. The dots stand a fixed step apart from the leader's right
     * edge leftward, so the dots of one entry stand over those of the next.
     *
     * @param style  the entry's style, for the font
     * @param page   the page as text
     * @param ascent the line's ascent: where the baseline stands under the tail's top
     * @param column the width of the page number's column
     */
    private Region entryTail(StyleAttributeMap style, String page, double ascent, double column) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
        javafx.scene.text.Text number = new javafx.scene.text.Text(page);
        number.setFont(Font.font(family, style.isBold() ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, size));   // a chief entry's is bold
        number.setTextOrigin(javafx.geometry.VPos.BASELINE);
        javafx.scene.shape.Line leader = new javafx.scene.shape.Line();
        leader.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        double dot = Math.max(1, size * 0.09);
        leader.setStrokeWidth(dot);
        leader.setStroke(Color.BLACK);
        double gutter = size * network.ike.komet.document.print.TypstWriter.ENTRY_GUTTER;
        // a dot and the room after it, as Typst's repeat sets them: the full stop's width and the gap
        double step = textWidth(style, ".", false) + size * network.ike.komet.document.print.TypstWriter.ENTRY_DOT_GAP;   // the dots are never bold
        javafx.scene.layout.Pane tail = new javafx.scene.layout.Pane(leader, number) {
            @Override
            protected void layoutChildren() {
                double width = getWidth();
                number.relocate(Math.max(0, width - number.getLayoutBounds().getWidth()), ascent - number.getBaselineOffset());
                double to = width - column - gutter - dot / 2;     // the last dot's centre, against the leader's right edge
                int dots = (int) Math.floor((to - dot / 2) / step) + 1;
                leader.setVisible(to >= dot / 2);
                if (leader.isVisible()) {
                    // a dash of a hundredth of a point under a round cap is a dot; the dashes repeat every step, the last at the end
                    leader.setStartX(to - (dots - 1) * step);
                    leader.setEndX(to + 0.01);
                    leader.setStartY(ascent - 0.5);
                    leader.setEndY(ascent - 0.5);
                    leader.getStrokeDashArray().setAll(0.01, step - 0.01);
                }
            }
        };
        tail.setMouseTransparent(false);
        tail.getStyleClass().add("stamped-page-entry-tail");
        tail.setMinHeight(0);
        return tail;
    }

    /** @return the width of a text set in a style's font, as the page draws it */
    private double textWidth(StyleAttributeMap style, String text, boolean bold) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.document.print.DocumentFonts.SERIF : style.getFontFamily();
        javafx.scene.text.Text measured = new javafx.scene.text.Text(text);
        measured.setFont(Font.font(family, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL, size));
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
        List<StampEntity<?>> stamps = introducers(area);
        boolean dates = layers.contains(Layer.DATES);
        area.date.setText(dates ? dateOf(stamps) : "");
        area.date.setManaged(dates);
        area.icons = null;
        if (layers.contains(Layer.STAMPS)) {
            FlowPane icons = new FlowPane(3, 3);
            icons.setPrefWrapLength(120);
            icons.setPickOnBounds(false);                               // the marks take the pointer, not the room between them
            icons.getStyleClass().add("stamped-document-stamps");
            area.icons = icons;
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
        readAgainstAbove(area);
        fillNotes(area);
    }

    /** The STAMPs that introduced an area's committed lines, oldest first. */
    private static List<StampEntity<?>> introducers(Area area) {
        if (area.introducers == null) {
            Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
            for (StoredLine line : area.lines) {
                if (line.introduced() != null && line.introduced().time() != Long.MAX_VALUE) {
                    distinct.putIfAbsent(line.introduced().nid(), line.introduced());
                }
            }
            List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
            stamps.sort(Comparator.comparingLong(StampEntity::time));
            area.introducers = stamps;
        }
        return area.introducers;
    }

    /** The day a block's margin shows: its oldest STAMP's; empty when no commit holds its lines. */
    private static String dateOf(List<StampEntity<?>> stamps) {
        return stamps.isEmpty() ? "" : DAY.format(Instant.ofEpochMilli(stamps.getFirst().time()));
    }

    /**
     * Whether a block's date and STAMPs are those of the block above it. A margin that repeats
     * the same date and identicon down the page says nothing after the first: what is worth the
     * margin is where they change. A block starts afresh, showing both, when it is the first of
     * the document or of an included file, or when what is above it is folded away; its date and
     * its STAMPs are judged apart, since two commits of one day change the one and not the other.
     */
    private void readAgainstAbove(Area area) {
        Area above = area.above;
        boolean afresh = above == null || above.member != area.member || !above.shown.get() || above.anchored.get();
        List<StampEntity<?>> mine = introducers(area);
        area.dateRepeats = !afresh && !mine.isEmpty() && dateOf(mine).equals(dateOf(introducers(above)));
        area.stampsRepeat = !afresh && !mine.isEmpty()
                && mine.stream().map(StampEntity::nid).collect(Collectors.toSet())
                        .equals(introducers(above).stream().map(StampEntity::nid).collect(Collectors.toSet()));
        showRepeats(area);
    }

    /**
     * Follows the pointer on the area's row. The right margin takes the pointer on its marks only, so
     * that a figure reaching into it can be used there; the row is what says the pointer is beside
     * the block where the margin shows nothing.
     */
    private void followRow(Area area) {
        Node row = area.documentBlock == null ? null : area.documentBlock.stackNode();
        if (row != null && row != area.followed) {
            area.followed = row;
            row.hoverProperty().addListener(obs -> showRepeats(area));
        }
    }

    /** Draws a repeated date and repeated STAMPs only under the pointer, fainter than those that mark a change; each keeps its room. */
    private void showRepeats(Area area) {
        if (area.date == null || area.marginalia == null || !area.hiddenByFold.isEmpty()) {
            return;                                                  // a fold line stands in the block's place: its margins come back with it
        }
        followRow(area);
        boolean pointed = area.holder.isHover() || area.date.getParent() != null && area.date.getParent().isHover()
                || area.marginalia.getParent() != null && area.marginalia.getParent().isHover()
                || area.followed != null && area.followed.isHover();
        area.date.setVisible(layers.contains(Layer.DATES) && (!area.dateRepeats || pointed));
        area.date.setOpacity(area.dateRepeats ? REPEAT_OPACITY : 1);
        if (area.icons != null) {
            area.icons.setVisible(!area.stampsRepeat || pointed);
            area.icons.setOpacity(area.stampsRepeat ? REPEAT_OPACITY : 1);
        }
    }

    /** How faintly a repeated date or STAMP is drawn under the pointer. */
    private static final double REPEAT_OPACITY = 0.5;

    /** Reads every built block's date and STAMPs against the block above again: a fold changed what is above what. */
    private void readAllAgainstAbove() {
        for (Area area : areas) {
            if (area.marginalia != null && area.documentBlock != null && area.documentBlock.isRealized()) {
                readAgainstAbove(area);
            }
        }
    }

    /**
     * The area each note stands beside, by its annotation's STAMP and the showing: the first block
     * its lines are in, so an annotation that covers lines in several blocks is read once. A line
     * that is shown twice, in an abstract and again in the body, has its note beside it in each
     * place, so a note has a home in every showing of its lines. Kept by area, not as a set of
     * the notes drawn, since a block is filled again every time the surface builds it or a layer
     * is switched, and a note drawn once must be drawn again in the same place.
     */
    private Map<NoteKey, Area> noteHomes;

    /**
     * What a note's home is found by.
     *
     * @param member the showing the note's lines are read in
     * @param stamp  the annotation's STAMP
     */
    private record NoteKey(DocumentAssembly.Member member, long stamp) { }
    /** The area a note is being written beside, or null. */
    private Area composing;
    /** Whether the Reading view was last stacked with a right margin. */
    private boolean rightMargin;

    /** Whether an annotation has words for the margin: a comment, or a refutation with a reason. */
    private static boolean spoken(GitDocumentMaterializer.StoredAnnotation annotation) {
        return annotation.stamp() != null && (annotation.kind() == AnnotationKind.COMMENT
                || annotation.kind() == AnnotationKind.REFUTATION && annotation.text() != null && !annotation.text().isBlank());
    }

    private Map<NoteKey, Area> noteHomes() {
        if (noteHomes == null) {
            Map<NoteKey, Area> homes = new HashMap<>();
            for (Area area : areas) {
                for (StoredLine line : area.lines) {
                    for (GitDocumentMaterializer.StoredAnnotation annotation : line.inForce()) {
                        if (spoken(annotation)) {
                            homes.putIfAbsent(new NoteKey(area.member, annotation.stamp().nid()), area);
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
                if (spoken(annotation) && noteHomes().get(new NoteKey(area.member, annotation.stamp().nid())) == area && drawn.add(annotation.stamp().nid())) {
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
            rta.setDisplayCaret(true);
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
        // a cross reference or a citation that found its target is a link: the pointer says so, and a click goes there
        rta.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            boolean link = area != editing && model.get().internalLinkAt(rta.getTextPosition(e.getScreenX(), e.getScreenY())).isPresent();
            // the control's own text pane asks for the text cursor, and a node's cursor is shown before its parent's:
            // the hand set on the control alone was never seen
            Node text = textPane(rta, e);
            if (text != null) {
                text.setCursor(link ? Cursor.HAND : Cursor.TEXT);
            } else {
                rta.setCursor(link ? Cursor.HAND : null);
            }
        });
        rta.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (area == editing || e.getButton() != MouseButton.PRIMARY || e.isShiftDown() || !e.isStillSincePress() || e.getClickCount() != 1) {
                return;
            }
            model.get().internalLinkAt(rta.getTextPosition(e.getScreenX(), e.getScreenY())).ifPresent(key -> {
                follow(key);
                e.consume();
            });
        });
        rta.selectionProperty().addListener((obs, was, is) -> snap(area, rta, model, is));
        rta.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (area == editing) {
                return;
            }
            if (e.getButton() == MouseButton.SECONDARY) {
                e.consume();                                    // the context menu handles it; the caret stays
                return;
            }
            // a shift-click in the text the words are in reaches for more words, which is the control's own to do;
            // anywhere else it reaches for lines, from where the selection began to the line clicked
            if (e.isShiftDown() && e.getButton() == MouseButton.PRIMARY && selection != null && rta != exactRta) {
                Optional<LineRun> run = model.get().runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
                if (run.isPresent()) {
                    clearExact();
                    selection = selection.extendTo(ordinal(area, run.get().nid()));
                    render();
                    host.selectionChanged();
                    e.consume();
                }
            }
        });
        // the marks are offered where a selection of words ends, and their keys act on it with no editor opened
        rta.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (area != editing && e.getButton() == MouseButton.PRIMARY && rta == exactRta && canMark()) {
                showSelectionBar(rta, e.getScreenX(), e.getScreenY());
            }
        });
        rta.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (area == editing) {
                return;                                         // a block in edit has its own keys
            }
            InlineMarks.forKey(e).ifPresent(mark -> {
                markSelection(mark);
                e.consume();
            });
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
                int o = ordinal(area, run.get().nid());
                // words selected where the menu is asked for stay selected, for Format; otherwise the menu's verbs
                // act on lines, which are drawn whole so that what an annotation would record is seen
                boolean words = wordsSelected() && rta == exactRta && selection != null && selection.contains(o);
                if (!words) {
                    clearExact();
                    if (selection == null || !selection.contains(o)) {
                        selection = new LineSelection(o, o);
                    }
                    render();
                    host.selectionChanged();
                }
            }
            showMenu(area, rta, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /** Shows the surface's menu for the selection as it stands, with what can be folded where it was asked for. */
    private void showMenu(Area area, Node at, double screenX, double screenY) {
        selectionBar.hide();
        boolean structural = editableBlockOfSelection().isEmpty() || editing != null;
        editItem.setDisable(structural);
        deleteItem.setDisable(structural);
        insertMenu.setDisable(structural);
        formatMenu.setDisable(editing != null ? editing.structuredEditor != null : !canMark());
        refreshInsertMenu();
        refreshFoldMenu(area);
        layerItems.forEach((layer, item) -> item.setSelected(layers.contains(layer)));
        signItem.setSelected(host.signsAnnotations());
        viewItems.forEach((candidate, item) -> item.setSelected(candidate == view));
        menu.show(at, screenX, screenY);
    }

    /**
     * The menu asked for beside a block, in its margins or the room around its text: the block's
     * lines become the selection, unless they are within it already, and the menu is the one the
     * text has, so that a fold, a layer, a comment or an edit is one right-click from the margin.
     */
    private void showMarginMenu(Area area, Node at, double screenX, double screenY) {
        if (area == editing || view != View.READING || area.lines.isEmpty()) {
            return;
        }
        hidePopover();
        int first = ordinal(area, area.lines.getFirst().nid());
        int last = ordinal(area, area.lines.getLast().nid());
        if (first >= 0 && last >= 0 && (selection == null || !selection.contains(first) || !selection.contains(last))) {
            clearExact();
            selection = new LineSelection(first, last);
            focus(area);
            render();
            host.selectionChanged();
        }
        showMenu(area, at, screenX, screenY);
    }

    // ── folds by what a block is and what it is in ────────────────────────────

    /**
     * Fills the Fold menu for the block the menu was asked at: the fold it anchors, to open; the
     * blocks selected, when more than one; the block itself, by what it is; each table, list or
     * other block it stands in; each section around it, nearest first; and every section, or
     * every fold, at once.
     */
    private void refreshFoldMenu(Area area) {
        foldMenu.getItems().clear();
        int index = area == null ? -1 : areas.indexOf(area);
        if (view != View.READING || index < 0) {
            foldMenu.setDisable(true);
            return;
        }
        foldMenu.setDisable(false);
        if (folding.at(index).isPresent()) {
            foldMenu.getItems().add(foldEntry("Unfold", "stampedDocumentUnfold", () -> folding.unfold(index)));
        }
        int[] span = selectedAreas();
        if (span != null && span[1] > span[0]) {
            foldMenu.getItems().add(foldEntry("Fold the " + (span[1] - span[0] + 1) + " blocks selected", "stampedDocumentFoldSelection",
                    () -> folding.foldRun(span[0], span[1])));
        }
        java.util.Set<String> offered = new java.util.HashSet<>();
        if (area.block != null && levelAt(index) < 0) {
            // the block, then what it stands in: a row's table, an item's list, a paragraph's example or sidebar
            StoredBlock at = area.block;
            while (at != null && at.kind() != Block.Kind.DOCUMENT && at.kind() != Block.Kind.SECTION) {
                int[] run = runOf(index, at);
                String word = wordFor(at);
                if (run != null && word != null && offered.add(run[0] + ":" + run[1])) {
                    foldMenu.getItems().add(foldEntry("Fold " + word, "stampedDocumentFold" + DocumentInserts.camel(at.kind().name().toLowerCase(java.util.Locale.ROOT)),
                            () -> folding.foldRun(run[0], run[1])));
                }
                at = parentOf(area.member, at);
            }
        }
        // the sections around the block, nearest first
        int level = Integer.MAX_VALUE;
        for (int i = index; i >= 0; i--) {
            int here = levelAt(i);
            if (here >= 1 && here < level) {
                int heading = i;
                int end = Folding.sectionEnd(this::levelAt, areas.size(), heading);
                level = here;
                if (end >= index && end > heading) {
                    String title = foldKey(heading).replaceFirst("^=+\\s*", "");
                    foldMenu.getItems().add(foldEntry("Fold section “" + (title.length() > 48 ? title.substring(0, 47) + "…" : title) + "”",
                            "stampedDocumentFoldSection", () -> folding.foldSection(heading, end)));
                }
            }
        }
        foldMenu.getItems().add(new SeparatorMenuItem());
        foldMenu.getItems().add(foldEntry("Fold every section", "stampedDocumentFoldAll", this::foldEverySection));
        MenuItem open = foldEntry("Unfold everything", "stampedDocumentUnfoldAll", folding::clear);
        open.setDisable(folding.isEmpty());
        foldMenu.getItems().add(open);
    }

    /** @return what the Fold menu offers at a block of the Reading view, by its place: for tests and automation */
    List<String> foldsOffered(int index) {
        refreshFoldMenu(areas.get(index));
        return foldMenu.getItems().stream().filter(item -> !(item instanceof SeparatorMenuItem) && !item.isDisable()).map(MenuItem::getText).toList();
    }

    /** Chooses one of the folds {@link #foldsOffered} names, as a click on it does. */
    void fold(int index, String offered) {
        refreshFoldMenu(areas.get(index));
        foldMenu.getItems().stream().filter(item -> offered.equals(item.getText())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no fold “" + offered + "” is offered there")).fire();
    }

    /** @return whether a block of the Reading view is folded away: hidden, or standing as the line that says what is folded */
    boolean folded(int index) {
        return folding.hidden(index) || folding.at(index).map(fold -> !fold.atHeading()).orElse(false);
    }

    private MenuItem foldEntry(String text, String id, Runnable fold) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        item.setOnAction(e -> {
            fold.run();
            foldsChanged();
        });
        return item;
    }

    /** Folds every outermost section: each heading at the shallowest level the document has below its title. */
    private void foldEverySection() {
        int outermost = Integer.MAX_VALUE;
        for (int i = 0; i < areas.size(); i++) {
            int level = levelAt(i);
            if (level >= 1) {
                outermost = Math.min(outermost, level);
            }
        }
        for (int i = 0; i < areas.size(); i++) {
            if (levelAt(i) == outermost) {
                folding.foldSection(i, Folding.sectionEnd(this::levelAt, areas.size(), i));
            }
        }
    }

    /** What a fold of a block is called: the block's kind as a reader names it; null for a kind that is never folded by itself. */
    private static String wordFor(StoredBlock block) {
        Optional<StructuredBlockKind> drawn = StructuredBlocks.kindOf(block);
        if (drawn.isPresent()) {
            return drawn.get().name();
        }
        return switch (block.kind()) {
            case PARAGRAPH -> "paragraph";
            case LISTING -> "listing";
            case LITERAL -> "literal block";
            case EXAMPLE -> "example";
            case QUOTE -> "quote";
            case SIDEBAR -> "sidebar";
            case OPEN -> "block";
            case ADMONITION -> "note";
            case TABLE -> "table";
            case LIST -> "list";
            case LIST_ITEM -> "list item";
            case DIAGRAM -> "diagram";
            case IMAGE -> "image";
            case BIBLIOGRAPHY_ENTRY -> "reference";
            default -> null;
        };
    }

    /** A block's parent in the document its showing reads; null for the root. */
    private StoredBlock parentOf(DocumentAssembly.Member member, StoredBlock block) {
        if (member == null || block == null) {
            return null;
        }
        return parents.computeIfAbsent(member, m -> {
            Map<Long, StoredBlock> found = new HashMap<>();
            for (StoredBlock each : reads(m).blocks().values()) {
                for (long nid : each.members()) {
                    found.put(nid, each);
                }
            }
            return found;
        }).get(block.nid());
    }

    /**
     * The areas a block is drawn as, with every block in it, around one of them: the run of
     * areas, unbroken, whose blocks are the block or stand in it. Null when the area is not one.
     */
    private int[] runOf(int index, StoredBlock block) {
        Area area = areas.get(index);
        java.util.Set<Long> within = new java.util.HashSet<>();
        collect(reads(area.member), block, within);
        if (area.block == null || !within.contains(area.block.nid())) {
            return null;
        }
        int from = index;
        int to = index;
        while (from > 0 && inRun(areas.get(from - 1), area.member, within)) {
            from--;
        }
        while (to + 1 < areas.size() && inRun(areas.get(to + 1), area.member, within)) {
            to++;
        }
        return new int[] {from, to};
    }

    private static boolean inRun(Area area, DocumentAssembly.Member member, java.util.Set<Long> within) {
        return area.member == member && area.block != null && within.contains(area.block.nid());
    }

    private static void collect(StoredDocument document, StoredBlock block, java.util.Set<Long> into) {
        into.add(block.nid());
        for (long nid : block.members()) {
            StoredBlock child = document.blocks().get(nid);
            if (child != null) {
                collect(document, child, into);
            }
        }
    }

    private static final Object TEXT_PANE = new Object();

    /**
     * The node inside a text control that asks for the text cursor: the nearest one above what
     * the pointer is on, found once and kept for as long as it is in a scene.
     */
    private static Node textPane(RichTextArea rta, MouseEvent e) {
        if (rta.getProperties().get(TEXT_PANE) instanceof Node known && known.getScene() != null) {
            return known;
        }
        for (Node at = e.getTarget() instanceof Node target ? target : null; at != null && at != rta; at = at.getParent()) {
            if (at.getCursor() != null) {
                rta.getProperties().put(TEXT_PANE, at);
                return at;
            }
        }
        return null;
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

    /**
     * Takes a gesture's selection in one text as the reader's own: the control keeps the words as
     * they were chosen, the block takes the focus, and the document's selection is the lines the
     * words touch, which is what an annotation records. A click alone selects no words: its line
     * is the selection, and the caret stands where the click landed, for Shift-click and the
     * Shift-arrow keys to select from.
     */
    private void snap(Area area, RichTextArea rta, java.util.function.Supplier<LineRunModel> source, SelectionSegment is) {
        LineRunModel model = source.get();
        if (applying || area == editing || is == null || model == null) {
            return;
        }
        Optional<LineRun> first = model.runAt(is.getMin());
        Optional<LineRun> last = lastRun(model, is);
        if (first.isEmpty() || last.isEmpty()) {
            return;
        }
        boolean forward = is.getAnchor().compareTo(is.getCaret()) <= 0;
        int from = ordinal(area, first.get().nid());
        int to = ordinal(area, last.get().nid());
        if (from < 0 || to < 0) {
            return;
        }
        selection = forward ? new LineSelection(from, to) : new LineSelection(to, from);
        reselect = null;                                          // the reader has selected something else
        exactRta = rta;
        exactArea = area;
        exactModel = source;
        exactAnchor = is.getAnchor();
        exactCaret = is.getCaret();
        if (is.isCollapsed()) {
            selectionBar.hide();
        }
        focus(area);
        render();
        host.selectionChanged();
    }

    /** The run the last selected character is on: a selection that ends where a line begins does not touch that line. */
    private static Optional<LineRun> lastRun(LineRunModel model, SelectionSegment is) {
        jfx.incubator.scene.control.richtext.TextPos max = is.getMax();
        if (is.isCollapsed()) {
            return model.runAt(max);
        }
        if (max.offset() > 0) {
            return model.runAt(jfx.incubator.scene.control.richtext.TextPos.ofLeading(max.index(), max.offset() - 1));
        }
        List<LineRun> before = model.runs(max.index() - 1);
        return before.isEmpty() ? model.runAt(max) : Optional.of(before.getLast());
    }

    /** Gives a Reading view block the focus: a bar at its left edge, where the block last clicked in is seen without a selection. */
    private void focus(Area area) {
        Area block = view == View.READING && area != null && area.holder != null && area.unit < 0 && area.numbers == null ? area : null;
        if (block == focused) {
            return;
        }
        if (focused != null && focused.holder != null) {
            focused.holder.setStyle("");
        }
        focused = block;
        if (block != null) {
            block.holder.setStyle(FOCUS_BAR);
        }
    }

    /** The focus bar: two points of colour down a block's left edge, painted behind it so the block's layout does not move. */
    private static final String FOCUS_BAR = "-fx-background-color: linear-gradient(from 0px 0px to 3px 0px, #3b82f6 0%, #3b82f6 66%, transparent 67%);";

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
            applySelection(area, area.area, area.model);
            for (CellText cell : area.cells) {
                applySelection(area, cell.rta, cell.model);
            }
        } finally {
            applying = false;
        }
    }

    /**
     * Draws the selection in one text: the reader's own words in the text they were chosen in and
     * nothing anywhere else; or, the selection being lines, the runs of the text's model the
     * selection holds, as one stretch. The lines are those of the area's own showing: a line an
     * abstract and the body both show is drawn selected where the reader selected it.
     */
    private void applySelection(Area area, RichTextArea rta, LineRunModel model) {
        if (exactRta != null) {
            if (rta != exactRta || !inWindow(rta)) {
                rta.clearSelection();
                return;
            }
            SelectionSegment now = rta.getSelection();
            if (now == null || !now.getAnchor().equals(exactAnchor) || !now.getCaret().equals(exactCaret)) {
                rta.select(exactAnchor, exactCaret);
            }
            return;
        }
        LineRun first = null;
        LineRun last = null;
        if (selection != null) {
            for (LineRun run : model.runs()) {
                if (selection.contains(ordinal(area, run.nid()))) {
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

    // ── marks on a selection of words ─────────────────────────────────────────

    /** A block whose text is prose a mark can be written in: a paragraph, a list item, an admonition. */
    private static boolean marksIn(StoredBlock block) {
        return block != null && switch (block.kind()) {
            case PARAGRAPH, ADMONITION, LIST_ITEM, BIBLIOGRAPHY_ENTRY -> true;
            default -> false;
        };
    }

    /** The Reading view's editable area of the block a stored line belongs to: the block an edit of the line is saved as. */
    private Optional<Area> blockAreaOf(long lineNid) {
        // in the showing the words were selected in, when the line's file is shown more than once; a block that a
        // tagged region cuts is whole where its file is shown whole, and a mark over its words is saved from there
        DocumentAssembly.Member member = exactArea == null ? null : exactArea.member;
        Optional<Area> here = areas.stream().filter(a -> a.editable && (member == null || a.member == member) && a.lines.stream().anyMatch(l -> l.nid() == lineNid))
                .findFirst();
        return here.isPresent() || member == null ? here
                : areas.stream().filter(a -> a.editable && a.lines.stream().anyMatch(l -> l.nid() == lineNid)).findFirst();
    }

    /** @return whether a mark can be written over the words selected: they are in prose of the document as it is now */
    boolean canMark() {
        if (editing != null || past || !wordsSelected() || exactModel == null || exactModel.get() == null) {
            return false;
        }
        jfx.incubator.scene.control.richtext.TextPos min = exactAnchor.compareTo(exactCaret) <= 0 ? exactAnchor : exactCaret;
        return exactModel.get().runAt(min).flatMap(run -> blockAreaOf(run.nid())).filter(area -> marksIn(area.block)).isPresent();
    }

    /**
     * Selects words by what they say, as a drag across them does, in the view shown: for
     * automation and tests, which have no pointer to drag.
     *
     * @param words the words, as one of the view's texts draws them in one paragraph
     * @param nth   which occurrence of them in the view, from 0, in reading order
     * @return whether they were found and selected
     */
    public boolean selectWords(String words, int nth) {
        if (words == null || words.isEmpty() || editing != null) {
            return false;
        }
        int seen = 0;
        if (view == View.PAGE_LAYOUT) {
            // every laid line is looked through, and the line's area built if its page was not shown yet
            for (PageLine laid : pageLines) {
                if (laid.unitArea.drawn != null) {
                    continue;
                }
                for (int p = 0; p < laid.spec.paragraphs().size(); p++) {
                    String text = LineRunModel.Spec.text(laid.spec.paragraphs().get(p));
                    for (int at = text.indexOf(words); at >= 0; at = text.indexOf(words, at + 1)) {
                        if (seen++ == nth) {
                            return selectWordsAt(laid.area == null ? buildLine(laid) : laid.area, p, at, words.length());
                        }
                    }
                }
            }
            return false;
        }
        for (Area area : shownAreas()) {
            LineRunModel model = area.model;
            if (model == null || area.area == null) {
                continue;
            }
            for (int p = 0; p < model.size(); p++) {
                String text = model.getPlainText(p);
                for (int at = text.indexOf(words); at >= 0; at = text.indexOf(words, at + 1)) {
                    if (seen++ == nth) {
                        return selectWordsAt(area, p, at, words.length());
                    }
                }
            }
        }
        return false;
    }

    /** Makes a stretch of one of an area's paragraphs the reader's own selection, and brings it into view. */
    private boolean selectWordsAt(Area area, int paragraph, int at, int length) {
        LineRunModel model = area.model;
        jfx.incubator.scene.control.richtext.TextPos from = jfx.incubator.scene.control.richtext.TextPos.ofLeading(paragraph, at);
        jfx.incubator.scene.control.richtext.TextPos to = jfx.incubator.scene.control.richtext.TextPos.ofLeading(paragraph, at + length);
        Optional<LineRun> first = model.runAt(from);
        Optional<LineRun> last = model.runAt(jfx.incubator.scene.control.richtext.TextPos.ofLeading(paragraph, at + length - 1));
        if (first.isEmpty() || last.isEmpty() || ordinal(area, first.get().nid()) < 0 || ordinal(area, last.get().nid()) < 0) {
            return false;
        }
        selection = new LineSelection(ordinal(area, first.get().nid()), ordinal(area, last.get().nid()));
        exactRta = area.area;
        exactArea = area;
        exactModel = () -> area.model;
        exactAnchor = from;
        exactCaret = to;
        focus(area);
        render();
        reveal();                                                 // automation has no eyes on the page: the words are brought into view
        host.selectionChanged();
        return true;
    }

    /** @return the words the reader has selected, as drawn, or empty when the selection is lines */
    public String selectedWords() {
        if (!wordsSelected() || exactModel == null || exactModel.get() == null) {
            return "";
        }
        boolean forward = exactAnchor.compareTo(exactCaret) <= 0;
        jfx.incubator.scene.control.richtext.TextPos min = forward ? exactAnchor : exactCaret;
        jfx.incubator.scene.control.richtext.TextPos max = forward ? exactCaret : exactAnchor;
        LineRunModel model = exactModel.get();
        if (min.index() != max.index() || min.index() >= model.size()) {
            return "";
        }
        String text = model.getPlainText(min.index());
        return text.substring(Math.min(min.offset(), text.length()), Math.min(max.offset(), text.length()));
    }

    /** Offers the marks where a selection of words ended: a bar of them just above the pointer, gone at the next press. */
    private void showSelectionBar(RichTextArea rta, double screenX, double screenY) {
        if (rta.getScene() == null || rta.getScene().getWindow() == null) {
            return;
        }
        HBox bar = new HBox(3);
        bar.getChildren().addAll(formatBar("stampedDocumentSelectionMark", this::markSelection));
        bar.setId("stampedDocumentSelectionBar");
        bar.setPadding(new Insets(4));
        bar.setStyle("-fx-background-color: white; -fx-background-radius: 6; -fx-border-color: #c9ced6; -fx-border-radius: 6;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.18), 8, 0, 0, 2);");
        selectionBar.getContent().setAll(bar);
        selectionBar.show(rta.getScene().getWindow(), screenX - 96, screenY - 48);
    }

    /** A line's drawn text, and where a text's run of the line begins in it. */
    private record Share(String text, int base) { }

    /**
     * The whole drawn text of a run's line. In the Reading view and the ledger a run is its line's
     * whole share; a page line holds only the part of the share that fell on it, so the share is
     * read from the line's unit, with where the page line's part begins in it.
     */
    private Share share(Area area, LineRunModel model, LineRun run) {
        String own = model.getPlainText(run.paragraph()).substring(run.start(), Math.min(run.end(), model.getPlainText(run.paragraph()).length()));
        if (area.unit < 0 || area.whole || area.unit >= units.size() || area.sliceParagraph >= units.get(area.unit).spec.paragraphs().size()) {
            return new Share(own, 0);
        }
        LineRunModel.Paragraph paragraph = units.get(area.unit).spec.paragraphs().get(area.sliceParagraph);
        int at = 0;
        for (int i = 0; i < paragraph.lines().size(); i++) {
            if (i > 0) {
                at += paragraph.separator().length();
            }
            String text = paragraph.lines().get(i).text();
            if (paragraph.lines().get(i).line().nid() == run.nid()) {
                return new Share(text, Math.max(at, area.sliceFrom) - at);
            }
            at += text.length();
        }
        return new Share(own, 0);
    }

    private static final String NOT_WORDS = "That selection cannot be marked as it stands: it holds something other than words, or half of a marked phrase."
            + " Select whole words, or use Edit block to change the source.";

    /**
     * Writes a mark over the words selected, with no editor opened: the words are found in their
     * lines' source ({@link SourceSpan}), the mark is toggled there, and the block is saved as an
     * uncommitted edit, as an edit in place is. The selection may run over lines of one block,
     * since a paragraph's lines are one text. A link needs its address typed, so in the Reading
     * view it opens the block's source at the link with the address selected.
     *
     * @param mark the mark
     */
    void markSelection(InlineMarks mark) {
        selectionBar.hide();
        if (editing != null) {
            host.status("Finish the edit in progress first: Save or Cancel it.");
            return;
        }
        if (past) {
            host.status("The document is read as of a past time, which is not edited: move the view's time to the latest to mark it.");
            return;
        }
        if (!wordsSelected() || exactModel == null || exactModel.get() == null) {
            host.status("Select the words to mark: drag across them, or double-click one.");
            return;
        }
        LineRunModel model = exactModel.get();
        boolean forward = exactAnchor.compareTo(exactCaret) <= 0;
        jfx.incubator.scene.control.richtext.TextPos min = forward ? exactAnchor : exactCaret;
        jfx.incubator.scene.control.richtext.TextPos max = forward ? exactCaret : exactAnchor;
        Optional<LineRun> firstRun = model.runAt(min);
        Optional<LineRun> lastRun = max.offset() > 0 ? model.runAt(jfx.incubator.scene.control.richtext.TextPos.ofLeading(max.index(), max.offset() - 1))
                : model.runs(max.index() - 1).isEmpty() ? model.runAt(max) : Optional.of(model.runs(max.index() - 1).getLast());
        Optional<Area> home = firstRun.flatMap(run -> blockAreaOf(run.nid()));
        if (firstRun.isEmpty() || lastRun.isEmpty() || home.isEmpty() || !marksIn(home.get().block)) {
            host.status("A mark is written in a paragraph, a list item or an admonition.");
            return;
        }
        LineRun first = firstRun.get();
        LineRun last = lastRun.get();
        Area block = home.get();
        // the lines the mark is saved in: the whole block's source where a conditional parts it, so no line is lost
        List<StoredLine> stored = block.saved();
        List<String> lines = new ArrayList<>(stored.stream().map(StoredLine::text).toList());
        int a = -1;
        int b = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (stored.get(i).nid() == first.nid()) {
                a = i;
            }
            if (stored.get(i).nid() == last.nid()) {
                b = i;
            }
        }
        if (a < 0 || b < a) {
            host.status("A mark stays within one block: the selection runs into another.");
            return;
        }
        Share from = share(exactArea, model, first);
        Share to = share(exactArea, model, last);
        int start = from.base() + Math.max(0, min.offset() - first.start());
        int end = to.base() + (last.paragraph() == max.index() ? Math.max(0, max.offset() - last.start()) : last.end() - last.start());
        boolean inline = exactArea.inline;
        Reselect again = null;
        if (a == b) {
            Optional<SourceSpan.Located> at = SourceSpan.locate(lines.get(a), inline, from.text(), start, end);
            if (at.isEmpty()) {
                host.status(NOT_WORDS);
                return;
            }
            SourceSpan.Located found = at.get();
            if (mark == InlineMarks.BOLD && found.bold() || mark == InlineMarks.ITALIC && found.italic() || mark == InlineMarks.MONO && found.mono()) {
                host.status("Those words are part of a longer phrase that is already " + mark.label().toLowerCase(java.util.Locale.ROOT)
                        + ": select the whole phrase to change it.");
                return;
            }
            if (mark == InlineMarks.LINK) {
                linkInSource(block, a, found);
                return;
            }
            // the words' own mark of this kind goes; any other mark is written outside the marks already around the words
            String line = lines.get(a);
            String unmarked = mark.toggle(line, found.from(), found.to()).text();
            lines.set(a, unmarked.length() < line.length() ? unmarked : mark.toggle(line, found.outerFrom(), found.outerTo()).text());
            // the words are selected again where the reader selected them, which a cut block's saved area may not be
            again = new Reselect(block.block.nid(), first.nid(), found.words(), found.nth(),
                    assembly == null || exactArea == null ? -1 : assembly.members().indexOf(exactArea.member));
        } else {
            java.util.OptionalInt opens = SourceSpan.start(lines.get(a), inline, from.text(), start);
            java.util.OptionalInt closes = SourceSpan.end(lines.get(b), inline, to.text(), end);
            Optional<String[]> both = opens.isPresent() && closes.isPresent()
                    ? mark.across(lines.get(a), opens.getAsInt(), lines.get(b), closes.getAsInt()) : Optional.empty();
            if (both.isEmpty()) {
                host.status(mark == InlineMarks.LINK && opens.isPresent() && closes.isPresent()
                        ? "A link's words stand on one line of the source, and these run over two: select fewer words, or use Edit block." : NOT_WORDS);
                return;
            }
            lines.set(a, both.get()[0]);
            lines.set(b, both.get()[1]);
        }
        reselect = again;
        host.saveEdit(block.block, String.join("\n", lines));
    }

    /**
     * A link over selected words: the block's source opens at the link, its address selected, so
     * that typing gives it; a link saved with no address would be a broken one. From a page the
     * source is the Reading view's to open, so the reader is told where.
     */
    private void linkInSource(Area block, int line, SourceSpan.Located found) {
        if (view != View.READING || !inWindow(block.area)) {
            host.status("A link needs its address typed: in the Reading view, select the words and choose Link.");
            return;
        }
        startEdit(block);
        RichTextArea rta = block.area;
        rta.select(jfx.incubator.scene.control.richtext.TextPos.ofLeading(line, found.from()),
                jfx.incubator.scene.control.richtext.TextPos.ofLeading(line, found.to()));
        applyMark(rta, InlineMarks.LINK);
        host.status("Type the link's address over the selection, then Save.");
    }

    /**
     * Selects again, once a mark is saved and the document read again, the words it was written
     * over: the same words at the same place in their line's drawn text, which a mark does not
     * change. So a second mark, bold then italic, needs no second selection.
     */
    private void selectAgain() {
        Reselect again = reselect;
        if (again == null) {
            return;
        }
        if (view == View.PAGE_LAYOUT) {
            // on a page the words are looked for among the laid lines of their own source line, counted through in
            // order; words a line break now parts are not selected, and their lines are not drawn selected in their place
            int seen = 0;
            for (PageLine laid : pageLines) {
                if (laid.unitArea.drawn != null || !again.in(laid.unitArea, assembly)) {
                    continue;
                }
                for (int p = 0; p < laid.spec.paragraphs().size(); p++) {
                    LineRunModel.Paragraph paragraph = laid.spec.paragraphs().get(p);
                    int start = 0;
                    for (int i = 0; i < paragraph.lines().size(); i++) {
                        if (i > 0) {
                            start += paragraph.separator().length();
                        }
                        String share = paragraph.lines().get(i).text();
                        if (paragraph.lines().get(i).line().nid() == again.line()) {
                            for (int at = share.indexOf(again.words()); at >= 0; at = share.indexOf(again.words(), at + 1)) {
                                if (seen++ == again.nth()) {
                                    selectWordsHere(laid.area == null ? buildLine(laid) : laid.area, p, start + at, again.words().length());
                                    return;
                                }
                            }
                        }
                        start += share.length();
                    }
                }
            }
            selection = null;
            render();
            host.selectionChanged();
            return;
        }
        if (view != View.READING) {
            return;
        }
        Area area = areas.stream().filter(a -> a.model != null && a.area != null && again.in(a, assembly)
                && a.model.runs().stream().anyMatch(r -> r.nid() == again.line())).findFirst().orElse(null);
        if (area == null) {
            return;
        }
        for (LineRun run : area.model.runs()) {
            if (run.nid() != again.line()) {
                continue;
            }
            String text = area.model.getPlainText(run.paragraph());
            String share = text.substring(run.start(), Math.min(run.end(), text.length()));
            int at = -1;
            for (int n = 0; n <= again.nth(); n++) {
                at = share.indexOf(again.words(), at + 1);
                if (at < 0) {
                    return;
                }
            }
            selectWordsHere(area, run.paragraph(), run.start() + at, again.words().length());
            return;
        }
    }

    /** Makes a stretch of one of an area's paragraphs the reader's own selection, where it stands. */
    private void selectWordsHere(Area area, int paragraph, int at, int length) {
        exactRta = area.area;
        exactArea = area;
        exactModel = () -> area.model;
        exactAnchor = jfx.incubator.scene.control.richtext.TextPos.ofLeading(paragraph, at);
        exactCaret = jfx.incubator.scene.control.richtext.TextPos.ofLeading(paragraph, at + length);
        Optional<LineRun> run = area.model == null ? Optional.empty() : area.model.runAt(exactAnchor);
        int ordinal = run.map(r -> ordinal(area, r.nid())).orElse(-1);
        if (ordinal >= 0) {
            selection = new LineSelection(ordinal, ordinal);
        }
        focus(area);
        render();
        host.selectionChanged();
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
            Area candidate = shown.get(i);
            if (candidate.model.runs().stream().anyMatch(r -> selection.contains(ordinal(candidate, r.nid())))) {
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
        foldMenu.setId("stampedDocumentFoldMenu");
        layersMenu.setId("stampedDocumentLayersMenu");
        for (Layer layer : Layer.values()) {
            CheckMenuItem item = new CheckMenuItem(switch (layer) {
                case DATES -> "Dates";
                case STAMPS -> "Stamps";
                case COMMENTS -> "Comments";
                case TINTS -> "Commit tints";
                case REVIEW_QUEUE -> "Review queue";
                case ANNOTATION_MARKS -> "Annotation marks";
                case LEFT_OUT -> "Passages left out";
            });
            item.setId("stampedDocumentMenuLayer" + layer.name());
            item.setOnAction(e -> host.layerRequested(layer, item.isSelected()));
            layerItems.put(layer, item);
            layersMenu.getItems().add(item);
        }
        // Record: what the document is and was, and how an annotation is recorded
        MenuItem history = item("Show history", "stampedDocumentShowHistory", host::showHistory);
        MenuItem print = item("Print to PDF…", "stampedDocumentMenuPrintPdf", host::printPdf);
        signItem.setId("stampedDocumentSignAnnotations");
        signItem.setOnAction(e -> host.setSignsAnnotations(signItem.isSelected()));
        menu.getItems().addAll(affirm, refute, comment, retract, new SeparatorMenuItem(), editItem, deleteItem, new SeparatorMenuItem(), insertMenu,
                formatMenu, new SeparatorMenuItem(), foldMenu, layersMenu, new SeparatorMenuItem());
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
            } else if (editing == null) {
                markSelection(mark);
            }
        });
        return item;
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
        // the block where the selection is: a block its file shows twice is edited in the place the reader chose
        DocumentAssembly.Member member = assembly.memberAt(selection.min()).orElse(null);
        // a block a conditional parts is found by any line of its source, a directive's among them
        return areas.stream().filter(a -> a.editable && a.member == member && a.saved().stream().anyMatch(l -> l.nid() == nid)).findFirst();
    }

    /** Opens the block the selection starts in for editing in place, in the Reading view. */
    void editSelectedBlock() {
        Optional<Area> area = editableBlockOfSelection();
        if (area.isEmpty()) {
            boolean leftOut = selection != null && assembly != null && !layers.contains(Layer.LEFT_OUT) && selection.min() >= 0
                    && selection.min() < assembly.lines().size() && assembly.memberAt(selection.min())
                    .map(member -> assembly.read(member).line(assembly.lines().get(selection.min()).nid()).isEmpty()).orElse(false);
            host.status(leftOut ? "That line is left out by a conditional: turn on Passages left out in the Layer menu to edit it."
                    : "Select a line of a paragraph or listing to edit its block.");
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
        // the editor holds the whole block's source, so its other parts are drawn faint until the edit ends
        partsWhileEditing(area, true);
        RichTextArea rta = area.area;
        RichTextModel model = new RichTextModel();
        applying = true;
        try {
            rta.setModel(model);
            rta.appendText(area.saved().stream().map(StoredLine::text).collect(Collectors.joining("\n")), Styles.SCREEN.mono());
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

    /** The format bar of a block in edit: each button marks the editor's selection. */
    private List<Button> formatBar(RichTextArea rta) {
        return formatBar("stampedDocumentMark", mark -> {
            applyMark(rta, mark);
            rta.requestFocus();
        });
    }

    /** A format bar: a button for each inline mark, its key in its tooltip, so the keys are learned where they are used. */
    private static List<Button> formatBar(String idPrefix, java.util.function.Consumer<InlineMarks> act) {
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
            button.setId(idPrefix + DocumentInserts.camel(mark.name().toLowerCase(java.util.Locale.ROOT)));
            button.setFont(Font.font("System", mark == InlineMarks.BOLD ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                    mark == InlineMarks.ITALIC ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, 12));
            if (mark == InlineMarks.UNDERLINE) {
                button.setUnderline(true);
            }
            button.setFocusTraversable(false);
            button.setMinWidth(26);
            button.setPadding(new Insets(2, 6, 2, 6));
            button.setTooltip(new Tooltip(mark.label() + "  " + mark.keyText()));
            button.setOnAction(e -> act.accept(mark));
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

    /**
     * While a block that a conditional parts is edited, its editor holds the source of all its
     * parts: the editor is drawn at full strength though its passage is left out, and the block's
     * other parts faint, since their words are in the editor.
     */
    private void partsWhileEditing(Area area, boolean editing) {
        if (area.leftOut != null && area.holder != null) {
            faint(area, editing ? 1 : LEFT_OUT_OPACITY);
        }
        if (area.parts == null) {
            return;
        }
        for (Area part : area.parts) {
            if (part != area && part.holder != null) {
                part.holder.setOpacity(editing ? 0.25 : 1);
            }
        }
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
        rta.setId(null);
        rta.setStyle(area.block != null && area.block.kind() == Block.Kind.ADMONITION ? "-fx-background-color: #FFF8E1; -fx-background-radius: 4;"
                : area.block != null && (area.block.kind() == Block.Kind.LISTING || area.block.kind() == Block.Kind.LITERAL)
                ? "-fx-background-color: #F4F3F1; -fx-background-radius: 4;" : "");
        area.holder.getChildren().removeIf(n -> "stampedDocumentEditButtons".equals(n.getId()));
        editing = null;
        partsWhileEditing(area, false);
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
