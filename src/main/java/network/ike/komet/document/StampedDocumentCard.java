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

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.help.KlHelpTopic;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.komet.layout.controls.FilterOptionsPopup;
import dev.ikm.komet.layout.controls.ViewOptionsPopupHelper;
import dev.ikm.tinkar.common.util.time.DateTimeUtil;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Window;
import network.ike.komet.document.surface.DocumentSurfaceArea;
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.print.DocumentFonts;
import network.ike.komet.document.print.DocumentModel;
import network.ike.komet.document.print.Folios;
import network.ike.komet.document.print.FrontLists;
import network.ike.komet.document.print.GreedyFxOracle;
import network.ike.komet.document.print.LayoutOracle;
import network.ike.komet.document.print.PageFidelity;
import network.ike.komet.document.print.RunningHeads;
import network.ike.komet.document.print.TypstOracle;
import network.ike.komet.document.print.TypstWriter;
import network.ike.komet.document.print.PrintBlocks;
import network.ike.komet.document.print.StoredDocumentModel;
import network.ike.komet.document.print.TypstPdf;
import network.ike.komet.document.surface.print.PrintSource;
import network.ike.komet.document.surface.print.PagedDocumentEngine;
import network.ike.komet.document.surface.print.settings.MarginPreset;
import network.ike.komet.document.surface.print.settings.PrintSettings;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The Stamped Document card ({@code IKE-Network/ike-issues#1307}; ike-lab-documents topic
 * {@code dev-stamped-document-surface}): one card, a Reading view and a Source view of the same
 * {@link StoredDocument}, and a Provenance layer over either. The document is what the
 * {@link GitDocumentMaterializer} wrote into the knowledge base, never the file; the repository
 * is consulted only for commit subjects and for the annotation trailers a person signs.
 *
 * <p>The toolbar holds what acts on the document as a whole: the document chooser, the view
 * switch, the layer menu and an overflow menu. An edit made in Komet is settled at the bottom of
 * the card, in a {@link PendingEditBar} above the status line that is there only while an edit
 * is pending or one can be restored: the count of edited lines, the commit's message, Commit,
 * and Discard… at its far end. The acts
 * on a selection — Affirm, Refute, Comment, Edit block, Show history — are the context menu of
 * the selection, in {@link DocumentSurfaceView}. The assistant's {@code StampTools} default to
 * the selection and hand their proposals to {@link #proposeAnnotation}.
 *
 * <p>A document opened from a repository (Open file… in the overflow) and one chosen from the
 * store are one choice: the card materializes the file if the store lacks it, remembers the
 * working tree, and shows the store's copy.
 *
 * <p>An assembly opens as one document: the host's include lines that name documents the store
 * holds (by their recorded path under the same working tree, or under a working tree beside it
 * when the include names a library another repository holds) show those children in place, each
 * still its own document ({@link DocumentAssembly}). A selection is located to the one member it
 * lies in, so an annotation's trailers name the child's file; the footer follows the member
 * under the viewport; an edit in place inside a child edits that child's block, and Commit
 * commits the member whose edit is pending.
 *
 * <p>The card builds no body of its own: it hosts the plugin's {@link DocumentSurfaceArea} as the
 * Claude card does, and the view stacks the document's blocks on that area's surface, with the
 * title and section in the surface's header, the status line, document id and commit in its
 * footer, dates in the left margin and the identicon marginalia in the right. The area's
 * expansion is the paged preview and its preferences are the print settings.
 */
public final class StampedDocumentCard extends AbstractHostCard implements DocumentSurfaceView.Host, RepositoryPanel.Host {

    private static final Logger LOG = LoggerFactory.getLogger(StampedDocumentCard.class);

    /** The folder the last PDF went to, where the next save dialog opens. */
    private static File lastPdfFolder;

    static final String CARD_NAME = "STAMPed Document";

    /** By document: the commit its repository stood at when this card last read it into the knowledge base. */
    private static final String PREF_READ_AT = "network.ike.komet.document.readAt.";
    private static final String PREF_DOCUMENT = "network.ike.komet.document.document";
    /** The working tree a document was last opened from, committed or exported into. */
    private static final String PREF_WORKING_TREE = "network.ike.komet.document.workingTree";
    private static final String PREF_VIEW = "network.ike.komet.document.view";
    /** Whether the Repository panel stands open on this card. */
    private static final String PREF_REPOSITORY_OPEN = "network.ike.komet.document.repositoryOpen";
    private static final String PREF_GRAPH_MODE = "network.ike.komet.document.graphMode";
    /** Whether the Graph view's Elements mode shows stored sentences as nodes (IKE-Network/ike-issues#1313). */
    private static final String PREF_GRAPH_SENTENCES = "network.ike.komet.document.graphSentences";
    /** In the Layer menu: sentences as nodes in Elements mode, or flattened into mixed content. */
    private final CheckMenuItem sentencesItem = new CheckMenuItem("Sentences in Elements mode");
    private static final String PREF_LAYER = "network.ike.komet.document.layer.";
    /** What the reader folded in a document, by the document's identity: the view's keys, one per fold. */
    private static final String PREF_FOLDS = "network.ike.komet.document.folds.";
    /**
     * The attribute names this card sets when it reads a document, kept by the document: with
     * {@code compendium} set, a book reads as the compendium reads the same topics.
     */
    private static final String PREF_ATTRIBUTES = "network.ike.komet.document.attributes.";
    /** In the More menu: the attributes the document's conditionals ask about, each to set for this card. */
    private final Menu attributesMenu = new Menu("Attributes set for this card");
    /** Between two folds' keys: a line break, since a key is one line of the document; the preferences are XML, which a control character would break. */
    private static final String FOLD_SEPARATOR = "\n";

    /** Live cards, most recently realized last, so tools and automation can find the one in use. */
    private static final List<WeakReference<StampedDocumentCard>> OPEN = new CopyOnWriteArrayList<>();

    /** @return the most recently realized card still alive, or {@code null} */
    public static StampedDocumentCard mostRecent() {
        StampedDocumentCard found = null;
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                found = card;
            }
        }
        return found;
    }

    /**
     * @param anchor a document's anchor
     * @return the live card showing the document, or {@code null}
     */
    public static StampedDocumentCard showing(PublicId anchor) {
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card != null && card.shown != null && PublicId.equals(card.shown.anchor(), anchor)) {
                return card;
            }
        }
        return null;
    }

    /** Re-reads the shown document in every live card, after something changed the store; call on the FX thread. */
    public static void reloadAll() {
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card == null) {
                OPEN.remove(ref);
            } else {
                card.refresh();
            }
        }
    }

    private final BorderPane host = new BorderPane();
    /**
     * The height the card's body asks for, whatever it shows. A card on the workspace stands as
     * tall as its content asks, up to the workspace's standard window height: left to the
     * document, a card of eleven source lines stood a third as tall as the same document's
     * pages, and changed its height with its view. With the tab and the toolbar above it, this
     * much body is that standard height, so the card has one height in every view and for every
     * document, until a person drags it to another.
     */
    private static final double BODY_HEIGHT = 966;
    /**
     * The host's center: the document area, which never leaves it, under the Graph view or the
     * pages while either shows. A layout area forgets itself, erasing its preferences node, when
     * its node leaves the scene, so swapping it out lost the print settings and failed every
     * later save of the card.
     */
    private final StackPane center = new StackPane();
    private final Label statusLine = new Label("Choose a document the store holds, or open a committed .adoc file.");
    /** The documents the store holds; a combo box so an empty one can say so rather than show a blank. */
    private final ComboBox<String> documents = new ComboBox<>();
    /** True while the chooser is being brought in line with a document already shown, so choosing it does not reopen it. */
    private boolean listingShown;
    /** Below this card width the toolbar's controls show their icons alone; above it, icon and name. */
    private static final double LABELED_TOOLBAR_WIDTH = 860;
    /** The same, while the Graph view's modes are on the toolbar too. */
    private static final double LABELED_TOOLBAR_WIDTH_WITH_MODES = 1120;
    /** The three views as one segmented control: exactly one selected, arrow keys move it. */
    private final SegmentedControl<DocumentSurfaceView.View> views = new SegmentedControl<>(
            List.of(DocumentSurfaceView.View.SOURCE, DocumentSurfaceView.View.READING, DocumentSurfaceView.View.PAGE_LAYOUT, DocumentSurfaceView.View.GRAPH),
            v -> switch (v) {
                case SOURCE -> "Source";
                case READING -> "Reading";
                case PAGE_LAYOUT -> "Page layout";
                case GRAPH -> "Graph";
            },
            v -> switch (v) {
                case SOURCE -> "The document as lines: number, introducing STAMP, mark, text";
                case READING -> "The document as a document";
                case PAGE_LAYOUT -> "The document on pages, as Print to PDF makes them through Typst: shown at once from JavaFX's own line breaks, settled on Typst's";
                case GRAPH -> "The document as the store holds it: its sections and blocks, their lines, the lines' references, each with its STAMP; an included document under its include line";
            },
            v -> switch (v) {
                case SOURCE -> "stampedDocumentSourceView";
                case READING -> "stampedDocumentReadingView";
                case PAGE_LAYOUT -> "stampedDocumentPageLayoutView";
                case GRAPH -> "stampedDocumentGraphView";
            },
            v -> switch (v) {
                case SOURCE -> "mdi2c-code-tags";
                case READING -> "mdi2b-book-open-outline";
                case PAGE_LAYOUT -> "mdi2f-file-document-outline";
                case GRAPH -> "mdi2f-file-tree-outline";
            });
    private PageStackView pageStack;
    /** The Graph view: a tree over the shown assembly, in the surface's place while it is chosen. */
    private DocumentGraphView graph;
    /** The Graph view's three modes as one segmented control, on the toolbar while the graph is shown. */
    private final SegmentedControl<DocumentGraphView.Mode> modes = new SegmentedControl<>(
            List.of(DocumentGraphView.Mode.LATEST, DocumentGraphView.Mode.HISTORY, DocumentGraphView.Mode.DOCBOOK),
            m -> switch (m) {
                case LATEST -> "Latest";
                case HISTORY -> "History";
                case DOCBOOK -> "Elements";
            },
            m -> switch (m) {
                case LATEST -> "The document as it stands";
                case HISTORY -> "With its history: a line's versions beneath it, the lines later commits retired where they stood, the commits that touched each block";
                case DOCBOOK -> "As elements, the way a structure editor shows a document: markup lines folded into the blocks they title and attribute, a paragraph's lines joined into text and inline elements";
            },
            m -> switch (m) {
                case LATEST -> "stampedDocumentGraphLatest";
                case HISTORY -> "stampedDocumentGraphHistory";
                case DOCBOOK -> "stampedDocumentGraphElements";
            },
            m -> switch (m) {
                case LATEST -> "mdi2c-clock-check-outline";
                case HISTORY -> "mdi2h-history";
                case DOCBOOK -> "mdi2f-format-section";
            });
    /** Each member's history, read once per open while the History mode asks for it. */
    private final Map<String, DocumentHistory> histories = new HashMap<>();
    /** What the store says each node of the graph is, read once per open as the Graph view asks. */
    private final Map<Long, Optional<StoredNodeFacts.NodeFacts>> facts = new HashMap<>();
    /** The page stack's units, and the two layouts of them: JavaFX's estimate, shown at once, and Typst's, settled on when it returns. */
    private List<LayoutOracle.Unit> pageUnits = List.of();
    /** The print model as the pages last measured it: its lists' entries carrying the pages settled on; {@code null} until settled. */
    private DocumentModel pageModel;
    /** How many passes the pages took to settle their lists' page numbers. */
    private int pagePasses;
    private LayoutOracle.Geometry pageGeometry;
    private LayoutOracle.Layout greedyLayout;
    private LayoutOracle.Layout typstLayout;
    private Path oracleFolder;
    /** Advanced at every page build; a Typst answer for an older build is dropped. */
    private int pageGeneration;
    private final Label settling = new Label("settling…");
    private final Label pageIndicator = new Label();
    private final MenuButton layerMenu = new MenuButton("Layer");
    private final MenuButton overflow = new MenuButton("More");
    /** Where a pending edit is settled: above the status line, and there only while one is pending or restorable. */
    private final PendingEditBar pendingBar = new PendingEditBar(new PendingEditBar.Host() {
        @Override
        public void commitPending(String message, boolean sign) {
            commitFromBar(message, sign);
        }

        @Override
        public void discardPending() {
            StampedDocumentCard.this.discardPending();
        }

        @Override
        public void restorePending() {
            StampedDocumentCard.this.restorePending();
        }

        @Override
        public void setSignsCommits(boolean sign) {
            setSignsAnnotations(sign);
        }
    });
    /** The lines edited and not committed, and the documents they are in, as the bar counts them. */
    private long editedLines;
    private int editedDocuments;
    /** The document the bar's message was typed for; another document starts with none. */
    private PublicId messageFor;
    private final Map<DocumentSurfaceView.Layer, CheckMenuItem> layerItems = new HashMap<>();
    private final Label footerIdentity = new Label();
    /** All the footer's line leaves out, for its tooltip. */
    private String statusDetail = "";
    /** The full SHA a click on the footer's identity copies, empty when the document names none. */
    private String footerSha = "";
    private VBox footer;
    private RepositoryPanel repositoryPanel;
    /** The strip that shows a file and its includes being materialized, with Pause, Resume and Stop. */
    private final MaterializeProgress materializing = new MaterializeProgress();
    private boolean pendingEdit;
    private DocumentSurfaceArea documentArea;
    private DocumentSurfaceView surface;
    private Map<String, PublicId> anchors = new LinkedHashMap<>();
    /** The host document: the one the person opened. */
    private StoredDocument shown;
    /** The time the shown document is read as of: {@link Long#MAX_VALUE} for the latest, else the view's past time. */
    private long shownAsOf = Long.MAX_VALUE;
    /** The document last opened, read again when the card refreshes, as when its view's time moves. */
    private PublicId reading;
    /** The host with its children shown in place. */
    private DocumentAssembly assembly;
    /** What was read for each member, by recorded path: committed numbers and text, pending edit, summaries. */
    private Map<String, Loaded> members = Map.of();
    /** Every member's summaries as one, for the surface's popovers and tooltips. */
    private CommitSummaries summaries = CommitSummaries.NONE;
    private String shownPath;
    private Path repoRoot;
    /** A report for the status line once the chooser's next open has drawn the document. */
    private String reportAfterOpen;
    /** What giving a file its repository did, for the report of the materialization that follows. */
    private String repositoryReport;
    /** A file asked for before the card was built. */
    private Path openWhenBuilt;
    private boolean built;

    private StampedDocumentCard(KometPreferences preferences) {
        super(preferences);
    }

    private StampedDocumentCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** The card's help: the stamped-document topic of this plugin's own help book. */
    static final KlHelpTopic HELP_TOPIC = network.ike.komet.document.help.DocumentHelp.STAMPED_DOCUMENT;

    @Override
    protected Optional<KlHelpTopic> helpTopic() {
        return Optional.of(HELP_TOPIC);
    }

    /** @return the name of the document shown, if one is */
    public Optional<String> currentDocument() {
        return Optional.ofNullable(documents.getValue());
    }

    /** @return the document as loaded from the store, if one is shown */
    public Optional<StoredDocument> shown() {
        return Optional.ofNullable(shown);
    }

    /** @return the working tree the shown document's file is known in, or {@code null} */
    public Path repoRoot() {
        return repoRoot;
    }

    /** @return the shown document's file relative to its repository root, when a working tree is known */
    public Optional<Path> currentFile() {
        return repoRoot == null || shownPath == null ? Optional.empty() : Optional.of(Path.of(shownPath));
    }

    /** @return the shown document's recorded path, which it has with no working tree too */
    @Override
    public Optional<String> documentPath() {
        return Optional.ofNullable(shownPath);
    }

    /**
     * @param file a file, by its full path
     * @return the live card showing the file, or {@code null}
     */
    public static StampedDocumentCard showingFile(Path file) {
        Path wanted = file.toAbsolutePath().normalize();
        for (WeakReference<StampedDocumentCard> ref : OPEN) {
            StampedDocumentCard card = ref.get();
            if (card != null && card.onScreen() && card.fileOnDisk().filter(wanted::equals).isPresent()) {
                return card;
            }
        }
        return null;
    }

    /**
     * @param root a working tree
     * @return the live cards on that working tree
     */
    static List<StampedDocumentCard> onRepository(Path root) {
        Path wanted = root.toAbsolutePath().normalize();
        return OPEN.stream().map(WeakReference::get)
                .filter(card -> card != null && card.onScreen() && card.repoRoot != null
                        && card.repoRoot.toAbsolutePath().normalize().equals(wanted))
                .toList();
    }

    /** @return whether the card is in a window: a closed one stays in {@link #OPEN} until it is collected */
    private boolean onScreen() {
        return fxObject().getScene() != null;
    }

    /** @return the shown document's file in the working tree, when one holds it */
    private Optional<Path> fileOnDisk() {
        return currentFile().map(relative -> repoRoot.resolve(relative).toAbsolutePath().normalize()).filter(Files::isRegularFile);
    }

    /** Selects the shown document's file in the journal's Documents tray, or in the Finder for a card outside a journal. */
    private void showInNavigator() {
        Optional<Path> file = fileOnDisk();
        if (file.isEmpty()) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
        } else if (!DocumentNavigatorTray.reveal(fxObject(), file.get())) {
            DocumentNavigatorTray.showInFinder(file.get());
        }
    }

    private void showInFinder() {
        Optional<Path> file = fileOnDisk();
        if (file.isEmpty()) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
        } else {
            DocumentNavigatorTray.showInFinder(file.get());
        }
    }

    /** @return whether this card shows exactly that file of that repository */
    public boolean showsFile(Path root, Path file) {
        return repoRoot != null && shownPath != null && repoRoot.equals(root) && Path.of(shownPath).equals(file);
    }

    /** @return the assembly shown: the host with its children in place, if a document is shown */
    public Optional<DocumentAssembly> assembly() {
        return Optional.ofNullable(assembly);
    }

    /**
     * @return the selected lines as 1-based inclusive ranges of the committed file they lie in
     *         (the host's or a child's); empty without a selection, or when it spans two members
     */
    public List<int[]> selectedLineNumbers() {
        return located().map(l -> l.local().ranges(o -> committedNumber(l.member(), o))).orElse(List.of());
    }

    /** @return the file the selection lies in, relative to the repository root: a child's or the host's */
    public Optional<Path> selectedFile() {
        return located().map(l -> Path.of(l.member().path()));
    }

    /** The selection located to its one member, if there is one and it lies in one member. */
    private Optional<DocumentAssembly.Located> located() {
        if (surface == null || assembly == null) {
            return Optional.empty();
        }
        return surface.selection().flatMap(assembly::locate);
    }

    /** The committed line number of a member's line by its index in that member, or 0. */
    private int committedNumber(DocumentAssembly.Member member, int index) {
        Loaded loaded = members.get(member.path());
        if (loaded == null || index < 0 || index >= member.document().lines().size()) {
            return 0;
        }
        return committedNumber(member.document().lines().get(index), loaded.committedNumbers());
    }

    /**
     * The 1-based number of a line in the committed file, or 0 when the file does not hold it or
     * its text shown is an uncommitted edit: an annotation names committed text, so an edited
     * line is annotated once the edit is committed, as the ledger's banner always said.
     *
     * @param line      the line as shown
     * @param committed the committed numbers by nid
     * @return the number, or 0
     */
    static int committedNumber(StoredLine line, Map<Long, Integer> committed) {
        return DocumentSurfaceView.uncommitted(line) ? 0 : committed.getOrDefault(line.nid(), 0);
    }

    /** The surface ordinal of the host's line with that committed number, or -1. */
    private int ordinalOf(int number) {
        return assembly == null ? -1 : ordinalOf(assembly.host(), number);
    }

    /** The surface ordinal of a member's line with that committed number, or -1. */
    private int ordinalOf(DocumentAssembly.Member member, int number) {
        Loaded loaded = members.get(member.path());
        if (loaded == null) {
            return -1;
        }
        for (Map.Entry<Long, Integer> e : loaded.committedNumbers().entrySet()) {
            if (e.getValue() == number) {
                return surface.ordinal(member, e.getKey());              // in that showing of the file, when it is shown more than once
            }
        }
        return -1;
    }

    /**
     * Selects a range of the host file's committed lines, 1-based inclusive, as a person's drag
     * would. Public so the assistant's hand-off and UI automation can select without the pointer.
     *
     * @param from the first line
     * @param to   the last line
     * @return whether any of the lines is shown
     */
    public boolean selectLines(int from, int to) {
        LineSelection wanted = LineSelection.covering(List.of(new int[] {from, to}), this::ordinalOf);
        if (wanted == null) {
            return false;
        }
        surface.select(wanted);
        return true;
    }

    /**
     * Selects words in the view shown by what they say, as a drag across them does: for
     * automation, which has no pointer to drag.
     *
     * @param words the words as the view draws them, in one paragraph of one text
     * @param nth   which occurrence of them in the view, from 0
     * @return whether they were found and selected
     */
    public boolean selectWords(String words, int nth) {
        return surface != null && surface.selectWords(words, nth);
    }

    /**
     * Writes a mark over the words selected, as its key or the selection's bar does, for automation.
     *
     * @param mark the mark's name: BOLD, ITALIC, UNDERLINE, STRIKE, MONO, HIGHLIGHT or LINK
     * @return what the status line says once the mark is asked for
     */
    public String markSelection(String mark) {
        surface.markSelection(InlineMarks.valueOf(mark.strip().toUpperCase(java.util.Locale.ROOT)));
        return statusLine.getText();
    }

    /**
     * Selects a range of a member file's committed lines — the host's or a child's shown in
     * place — 1-based inclusive.
     *
     * @param file the file, relative to the repository root
     * @param from the first line
     * @param to   the last line
     * @return whether any of the lines is shown
     */
    public boolean selectLines(Path file, int from, int to) {
        if (assembly == null) {
            return false;
        }
        String path = file.toString().replace('\\', '/');
        // a file an abstract and the body both show: the lines are selected in the first showing that reads them
        for (DocumentAssembly.Member member : assembly.showings(path)) {
            LineSelection wanted = LineSelection.covering(List.of(new int[] {from, to}), n -> ordinalOf(member, n));
            if (wanted != null) {
                surface.select(wanted);
                return true;
            }
        }
        return false;
    }

    // ── toolbar ───────────────────────────────────────────────────────────────

    @Override
    protected void buildToolbarControls(HBox toolBar) {
        documents.setId("stampedDocumentChooser");
        documents.setTooltip(new Tooltip("The documents the knowledge base holds"));
        documents.setMaxWidth(300);
        documents.getStyleClass().add("stamped-toolbar-control");
        documents.setPromptText("No documents");
        documents.getItems().addListener((javafx.collections.ListChangeListener<String>) change ->
                documents.setPromptText(documents.getItems().isEmpty() ? "No documents" : "Choose a document"));
        documents.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (listingShown) {
                return;                                    // the chooser catching up with a document already shown
            }
            if (is != null && anchors.containsKey(is)) {
                preferences().put(PREF_DOCUMENT, is);
                syncPreferences();
                String report = reportAfterOpen;
                reportAfterOpen = null;
                open(anchors.get(is), report, null);
            }
        });
        views.setId("stampedDocumentViews");
        views.valueProperty().addListener((obs, was, is) -> {
            preferences().put(PREF_VIEW, is.name());
            syncPreferences();
            if (surface != null) {
                surface.setView(is);
                showCenter(is);
                find(false);                                       // the matches are of the view shown
            }
        });
        modes.setId("stampedDocumentGraphModes");
        modes.setVisible(false);
        modes.setManaged(false);
        modes.valueProperty().addListener((obs, was, is) -> {
            preferences().put(PREF_GRAPH_MODE, is.name());
            syncPreferences();
            if (graph != null) {
                graph.setMode(is);
            }
        });
        layerMenu.setId("stampedDocumentLayerMenu");
        Icons.iconOnly(layerMenu, "mdi2l-layers-outline", "What the Provenance layer draws over the document");
        layerMenu.getStyleClass().add("stamped-toolbar-control");
        for (DocumentSurfaceView.Layer layer : DocumentSurfaceView.Layer.values()) {
            CheckMenuItem item = new CheckMenuItem(switch (layer) {
                case DATES -> "Dates";
                case STAMPS -> "Stamps";
                case COMMENTS -> "Comments";
                case TINTS -> "Commit tints";
                case REVIEW_QUEUE -> "Review queue";
                case ANNOTATION_MARKS -> "Annotation marks";
                case LEFT_OUT -> "Passages left out";
            });
            item.setId("stampedDocumentLayer" + layer.name());
            item.setSelected(preferences().getBoolean(PREF_LAYER + layer.name(), layer != DocumentSurfaceView.Layer.TINTS && layer != DocumentSurfaceView.Layer.REVIEW_QUEUE
                    && layer != DocumentSurfaceView.Layer.LEFT_OUT));
            item.selectedProperty().addListener((obs, was, is) -> {
                preferences().putBoolean(PREF_LAYER + layer.name(), is);
                syncPreferences();
                if (surface != null) {
                    surface.setLayer(layer, is);
                    if (layer == DocumentSurfaceView.Layer.LEFT_OUT && assembly != null) {
                        // this layer changes what the Reading view is made of: the document is drawn again, where the reader stood
                        surface.show(assembly);
                        find(false);
                        if (surface.view() == DocumentSurfaceView.View.PAGE_LAYOUT) {
                            buildPages();
                        }
                    }
                }
                if (graph != null) {
                    graph.relayer();
                }
            });
            layerItems.put(layer, item);
            layerMenu.getItems().add(item);
        }
        sentencesItem.setId("stampedDocumentGraphSentences");
        sentencesItem.setSelected(preferences().getBoolean(PREF_GRAPH_SENTENCES, true));
        sentencesItem.selectedProperty().addListener((obs, was, is) -> {
            preferences().putBoolean(PREF_GRAPH_SENTENCES, is);
            syncPreferences();
            if (graph != null) {
                graph.setSentences(is);
            }
        });
        layerMenu.getItems().addAll(new SeparatorMenuItem(), sentencesItem);
        overflow.setId("stampedDocumentOverflow");
        Icons.iconOnly(overflow, "mdi2d-dots-horizontal", "Verbs that act on the whole document");
        overflow.getStyleClass().add("stamped-toolbar-control");
        overflow.getItems().addAll(
                item("Find… (⌘F)", "stampedDocumentFind", "Find text in the document: Return goes to the next match, Shift-Return to the one before", this::showFind),
                item("Find and Replace… (⌥⌘F)", "stampedDocumentFindReplace", "Find text and put other words in its place, one match at a time or all of them", this::showReplace),
                new SeparatorMenuItem(),
                item("Show in Navigator", "stampedDocumentShowInNavigator", "Select the document's file in the journal's Documents tray", this::showInNavigator),
                item("Show in Finder", "stampedDocumentShowInFinder", "Show the document's file in the Finder", this::showInFinder),
                new SeparatorMenuItem(),
                item("Open file…", "stampedDocumentOpenFile", "Choose an .adoc file; it is materialized if the store lacks it, and one with no commit yet is offered a repository and a first commit", this::chooseFile),
                item("Refresh", "stampedDocumentRefresh", "Re-read the documents the store holds, and the one shown", this::refresh),
                item("Export…", "stampedDocumentExport", "Write the document from the store back into a repository's working tree at its recorded path, and compare it with HEAD", this::chooseExportTarget),
                item("Print to PDF…", "stampedDocumentPrintPdf", "Save the document, with the documents it includes, as a PDF where you choose, compiled by your installed typst, and open it", this::chooseAndPrint),
                new SeparatorMenuItem(),
                item("Materialize", "stampedDocumentMaterialize", "Write the commits the knowledge base lacks of this file and of every file it includes, each as its own document", this::materialize),
                item("Rebuild from Git", "stampedDocumentRebuild", "Drop this document from the knowledge base and materialize it again from git, which holds all an ephemeral document is", this::rebuildFromGit),
                item("Remove Superseded Documents", "stampedDocumentRemoveSuperseded", "Delete the prose-element documents that vertex documents of the same working tree and path replace", this::removeSuperseded),
                new SeparatorMenuItem(),
                item("Show history", "stampedDocumentHistory", "The STAMPs of the selected lines, or of the whole document, in order", this::showHistory),
                new SeparatorMenuItem(),
                item("Go to page…", "stampedDocumentGoToPage", "Scroll the Page layout to a page by its number", this::goToPage));
        attributesMenu.setId("stampedDocumentAttributes");
        overflow.getItems().addAll(new SeparatorMenuItem(), attributesMenu);
        fillAttributesMenu();
        // Nothing here settles a pending edit: that is the bar's, at the bottom of the card.
        toolBar.getChildren().addAll(coordinateButton(), documents, views, modes, layerMenu, overflow);
        // Names beside the icons while the card is wide enough for them; icons alone when it is not.
        fxObject().widthProperty().addListener((obs, was, is) -> labelToolbar());
        modes.visibleProperty().addListener((obs, was, is) -> labelToolbar());
        labelToolbar();
    }

    /**
     * The card's view coordinates, as the assistant card shows them: the view the document is read
     * under, its time among them. Moving the time refreshes the card, as any change of its view
     * does, which reads the document again as it stood then.
     */
    private MenuButton coordinateButton() {
        MenuButton button = new MenuButton();
        button.setId("stampedDocumentCoordinates");
        button.getStyleClass().add("coordinate");
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setTooltip(new Tooltip("Coordinates: the view the document is read under; its time reads the document as it stood then"));
        if (getCardViewProperties() == null) {
            button.setDisable(true);
            return button;
        }
        ViewOptionsPopupHelper.setupViewCoordinateOptionsPopup(getCardViewProperties(),
                FilterOptionsPopup.FILTER_TYPE.CHAPTER_WINDOW, fxObject(), button, () -> { });
        wireCoordinateOverrideIndicator(button);
        return button;
    }

    /** Shows each toolbar control's name beside its icon when the card is wide enough, its icon alone otherwise. */
    private void labelToolbar() {
        double needed = modes.isVisible() ? LABELED_TOOLBAR_WIDTH_WITH_MODES : LABELED_TOOLBAR_WIDTH;
        boolean compact = fxObject().getWidth() < needed;
        views.setCompact(compact);
        modes.setCompact(compact);
        Icons.compact(layerMenu, compact);
        Icons.compact(overflow, compact);
    }

    // ── find ──────────────────────────────────────────────────────────────────

    private final HBox replaceBar = new HBox(6);
    private final javafx.scene.control.TextField replaceField = new javafx.scene.control.TextField();
    /** What a replacement does once its edit is saved and the document is read again; null when none is under way. */
    private Runnable afterRead;
    private int replaced;
    private int leftAsIs;
    private network.ike.komet.document.surface.DocumentSurface.Viewpoint placeBeforeReplacing;
    private final HBox findBar = new HBox(6);
    private final javafx.scene.control.TextField findField = new javafx.scene.control.TextField();
    private final Label findCount = new Label();
    /** The matches of the text in the bar, in reading order, and which of them is shown. */
    private List<DocumentSurfaceView.Match> findMatches = List.of();
    private int findAt = -1;

    /**
     * The bar Command-F opens beneath the Repository line: a field, where the match shown stands
     * among the matches, the match before, the next, and close. It finds as the text is typed,
     * from where the reader is; Return goes on to the next match and Shift-Return back.
     */
    private void buildFindBar() {
        findBar.setId("stampedDocumentFindBar");
        findBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        findBar.setPadding(new Insets(4, 12, 4, 12));
        findField.setId("stampedDocumentFindField");
        findField.setPromptText("Find in the document");
        HBox.setHgrow(findField, javafx.scene.layout.Priority.ALWAYS);
        findCount.setId("stampedDocumentFindCount");
        findCount.setStyle("-fx-text-fill: #5f6b7a;");
        findCount.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        Button previous = findButton("▲", "stampedDocumentFindPrevious", "The match before (Shift-Return)", () -> stepFind(-1));
        Button next = findButton("▼", "stampedDocumentFindNext", "The next match (Return)", () -> stepFind(1));
        Button close = findButton("✕", "stampedDocumentFindClose", "Close (Esc)", this::hideFind);
        findField.textProperty().addListener((obs, was, is) -> find(true));
        findField.setOnAction(e -> stepFind(1));
        findField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                hideFind();
                e.consume();
            } else if (e.getCode() == javafx.scene.input.KeyCode.ENTER && e.isShiftDown()) {
                stepFind(-1);
                e.consume();
            }
        });
        findBar.getChildren().setAll(findField, findCount, previous, next, close);
        findBar.setVisible(false);
        findBar.setManaged(false);
        replaceBar.setId("stampedDocumentReplaceBar");
        replaceBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        replaceBar.setPadding(new Insets(0, 12, 4, 12));
        replaceField.setId("stampedDocumentReplaceField");
        replaceField.setPromptText("Replace with");
        HBox.setHgrow(replaceField, javafx.scene.layout.Priority.ALWAYS);
        Button one = findButton("Replace", "stampedDocumentReplace", "Put these words in place of the match shown, and go to the next (Return)", this::replaceFound);
        Button all = findButton("Replace All", "stampedDocumentReplaceAll", "Put these words in place of every match, as edits you can commit or discard", this::replaceAll);
        replaceField.setOnAction(e -> replaceFound());
        replaceField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                hideFind();
                e.consume();
            }
        });
        replaceBar.getChildren().setAll(replaceField, one, all);
        replaceBar.setVisible(false);
        replaceBar.setManaged(false);
    }

    private static Button findButton(String text, String id, String tooltip, Runnable action) {
        Button button = new Button(text);
        button.setId(id);
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** Command-F, or the overflow menu's Find…: opens the bar with its text selected, and finds that text again. */
    private void showFind() {
        if (surface == null) {
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        findField.requestFocus();
        findField.selectAll();
        find(true);
    }

    /** Option-Command-F, or the overflow menu's Find and Replace…: the find bar with the row that replaces beneath it. */
    private void showReplace() {
        if (surface == null) {
            return;
        }
        replaceBar.setVisible(true);
        replaceBar.setManaged(true);
        showFind();
    }

    /**
     * Replace: the match shown takes the words of the replace field, as an edit of its block that
     * Commit records and Discard takes back; once the document is read again, the next match is
     * shown. With no match shown yet, the first is shown and nothing is replaced.
     */
    private void replaceFound() {
        if (surface == null || afterRead != null || findMatches.isEmpty()) {
            return;
        }
        if (findAt < 0) {
            stepFind(1);
            return;
        }
        var match = findMatches.get(findAt);
        if (surface.replaceMatch(match, replaceField.getText())) {
            afterRead = () -> {
                // the match is gone: the next is the first that stands at or after where it stood
                find(false);
                for (int i = 0; i < findMatches.size(); i++) {
                    if (!findMatches.get(i).before(match)) {
                        findAt = i;
                        showFindMatch();
                        return;
                    }
                }
                if (!findMatches.isEmpty()) {
                    findAt = 0;
                    showFindMatch();
                }
            };
        }
    }

    /**
     * Replace All: every match takes the words of the replace field, one edit after another, from
     * the last match to the first, so that words put in which hold the text found are not found
     * again. A match that cannot be placed in its source, as one in a caption or a heading, is
     * left, and the status says how many were.
     */
    private void replaceAll() {
        if (surface == null || afterRead != null || findMatches.isEmpty()) {
            return;
        }
        replaced = 0;
        leftAsIs = 0;
        replaceBar.setDisable(true);
        placeBeforeReplacing = surface.place();
        replaceBefore(null);
    }

    private void replaceBefore(DocumentSurfaceView.Match last) {
        String text = findField.getText() == null ? "" : findField.getText();
        findMatches = surface.find(text);
        for (int i = findMatches.size() - 1; i >= 0; i--) {
            var match = findMatches.get(i);
            if (last != null && !match.before(last)) {
                continue;
            }
            if (surface.replaceMatch(match, replaceField.getText())) {
                replaced++;
                findCount.setText("Replacing… " + replaced);
                afterRead = () -> replaceBefore(match);
                return;
            }
            leftAsIs++;
        }
        replaceBar.setDisable(false);
        find(false);
        surface.clearMatch();
        surface.restore(placeBeforeReplacing);                         // the reader is put back where they were reading
        placeBeforeReplacing = null;
        statusLine.setText(replaced + (replaced == 1 ? " match replaced" : " matches replaced")
                + (leftAsIs == 0 ? "." : "; " + leftAsIs + (leftAsIs == 1 ? " left as it stands" : " left as they stand")
                + ", in a caption, a heading, a table cell or a marked phrase: Edit block changes " + (leftAsIs == 1 ? "it." : "those."))
                + (replaced == 0 ? "" : " Commit records the edits; Discard… takes them back."));
    }

    private void hideFind() {
        replaceBar.setVisible(false);
        replaceBar.setManaged(false);
        findBar.setVisible(false);
        findBar.setManaged(false);
        findMatches = List.of();
        findAt = -1;
        if (surface != null) {
            surface.clearMatch();
        }
    }

    /**
     * Finds the bar's text in the view shown. With {@code show}, the first match at or after the
     * block at the top of the viewport is shown; without, the matches are counted again and the
     * reader stays put, as after the document is read again.
     */
    private void find(boolean show) {
        if (surface == null || !findBar.isVisible()) {
            return;
        }
        String text = findField.getText() == null ? "" : findField.getText();
        DocumentSurfaceView.View view = surface.view();
        if (view == DocumentSurfaceView.View.PAGE_LAYOUT || view == DocumentSurfaceView.View.GRAPH) {
            findMatches = List.of();
            findAt = -1;
            findCount.setText("Find reads the Reading and Source views");
            return;
        }
        findMatches = surface.find(text);
        if (findMatches.isEmpty()) {
            findAt = -1;
            findCount.setText(text.isBlank() ? "" : "No matches");
            surface.clearMatch();
            return;
        }
        if (!show) {
            findAt = -1;
            findCount.setText(findMatches.size() == 1 ? "1 match" : findMatches.size() + " matches");
            return;
        }
        int top = surface.topBlock();
        findAt = 0;
        for (int i = 0; i < findMatches.size(); i++) {
            if (findMatches.get(i).blockIndex() >= top) {
                findAt = i;
                break;
            }
        }
        showFindMatch();
    }

    /** Goes to the next match, or the one before, around the ends. */
    private void stepFind(int by) {
        if (findMatches.isEmpty() || surface == null) {
            return;
        }
        findAt = findAt < 0 ? (by > 0 ? 0 : findMatches.size() - 1) : Math.floorMod(findAt + by, findMatches.size());
        showFindMatch();
    }

    private void showFindMatch() {
        surface.showMatch(findMatches.get(findAt));
        findCount.setText((findAt + 1) + " of " + findMatches.size());
    }

    private static MenuItem item(String text, String id, String tooltip, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        item.setOnAction(e -> action.run());
        return item;
    }

    @Override
    protected void renderContent() {
        if (built) {
            return;
        }
        built = true;
        OPEN.add(new WeakReference<>(this));
        // The Document surface (#808) as a KlArea (#839), on a stable child preferences node, as the
        // Claude card hosts it: the area owns the print settings and the expand-to-paged-preview
        // affordance; the view stacks the document's blocks on the area's surface. Named by the class's simple
        // name: node(Class) is the class's package path, one absolute node every card shared.
        documentArea = DocumentSurfaceArea.restore(preferences().node(DocumentSurfaceArea.class.getSimpleName()));
        // The pages' running head: the document's title, as the Reading view's header and the PDF name it; the
        // store's name for the document before a title is known.
        documentArea.setRunningHeadSupplier(() -> shown != null && !shown.attributes().isEmpty() && !shown.attributes().getFirst().isBlank()
                ? shown.attributes().getFirst() : documents.getValue() == null ? "" : documents.getValue());
        documentArea.restoreSettings();
        surface = new DocumentSurfaceView(this, documentArea.surface());
        graph = new DocumentGraphView(graphHost());
        documentArea.surface().setPrintSource(this::printSource);
        for (Map.Entry<DocumentSurfaceView.Layer, CheckMenuItem> e : layerItems.entrySet()) {
            surface.setLayer(e.getKey(), e.getValue().isSelected());
        }
        String rememberedView = preferences().get(PREF_VIEW, "");
        for (DocumentSurfaceView.View candidate : DocumentSurfaceView.View.values()) {
            if (candidate.name().equals(rememberedView)) {
                views.select(candidate);
            }
        }
        if (views.getValue() != surface.view()) {
            surface.setView(views.getValue());
        }
        String rememberedMode = preferences().get(PREF_GRAPH_MODE, "");
        for (DocumentGraphView.Mode candidate : DocumentGraphView.Mode.values()) {
            if (candidate.name().equals(rememberedMode)) {
                modes.select(candidate);
            }
        }
        graph.setSentences(sentencesItem.isSelected());
        graph.setMode(modes.getValue());
        statusLine.setId("stampedDocumentStatus");
        statusLine.setWrapText(true);
        statusLine.setMaxWidth(Double.MAX_VALUE);
        statusLine.textProperty().addListener((obs, was, is) -> statusLine.setTooltip(null));   // the detail is the shown document's alone
        HBox.setHgrow(statusLine, javafx.scene.layout.Priority.ALWAYS);
        footerIdentity.setId("stampedDocumentFooterIdentity");
        footerIdentity.setStyle("-fx-text-fill: #5f6b7a;");
        footerIdentity.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        footerIdentity.setOnMouseClicked(e -> {
            if (!footerSha.isEmpty()) {
                javafx.scene.input.ClipboardContent sha = new javafx.scene.input.ClipboardContent();
                sha.putString(footerSha);
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(sha);
            }
        });
        settling.setId("stampedDocumentSettling");
        settling.setStyle("-fx-text-fill: #8a8782; -fx-font-style: italic;");
        settling.setTooltip(new Tooltip("Typst is laying the pages out; the lines re-flow to its breaks when it answers"));
        settling.setVisible(false);
        settling.setManaged(false);
        pageIndicator.setId("stampedDocumentPageIndicator");
        pageIndicator.setStyle("-fx-text-fill: #5f6b7a;");
        pageIndicator.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        pageIndicator.setVisible(false);
        pageIndicator.setManaged(false);
        HBox status = new HBox(12, statusLine, settling, pageIndicator, footerIdentity);
        status.setId("stampedDocumentFooter");
        status.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        status.setPadding(new Insets(4, 12, 4, 12));
        // The bar that settles a pending edit stands on the status line and goes where it goes.
        VBox footer = new VBox(pendingBar, status);
        footer.setId("stampedDocumentFooterArea");
        this.footer = footer;
        documentArea.surface().setFooter(footer);
        repositoryPanel = new RepositoryPanel(this);
        repositoryPanel.setExpanded(preferences().getBoolean(PREF_REPOSITORY_OPEN, false));
        repositoryPanel.expandedProperty().addListener((obs, was, is) -> {
            preferences().putBoolean(PREF_REPOSITORY_OPEN, is);
            syncPreferences();
        });
        buildFindBar();
        host.setTop(new VBox(repositoryPanel, materializing, findBar, replaceBar));
        center.getChildren().setAll(documentArea.fxObject());
        host.setCenter(center);
        host.setBottom(null);
        host.setPrefHeight(BODY_HEIGHT);
        documentArea.surface().setVerticalBarAlways(true);         // the bar stands in a card that does not shrink to a short document
        // On the card's root, not its body: the toolbar is part of the card's chrome, outside the
        // body, and was left in JavaFX's default look while the sheets sat on the body alone.
        for (String sheet : List.of("/network/ike/komet/document/stamped-document.css")) {
            URL css = StampedDocumentCard.class.getResource(sheet);
            if (css != null && !fxObject().getStylesheets().contains(css.toExternalForm())) {
                fxObject().getStylesheets().add(css.toExternalForm());
            }
        }
        setCardContent(host);
        // Find has no button: the toolbar has no room for one. The keys work wherever the focus is in the card.
        fxObject().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && !e.isAltDown() && e.getCode() == javafx.scene.input.KeyCode.F) {
                showFind();
                e.consume();
            } else if (e.isShortcutDown() && e.isAltDown() && e.getCode() == javafx.scene.input.KeyCode.F) {
                showReplace();
                e.consume();
            } else if (e.isShortcutDown() && e.getCode() == javafx.scene.input.KeyCode.G && findBar.isVisible()) {
                stepFind(e.isShiftDown() ? -1 : 1);
                e.consume();
            }
        });
        String remembered = preferences().get(PREF_WORKING_TREE, "");
        if (!remembered.isEmpty() && Files.isDirectory(Path.of(remembered))) {
            repoRoot = Path.of(remembered);
            DocumentNarratives.workingTreeOpened(repoRoot);
        }
        repositoryPanel.refresh();
        if (openWhenBuilt != null) {
            Path file = openWhenBuilt;
            openWhenBuilt = null;
            Platform.runLater(() -> open(file));                   // it may ask for a repository, and a dialog cannot wait in a layout pass
            return;
        }
        refresh();
    }

    // ── the Host ──────────────────────────────────────────────────────────────

    /**
     * The time the card's view reads at: {@link Long#MAX_VALUE} for the latest, else a past time,
     * which the document is read as of (ike-lab-documents topic {@code dev-decision-document},
     * "Time travel").
     *
     * @return the view's time
     */
    long asOf() {
        return view().viewCoordinateRecord().stampCoordinate().stampPosition().time();
    }

    /** The card's view, or the default one before the card is hosted. */
    @Override
    public ViewCalculator view() {
        return getCardViewProperties() != null ? getCardViewProperties().calculator() : Calculators.View.Default();
    }

    @Override
    public CommitSummaries summaries() {
        return summaries;
    }

    /**
     * Where a document's picture files are read from: the working tree the card was given, and
     * the document library, which is the tree a document chosen from the store was most likely
     * written in when the card has been given none.
     */
    @Override
    public List<Path> workingTrees() {
        List<Path> trees = new ArrayList<>();
        if (repoRoot != null) {
            trees.add(repoRoot);
        }
        Path library = DocumentLibrary.root();
        if (library != null && !trees.contains(library)) {
            trees.add(library);
        }
        return trees;
    }

    @Override
    public void status(String text) {
        statusLine.setText(text);
    }

    @Override
    public void selectionChanged() {
        // The acts on a selection live in its context menu; nothing on the toolbar changes. The graph follows it.
        if (graph != null) {
            graph.showSelection();
        }
    }

    @Override
    public void showingMember(DocumentAssembly.Member member) {
        if (member == null) {
            return;
        }
        String commit = member.document().commit();
        String file = member.path().isEmpty() ? documents.getValue() : member.path();
        // The file's name alone: its whole path is in the Repository title, and here it squeezed the line into a thin column.
        String name = Path.of(file).getFileName().toString();
        String shortSha = commit.substring(0, Math.min(7, commit.length()));
        footerSha = commit;
        Optional<StampEntity<?>> stamp = GitDocumentMaterializer.commitStamp(member.document().anchor(), commit);
        if (stamp.isEmpty()) {
            // No STAMP names the commit, so no identicon would match the margin's: the SHA says it.
            footerIdentity.setText(commit.isEmpty() ? name : name + " @ " + shortSha);
            footerIdentity.setGraphic(null);
            footerIdentity.setCursor(null);
            footerIdentity.setTooltip(new Tooltip(commit.isEmpty() ? file : file + "\n" + commit + "\nClick to copy the SHA"));
            return;
        }
        javafx.scene.image.ImageView badge = new javafx.scene.image.ImageView(surface.badge(stamp.get()));
        badge.setPreserveRatio(true);
        badge.setFitHeight(16);
        HBox mark = new HBox(2, new dev.ikm.komet.framework.controls.StampSigil(16), badge);
        mark.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        footerIdentity.setGraphic(mark);
        footerIdentity.setText(name);
        footerIdentity.setCursor(javafx.scene.Cursor.HAND);
        footerIdentity.setTooltip(new Tooltip(file + "\n" + shortSha + " · " + StampNames.name(view(), stamp.get().authorNid()) + " · "
                + DateTimeUtil.format(stamp.get().time()) + summaries.summary(stamp.get().nid()).map(s -> "\n" + s).orElse("")
                + "\nClick to copy the full SHA"));
    }

    // ── loading ───────────────────────────────────────────────────────────────

    /** Re-reads the store's document list and reopens the chosen document. */
    public void refresh() {
        String wanted = documents.getValue() != null ? documents.getValue() : preferences().get(PREF_DOCUMENT, "");
        CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Could not list the store's documents: " + failure.getMessage());
                LOG.warn("Listing stored documents failed", failure);
                return;
            }
            anchors = found;
            documents.getItems().setAll(found.keySet());
            if (found.isEmpty()) {
                shown = null;
                statusLine.setText("The knowledge base holds no materialized documents yet. Open file… materializes one.");
            } else if (found.containsKey(wanted)) {
                if (wanted.equals(documents.getValue())) {
                    open(found.get(wanted));
                } else {
                    documents.setValue(wanted);
                }
            } else if (reading != null) {
                open(reading);                                 // a document opened directly, which the chooser may not name
            } else {
                statusLine.setText(found.size() + " document(s) in the store; choose one.");
            }
        }));
    }

    /**
     * Shows a document by its anchor, reading it from the store off the FX thread.
     *
     * @param anchor the document anchor's id
     */
    public void open(PublicId anchor) {
        reading = anchor;
        if (surface == null) {
            return;                                                // not realized yet: its first refresh opens it
        }
        open(anchor, null, null);
        listShown(anchor);
    }

    /**
     * Shows a document in the Reading view, where a structured block draws: how a new card beside
     * the assistant opens, whatever view a card last remembered.
     *
     * @param anchor the document anchor's id
     */
    public void openReading(PublicId anchor) {
        views.select(DocumentSurfaceView.View.READING);
        open(anchor);
    }

    /**
     * Opens a file in the Reading view, as a card opened from the Documents tray does.
     *
     * @param file the file
     */
    public void openReading(Path file) {
        views.select(DocumentSurfaceView.View.READING);
        if (surface == null) {
            openWhenBuilt = file;                                  // not realized yet: its first refresh opens it
            return;
        }
        open(file);
    }

    /**
     * Lists a document the card shows but its chooser does not, as one opened directly is: the
     * store's documents are read again and the shown one chosen, without opening it a second time,
     * so the chooser never says "No documents" over a document on the card.
     */
    private void listShown(PublicId anchor) {
        Optional<String> known = anchors.entrySet().stream().filter(entry -> PublicId.equals(entry.getValue(), anchor))
                .map(Map.Entry::getKey).findFirst();
        if (known.isPresent()) {
            preferences().put(PREF_DOCUMENT, known.get());     // so the card reopens it, as it does a chosen one
            syncPreferences();
            return;
        }
        CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                LOG.warn("Listing stored documents failed", failure);
                return;
            }
            anchors = found;
            listingShown = true;
            try {
                documents.getItems().setAll(found.keySet());
                found.forEach((name, id) -> {
                    if (PublicId.equals(id, anchor)) {
                        documents.setValue(name);
                        preferences().put(PREF_DOCUMENT, name);   // so the card reopens it, as it does a chosen one
                        syncPreferences();
                    }
                });
            } finally {
                listingShown = false;
            }
        }));
    }

    /**
     * What one open reads for one member off the FX thread.
     *
     * @param document         the document with any pending edit in it
     * @param committedNumbers the 1-based line number of each committed line, by nid
     * @param committedLines   the committed text, line by line: what the file at HEAD must equal for a trailer to anchor
     * @param path             the recorded path, or {@code null} when the store holds no git source
     * @param pending          whether an edit of it is pending in Komet
     * @param restorable       whether an edit of it that was discarded, or left out of a commit, can be restored
     * @param summaries        the commit subjects of its STAMPs
     */
    private record Loaded(StoredDocument document, Map<Long, Integer> committedNumbers, List<String> committedLines, String path,
                          boolean pending, boolean restorable, CommitSummaries summaries) {

        /**
         * Reads one document as of a time; off the FX thread. The committed text is read again only
         * when an edit is pending; a reading of a past time has none.
         */
        static Optional<Loaded> read(PublicId anchor, Path root, boolean host, long asOf) {
            Optional<StoredDocument> loaded = GitDocumentMaterializer.load(anchor, true, asOf);
            if (loaded.isEmpty()) {
                return Optional.empty();
            }
            boolean pending = asOf == Long.MAX_VALUE && DocumentEditor.pending(anchor).isPresent();
            StoredDocument committed = pending ? GitDocumentMaterializer.load(anchor, false).orElse(loaded.get()) : loaded.get();
            Map<Long, Integer> numbers = new HashMap<>();
            for (int i = 0; i < committed.lines().size(); i++) {
                numbers.put(committed.lines().get(i).nid(), i + 1);
            }
            String path = GitDocumentMaterializer.recordedPath(anchor).orElse(null);
            Path relative = path == null ? null : Path.of(path);
            boolean rootHoldsFile = root != null && relative != null && Files.isRegularFile(root.resolve(relative));
            return Optional.of(new Loaded(loaded.get(), numbers, committed.lines().stream().map(StoredLine::text).toList(), path,
                    pending, host && asOf == Long.MAX_VALUE && !DocumentEditor.restorable(anchor).isEmpty(),
                    CommitSummaries.read(anchor, rootHoldsFile ? root : null, relative)));
        }
    }

    /** What one open reads off the FX thread: the host, its children by path, the assembly over them, and the time they are read as of. */
    private record Opened(Loaded host, Map<String, Loaded> members, DocumentAssembly assembly, long asOf) { }

    /**
     * Reads the host and every document its includes resolve to, by recorded path: under a known
     * working tree the child's anchor is the identity its file materializes under there, or under
     * a working tree beside it when the include names a library another repository holds
     * ({@link WorkingTrees}, topic {@code dev-adoc-packages}); without a known tree, the store's
     * documents of that path (the first when two repositories hold it). An include that names a
     * file under the AsciiDoc roots is matched against the committed paths of the known tree and
     * then of its neighbours, each listed once with one git call, and never against a listing of
     * the whole store while a tree is known.
     */
    private static Opened read(PublicId anchor, Path root, long asOf, List<String> set) {
        return read(anchor, root, asOf, set, Map.of(), null);
    }

    /**
     * As {@link #read(PublicId, Path, long, List)}, for a document already read whose files are
     * known to be as they were but for one: the others are taken as they were read, and only the
     * one that changed is read from the store.
     *
     * @param asRead   the files as last read, by path; empty to read every file
     * @param hostPath the host's path among them, or null to read the host
     */
    private static Opened read(PublicId anchor, Path root, long asOf, List<String> set, Map<String, Loaded> asRead, String hostPath) {
        Optional<Loaded> host = hostPath != null && asRead.containsKey(hostPath) ? Optional.of(asRead.get(hostPath)) : Loaded.read(anchor, root, true, asOf);
        if (host.isEmpty()) {
            if (asOf == Long.MAX_VALUE) {
                return null;
            }
            // the document did not exist yet at that time: nothing is shown as it stood then, rather than what it is now
            StoredDocument nothing = StoredDocument.nothing(anchor);
            DocumentAssembly empty = DocumentAssembly.single(nothing, "");
            Loaded before = new Loaded(nothing, Map.of(), List.of(), null, false, false, CommitSummaries.NONE);
            return new Opened(before, Map.of(empty.host().path(), before), empty, asOf);
        }
        Map<String, Loaded> loaded = new LinkedHashMap<>();
        asRead.forEach((path, file) -> {
            if (!path.equals(hostPath)) {
                loaded.put(path, file);
            }
        });
        List<Path> trees = new ArrayList<>();
        if (root != null) {
            trees.add(root);
            trees.addAll(WorkingTrees.beside(root).trees());
        }
        Map<Path, List<String>> committed = new HashMap<>();
        java.util.function.Function<Path, List<String>> committedPaths = tree -> committed.computeIfAbsent(tree, t -> {
            try {
                return GitOperations.committedPaths(t);
            } catch (IOException e) {
                LOG.warn("Listing the committed files of {} failed: {}", t, e.getMessage());
                return List.of();
            }
        });
        java.util.concurrent.atomic.AtomicReference<Map<String, List<PublicId>>> listed = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.function.Supplier<Map<String, List<PublicId>>> byPath = () -> {
            if (listed.get() == null) {
                listed.set(GitDocumentMaterializer.storedDocumentsByPath());
            }
            return listed.get();
        };
        java.util.function.Function<String, Optional<StoredDocument>> store = path -> {
            Loaded known = loaded.get(path);
            if (known != null) {
                return Optional.of(known.document());
            }
            Optional<Loaded> read = Optional.empty();
            for (Path tree : trees) {
                PublicId under = GitDocumentMaterializer.documentAnchor(tree, path);
                if (under.equals(anchor)) {
                    continue;
                }
                read = Loaded.read(under, tree, false, asOf);
                if (read.isPresent()) {
                    break;
                }
            }
            if (read.isEmpty() && root == null) {
                for (PublicId candidate : byPath.get().getOrDefault(path, List.of())) {
                    if (candidate.equals(anchor)) {
                        continue;
                    }
                    read = Loaded.read(candidate, null, false, asOf);
                    if (read.isPresent()) {
                        break;
                    }
                }
            }
            read.ifPresent(l -> loaded.put(path, l));
            return read.map(Loaded::document);
        };
        java.util.function.Function<String, List<Path>> underRoots = rest -> {
            if (root == null) {
                return IncludeResolver.endingWith(byPath.get().keySet(), rest);
            }
            for (Path tree : trees) {
                List<Path> found = IncludeResolver.endingWith(committedPaths.apply(tree), rest);
                if (!found.isEmpty()) {
                    return found;
                }
            }
            return List.of();
        };
        DocumentAssembly assembly = DocumentAssembly.of(host.get().document(), host.get().path(), store, underRoots, set);
        Map<String, Loaded> members = new LinkedHashMap<>();
        members.put(assembly.host().path(), host.get());
        for (DocumentAssembly.Member child : assembly.children()) {
            Loaded l = loaded.get(child.path());
            if (l != null) {
                members.put(child.path(), l);
            }
        }
        return new Opened(host.get(), members, assembly, asOf);
    }

    /** As {@link #open(PublicId)}, then puts a report on the status line and runs {@code afterShow} once drawn. */
    private void open(PublicId anchor, String report, Runnable afterShow) {
        open(anchor, report, afterShow, null);
    }

    /**
     * @param changed the path of the one file of the document shown that an edit just changed, so
     *                the others need not be read again; null to read every file
     */
    private void open(PublicId anchor, String report, Runnable afterShow, String changed) {
        statusLine.setText("Reading from the store …");
        Path known = repoRoot;
        long asOf = asOf();
        // the files an edit did not touch are as they were read, when the same document is read the same way
        Map<String, Loaded> asRead = new LinkedHashMap<>();
        String hostPath = null;
        if (changed != null && shown != null && assembly != null && PublicId.equals(shown.anchor(), anchor) && asOf == shownAsOf
                && members.containsKey(changed)) {
            asRead.putAll(members);
            asRead.remove(changed);
            hostPath = assembly.host().path();
        }
        String host = hostPath;
        // the card may never have opened this document's file: its repository is found before it is read,
        // so the includes are read from it and a comment or a commit has somewhere to go without asking
        java.util.concurrent.atomic.AtomicReference<Path> home = new java.util.concurrent.atomic.AtomicReference<>();
        List<String> set = attributesSet(anchor);
        long started = System.nanoTime();
        long[] readAt = new long[1];
        CompletableFuture.supplyAsync(() -> {
            home.set(home(anchor, known).orElse(null));
            Path tree = home.get() != null ? home.get() : known;
            Opened read = read(anchor, tree, asOf, set, java.util.Objects.equals(tree, known) ? asRead : Map.of(), host);
            readAt[0] = System.nanoTime();
            return read;
        }).whenComplete((loaded, failure) -> Platform.runLater(() -> {
            if (failure == null && loaded != null && home.get() != null && java.util.Objects.equals(repoRoot, known)) {
                useWorkingTree(home.get());
            }
            if (failure != null) {
                statusLine.setText("Could not read the document: " + failure.getMessage());
                LOG.warn("Loading {} failed", anchor, failure);
                return;
            }
            if (loaded == null) {
                statusLine.setText("The store holds no manifest for that document.");
                return;
            }
            long showing = System.nanoTime();
            show(loaded);
            LOG.info("Read {} file(s) in {} ms, waited {} ms for the application thread, drew them in {} ms",
                    loaded.members().size(), (readAt[0] - started) / 1_000_000, (showing - readAt[0]) / 1_000_000, (System.nanoTime() - showing) / 1_000_000);
            if (report != null) {
                statusLine.setText(report);
            }
            if (afterShow != null) {
                afterShow.run();
            }
            DocumentNavigatorTray.readAgain(fxObject());               // what its detail says of the store may have changed
        }));
    }

    /**
     * Puts the reader's place back on a stack of pages just laid again. A new stack has no
     * viewport to scroll until it is laid out, a pulse or two after it is shown, so the place
     * waits for the viewport.
     */
    private static void restorePlace(PageStackView stack, PageStackView.Anchor place) {
        if (stack.getViewportBounds().getHeight() > 0) {
            stack.restore(place);
            return;
        }
        stack.viewportBoundsProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends javafx.geometry.Bounds> observable, javafx.geometry.Bounds was, javafx.geometry.Bounds is) {
                if (is.getHeight() > 0) {
                    stack.viewportBoundsProperty().removeListener(this);
                    Platform.runLater(() -> stack.restore(place));
                }
            }
        });
    }

    private void show(Opened opened) {
        Loaded loaded = opened.host();
        // the same document read again, after a mark or an edit, keeps the page the reader is on: the pages are laid
        // again from the top, and the line that stood at the top of the view is put back there
        Optional<PageStackView.Anchor> place = shown != null && pageStack != null && surface.view() == DocumentSurfaceView.View.PAGE_LAYOUT
                && PublicId.equals(shown.anchor(), loaded.document().anchor()) ? pageStack.anchor() : Optional.empty();
        // the document that is shown, read again after an edit, a mark, a comment or a commit: what did not change stays as it is drawn
        boolean again = shown != null && PublicId.equals(shown.anchor(), loaded.document().anchor());
        shown = loaded.document();
        assembly = opened.assembly();
        members = opened.members();
        histories.clear();
        facts.clear();
        shownPath = loaded.path();
        shownAsOf = opened.asOf();
        summaries = CommitSummaries.merge(members.values().stream().map(Loaded::summaries).toList());
        surface.setPast(shownAsOf != Long.MAX_VALUE);
        if (again) {
            surface.showChanged(assembly);
        } else {
            surface.show(assembly);
        }
        find(false);                                               // the document was read again: its matches are counted again
        Runnable replacing = afterRead;
        afterRead = null;
        if (replacing != null) {
            Platform.runLater(replacing);                              // once this showing has said what it has to say
        }
        fillAttributesMenu();
        pendingEdit = members.values().stream().anyMatch(Loaded::pending);
        repositoryPanel.refresh(state -> {
            state.remotes().stream().findFirst().ifPresent(remote -> GitRepositoryTerms.record(shown.anchor(), remote.url(), state.branch()));
            followHead(state);
        });
        showingMember(assembly.host());
        documentArea.refreshExpandedView();
        if (surface.view() == DocumentSurfaceView.View.PAGE_LAYOUT) {
            buildPages();                                              // the pages follow the document
            place.ifPresent(at -> restorePlace(pageStack, at));
        } else if (surface.view() == DocumentSurfaceView.View.GRAPH) {
            // As does the graph, with its modes and the centre: a load that ends after the person
            // chose Graph found no document then, so showCenter left the surface in place; a store
            // slower to read, as Rocks is, made that the usual case (IKE-Network/ike-issues#1313).
            showCenter(DocumentSurfaceView.View.GRAPH);
        }
        // a line that is shown twice, in an abstract and in the body, is one line: it is counted once
        int affirmed = (int) assembly.lines().stream().filter(l -> !l.affirmers().isEmpty()).map(StoredLine::nid).distinct().count();
        int references = assembly.references().values().stream().mapToInt(List::size).sum();
        long edited = assembly.lines().stream().filter(DocumentSurfaceView::uncommitted).map(StoredLine::nid).distinct().count();
        editedLines = edited;
        editedDocuments = (int) members.values().stream().filter(Loaded::pending).count();
        if (messageFor == null || !PublicId.equals(messageFor, shown.anchor())) {
            pendingBar.clearMessage();                                 // a message is for the document it was typed under
            messageFor = shown.anchor();
        }
        if (pendingEdit) {
            pendingBar.showSigned(signsAnnotations());
        }
        pendingBar.show(pendingEdit, loaded.restorable(), edited, editedDocuments);
        int children = assembly.children().size();
        List<DocumentAssembly.Include> unresolved = assembly.unresolved();
        int blocks = shown.root() == null ? -1 : assembly.byPath().values().stream().mapToInt(m -> m.document().blocks().size()).sum();
        String tail = shown.root() == null ? "no structure; re-materialize to add it"
                : blocks + " blocks, " + references + " references, "
                + affirmed + " affirmed lines";
        if (shownAsOf != Long.MAX_VALUE && shown.lines().isEmpty()) {
            statusLine.setText("The document has no version as of " + DateTimeUtil.format(shownAsOf) + "; move the view's time later to read it.");
            return;
        }
        String lost = unresolved.isEmpty() ? "" : unresolved.size() + " include(s) left as lines: "
                + unresolved.stream().map(DocumentAssembly.Include::target).limit(3).collect(Collectors.joining(", ")) + (unresolved.size() > 3 ? ", …" : "");
        statusLine.setText((shownAsOf == Long.MAX_VALUE ? "" : "As of " + DateTimeUtil.format(shownAsOf)
                + ", read only: the document as it stood then; move the view's time to the latest to edit it. ")
                + readWith(assembly.set())
                // the bar above the line counts the edited lines, so the line does not count them again
                + DocumentFooter.line(assembly.storedLines(), blocks, references, affirmed, children, 0, lost));
        statusDetail = status(shown, tail
                + (children == 0 ? "" : "; " + children + " included document(s) shown in place")
                + (lost.isEmpty() ? "" : "; " + lost)
                + (edited == 0 ? "" : "; " + edited + " line(s) edited in Komet, uncommitted: Commit, below, records them in git")
                + (repoRoot == null || shownPath == null || !Files.isRegularFile(repoRoot.resolve(shownPath))
                ? "; no working tree holds the file: Open file… to annotate" : ""));
        Tooltip detail = new Tooltip(statusDetail);
        detail.setWrapText(true);
        detail.setMaxWidth(420);
        statusLine.setTooltip(detail);
    }

    private String status(StoredDocument document, String tail) {
        String name = StampNames.name(view(), PrimitiveData.nid(document.anchor()));
        String commit = document.commit().isEmpty() ? "?" : document.commit().substring(0, Math.min(7, document.commit().length()));
        int lines = assembly == null ? document.lines().size() : assembly.storedLines();     // a line shown twice is one line
        return name + " @ " + commit + " — " + lines + " lines, " + document.manifestVersions() + " manifest versions, " + tail;
    }

    // ── print ─────────────────────────────────────────────────────────────────

    /**
     * The shown document as the print writers read it: the host with each resolved child flowed
     * in place of its include line, the children's titles at their includes' level offsets.
     *
     * @return the model, empty before a document is shown
     */
    public Optional<DocumentModel> model() {
        if (shown == null) {
            return Optional.empty();
        }
        // a table's columns are fitted to the measure of the page the model is for, in its fonts
        PrintSettings settings = documentArea.settings().get();
        double[] dims = PagedDocumentEngine.pageDimensionsPoints(settings);
        surface.printOn(settings.theme(), dims[0] - settings.margins().left() - settings.margins().right());
        if (assembly == null) {
            return Optional.of(StoredDocumentModel.of(shown, Map.of(), surface.printing()));
        }
        // each include line with what it shows: the whole file, or the tagged regions it names; every line as it
        // reads, a conditional's leavings gone and an attribute reference as its value
        return Optional.of(StoredDocumentModel.of(assembly, surface.printing()));
    }

    /**
     * Puts the view's body in the card's centre: the Document surface for the Reading and Source
     * views, the page stack for the Page layout — laid out at once from JavaFX's greedy line
     * breaks, then settled on Typst's exact lines when typst answers.
     *
     * @param view the view to show
     */
    private void showCenter(DocumentSurfaceView.View view) {
        boolean graphShown = view == DocumentSurfaceView.View.GRAPH && shown != null;
        modes.setVisible(graphShown);
        modes.setManaged(graphShown);
        if (view == DocumentSurfaceView.View.PAGE_LAYOUT && shown != null) {
            buildPages();
            return;
        }
        pageStack = null;
        pageGeneration++;
        showSettling(false);
        pageIndicator.setVisible(false);
        pageIndicator.setManaged(false);
        if (graphShown) {
            graph.show(assembly);
            showOverArea(graph.node());
            host.setBottom(footer);                                    // the footer stays with the surface, which is not in the scene now
            return;
        }
        graph.show(null);
        showOverArea(null);
        host.setBottom(null);
        // the pages and the graph took the footer under themselves: it is the surface's again, or the card would stand without one
        documentArea.surface().setFooter(footer);
    }

    /**
     * Shows a view in the document area's place, or the area again: the area stays in the scene,
     * hidden, so it is never detached.
     *
     * @param view the Graph view's or the pages' node, or {@code null} for the document area
     */
    private void showOverArea(Node view) {
        Node area = documentArea.fxObject();
        center.getChildren().removeIf(child -> child != area);
        if (view != null) {
            center.getChildren().add(view);
        }
        area.setVisible(view == null);
        area.setManaged(view == null);
    }

    @Override
    public dev.ikm.komet.framework.view.ViewProperties viewProperties() {
        return getCardViewProperties();
    }

    /** What the Graph view asks of the card: the surface's selection and layer, the card's acts. */
    private DocumentGraphView.Host graphHost() {
        return new DocumentGraphView.Host() {
            @Override
            public ViewCalculator view() {
                return StampedDocumentCard.this.view();
            }

            @Override
            public CommitSummaries summaries() {
                return summaries;
            }

            @Override
            public Optional<StoredNodeFacts.NodeFacts> facts(DocumentGraph.Node node) {
                Loaded loaded = members.get(node.member().path());
                boolean pending = loaded != null && loaded.pending();
                return facts.computeIfAbsent(node.nid(), nid -> {
                    try {
                        return StoredNodeFacts.read(nid, view(), pending);
                    } catch (RuntimeException failure) {
                        LOG.warn("Reading node {} of {} failed", nid, node.member().path(), failure);
                        return Optional.empty();
                    }
                });
            }

            @Override
            public DocumentHistory history(DocumentAssembly.Member member) {
                Loaded loaded = members.get(member.path());
                boolean pending = loaded != null && loaded.pending();
                return histories.computeIfAbsent(member.path(), p -> {
                    try {
                        return DocumentHistory.load(member.document(), pending);
                    } catch (RuntimeException failure) {
                        LOG.warn("Reading the history of {} failed", member.path(), failure);
                        return DocumentHistory.NONE;
                    }
                });
            }

            @Override
            public void highlightCommit(long stampNid) {
                surface.highlightCommit(stampNid);
            }

            @Override
            public void status(String text) {
                StampedDocumentCard.this.status(text);
            }

            @Override
            public Image identicon(StampEntity<?> stamp) {
                return surface.badge(stamp);
            }

            @Override
            public boolean layer(DocumentSurfaceView.Layer layer) {
                return surface.layer(layer);
            }

            @Override
            public Optional<LineSelection> selection() {
                return surface.selection();
            }

            @Override
            public void select(LineSelection selection) {
                surface.select(selection);
            }

            @Override
            public void clearSelection() {
                surface.clearSelection();
            }

            @Override
            public void annotate(AnnotationKind kind) {
                StampedDocumentCard.this.annotate(kind);
            }

            @Override
            public void showHistory() {
                StampedDocumentCard.this.showHistory();
            }

            @Override
            public void editSelectedBlock() {
                surface.editSelectedBlock();
            }

            @Override
            public void editSentence(long sentence, String text) {
                StampedDocumentCard.this.askToEditSentence(sentence, text);
            }

            @Override
            public void showingMember(DocumentAssembly.Member member) {
                StampedDocumentCard.this.showingMember(member);
            }
        };
    }

    // ── attributes set for this card ──────────────────────────────────────────

    /** What the status line says of the attributes the card set: nothing when the document is read as written. */
    static String readWith(List<String> set) {
        return set.isEmpty() ? "" : "Read with " + String.join(", ", set) + " set. ";
    }

    /** The attribute names this card sets for a document, as its preferences keep them. */
    private List<String> attributesSet(PublicId anchor) {
        String kept;
        try {
            kept = preferences().get(PREF_ATTRIBUTES + foldsKey(anchor), "");
        } catch (RuntimeException | AssertionError e) {
            return List.of();
        }
        return kept.isBlank() ? List.of() : java.util.Arrays.stream(kept.split(",")).map(String::strip).filter(n -> !n.isEmpty()).distinct().toList();
    }

    /**
     * Sets or clears an attribute for the shown document on this card, and reads the document
     * again: every view, the pages and the PDF among them, then reads it with the attribute.
     *
     * @param name the attribute's name
     * @param set  whether the card sets it
     */
    public void setAttribute(String name, boolean set) {
        if (shown == null || name == null || name.isBlank()) {
            return;
        }
        List<String> names = new ArrayList<>(attributesSet(shown.anchor()));
        if (set ? names.contains(name) || !names.add(name) : !names.remove(name)) {
            return;
        }
        try {
            if (names.isEmpty()) {
                preferences().remove(PREF_ATTRIBUTES + foldsKey(shown.anchor()));
            } else {
                preferences().put(PREF_ATTRIBUTES + foldsKey(shown.anchor()), String.join(",", names));
            }
            syncPreferences();
        } catch (RuntimeException | AssertionError e) {
            LOG.warn("The attributes set for {} could not be kept", shown.anchor(), e);
        }
        open(shown.anchor(), null, null);
    }

    /** @return the attribute names this card set for the shown document */
    public List<String> attributesSet() {
        return assembly == null ? List.of() : assembly.set();
    }

    /**
     * Fills the More menu's attributes: every name a conditional of the shown document asks
     * about, checked where this card sets it; one the document sets itself is shown checked and
     * cannot be changed here, since the document's own line has the last word.
     */
    private void fillAttributesMenu() {
        attributesMenu.getItems().clear();
        if (assembly == null) {
            attributesMenu.setDisable(true);
            return;
        }
        List<String> names = new ArrayList<>(DocumentConditions.of(assembly).names());
        for (String name : assembly.set()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        attributesMenu.setDisable(false);
        if (names.isEmpty()) {
            MenuItem none = new MenuItem("No conditional in this document asks about an attribute");
            none.setDisable(true);
            attributesMenu.getItems().add(none);
            return;
        }
        DocumentAttributes atEnd = assembly.lines().isEmpty() ? DocumentAttributes.intrinsic()
                : assembly.memberAt(assembly.lines().size() - 1)
                .map(member -> assembly.attributes(member, assembly.lines().getLast().nid())).orElse(DocumentAttributes.intrinsic());
        for (String name : names) {
            boolean here = assembly.set().contains(name);
            boolean byDocument = !here && atEnd.defined(name);
            CheckMenuItem item = new CheckMenuItem(byDocument ? name + " (set by the document)" : name);
            item.setId("stampedDocumentAttribute-" + name);
            item.setSelected(here || byDocument);
            item.setDisable(byDocument);
            item.setOnAction(e -> setAttribute(name, item.isSelected()));
            attributesMenu.getItems().add(item);
        }
    }

    /** The reader's folds in the shown document, kept with the card's preferences under the document's identity. */
    @Override
    public void rememberFolds(List<String> keys) {
        if (shown == null) {
            return;
        }
        String joined = String.join(FOLD_SEPARATOR, keys);
        try {
            if (joined.isEmpty()) {
                preferences().remove(PREF_FOLDS + foldsKey(shown.anchor()));
            } else if (joined.length() < java.util.prefs.Preferences.MAX_VALUE_LENGTH) {
                preferences().put(PREF_FOLDS + foldsKey(shown.anchor()), joined);
            }
            syncPreferences();
        } catch (RuntimeException | AssertionError e) {
            // the fold stands on the surface either way; what could not be kept is only the memory of it
            LOG.warn("The folds of {} could not be remembered", shownPath, e);
        }
    }

    @Override
    public List<String> rememberedFolds() {
        if (shown == null) {
            return List.of();
        }
        String joined = preferences().get(PREF_FOLDS + foldsKey(shown.anchor()), "");
        return joined.isEmpty() ? List.of() : List.of(joined.split(FOLD_SEPARATOR));
    }

    /** A document's part of a preference key: its anchor's first UUID, never a nid. */
    private static String foldsKey(PublicId anchor) {
        return anchor.asUuidArray()[0].toString();
    }

    /** @return the Graph view, for automation and tests */
    public Optional<TreeView<DocumentGraphView.Row>> graphTree() {
        return surface != null && surface.view() == DocumentSurfaceView.View.GRAPH ? Optional.of(graph.tree()) : Optional.empty();
    }

    /** Lays the pages out: the greedy pass now, Typst's in the background. */
    private void buildPages() {
        int generation = ++pageGeneration;
        PrintSettings settings = documentArea.settings().get();
        double[] dims = PagedDocumentEngine.pageDimensionsPoints(settings);
        MarginPreset m = settings.margins();
        pageGeometry = new LayoutOracle.Geometry(dims[0], dims[1], m.left(), m.top(), dims[0] - m.left() - m.right(), dims[1] - m.top() - m.bottom());
        surface.printOn(settings.theme(), pageGeometry.contentWidth());
        pageUnits = surface.pageUnits(settings.theme());
        GreedyFxOracle greedy = new GreedyFxOracle(DocumentFonts.SERIF, DocumentFonts.MONO);
        greedyLayout = greedy.layout(pageUnits, pageGeometry);
        typstLayout = null;
        pageModel = null;
        pagePasses = 0;
        surface.beginLineCheck(pageGeometry.contentLeft());
        surface.layoutLines(greedyLayout, pageUnits);
        pageStack = new PageStackView(settings, dims[0], dims[1], runningHead());
        pageStack.currentPageProperty().addListener((obs, was, is) -> showPageIndicator());
        pageStack.show(content());
        showOverArea(pageStack);
        // The footer stays with the document area's surface, which is not in the scene now: show it under the stack.
        host.setBottom(footer);
        pageIndicator.setVisible(true);
        pageIndicator.setManaged(true);
        showPageIndicator();
        surface.renderSelection();
        settle(generation, settings, greedy);
    }

    /** What the page stack shows: the surface's line areas and marginalia for the layout in force. */
    private PageStackView.Content content() {
        LayoutOracle.Layout layout = typstLayout != null ? typstLayout : greedyLayout;
        return new PageStackView.Content() {
            @Override
            public int pageCount() {
                return layout == null ? 1 : layout.pages();
            }

            @Override
            public List<PageStackView.Placed> backdrops(int page) {
                return surface.backdropsOn(page, pageGeometry.contentLeft(), pageGeometry.contentWidth());
            }

            @Override
            public List<PageStackView.Placed> lines(int page) {
                return surface.linesOn(page, pageGeometry.contentLeft(), pageGeometry.contentWidth(), pageGeometry.bandHeight());
            }

            @Override
            public List<PageStackView.Mark> marginalia(int page) {
                return surface.marginaliaOn(page);
            }

            @Override
            public List<PageStackView.Place> places(int page) {
                return surface.placesOn(page, pageGeometry.contentWidth(), pageGeometry.bandHeight());
            }

            @Override
            public void built(int page) {
                // a selection applied before an area's first layout breaks the incubator's highlight: the sheet is laid out now
                surface.renderSelection(page);
            }

            @Override
            public String section(int page) {
                return surface.sectionOn(page);
            }

            @Override
            public String folio(int page) {
                return layout == null ? Integer.toString(page + 1) : layout.folios().label(page);
            }

            @Override
            public boolean runningHead(int page) {
                return runningHeads().shown(page);
            }
        };
    }

    /** The running heads of the layout last asked, and that layout: the pages a chapter opens on are found once per layout. */
    private RunningHeads runningHeads = RunningHeads.EVERY_PAGE;
    private LayoutOracle.Layout runningHeadsOf;

    /** Which pages of the layout in force carry the running head: none that opens the title or a chapter. */
    private RunningHeads runningHeads() {
        LayoutOracle.Layout layout = typstLayout != null ? typstLayout : greedyLayout;
        if (layout == null) {
            return RunningHeads.EVERY_PAGE;
        }
        if (layout != runningHeadsOf) {
            runningHeads = RunningHeads.of(pageUnits, layout.lines());
            runningHeadsOf = layout;
        }
        return runningHeads;
    }

    /** The folios of the layout in force: where the body begins, and whether a title page stands before it. */
    private Folios folios() {
        LayoutOracle.Layout layout = typstLayout != null ? typstLayout : greedyLayout;
        return layout == null ? Folios.NONE : layout.folios();
    }

    /**
     * The second pass: writes the document as word-labelled Typst, asks typst where every word
     * fell, and re-flows the pages to its lines — keeping the line the reader is on where it is.
     */
    private void settle(int generation, PrintSettings settings, GreedyFxOracle greedy) {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            return;
        }
        showSettling(true);
        List<LayoutOracle.Unit> units = pageUnits;
        LayoutOracle.Geometry geometry = pageGeometry;
        // The front lists' entries print their targets' pages: the greedy estimate's first, then the pages typst
        // found, until no entry's page changes (FrontLists), so the measured source is the document as printed.
        Map<String, Integer> targets = surface.entryTargets();
        Map<String, Integer> estimated = FrontLists.pages(greedyLayout, targets);
        TypstWriter.PageSetup setup = TypstPdf.setup(settings);
        CompletableFuture.supplyAsync(() -> {
            try {
                Path folder = Files.createTempDirectory("stamped-document-layout-");
                Path source = folder.resolve("layout.typ");
                Path fonts = DocumentFonts.extractTo(folder.resolve("fonts"));
                FrontLists.Settled settled = FrontLists.settle(model.get(), estimated, targets, numbered -> {
                    Files.writeString(source, TypstWriter.write(numbered, setup, TypstWriter.Labels.WORDS), java.nio.charset.StandardCharsets.UTF_8);
                    return new TypstOracle(folder, source, fonts, greedy::width).layout(units, geometry);
                });
                dump(folder, units, settled.layout());
                return Map.entry(folder, settled);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((answer, failure) -> Platform.runLater(() -> {
            if (generation != pageGeneration || pageStack == null) {
                return;                                              // the pages were rebuilt, or left, meanwhile
            }
            showSettling(false);
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("The pages keep JavaFX's layout: Typst could not lay them out: " + cause.getMessage());
                LOG.warn("Typst layout failed", cause);
                return;
            }
            FrontLists.Settled settled = answer.getValue();
            LayoutOracle.Layout layout = settled.layout();
            if (layout.lines().isEmpty()) {
                statusLine.setText("The pages keep JavaFX's layout: " + String.join("; ", layout.notes()));
                return;
            }
            oracleFolder = answer.getKey();
            typstLayout = layout;
            pageModel = settled.model();
            pagePasses = settled.passes();
            Optional<PageStackView.Anchor> anchor = pageStack.anchor();
            surface.beginLineCheck(geometry.contentLeft());
            surface.layoutLines(layout, units);
            pageStack.show(content());
            anchor.ifPresent(pageStack::restore);
            surface.renderSelection();
            showPageIndicator();
            // The footer's line stays the document's: how the pages settled is detail.
            if (statusLine.getTooltip() != null) {
                statusLine.getTooltip().setText(statusDetail + "\n\nPages settled on Typst's lines in " + layout.millis() + " ms"
                        + (settled.passes() > 1 ? ", the last of " + settled.passes() + " passes over the lists' page numbers" : "")
                        + (layout.notes().isEmpty() ? "" : " (" + String.join("; ", layout.notes()) + ")"));
            }
        }));
    }

    /** Writes the units and the lines the oracle made of them beside the source, for the fidelity report and for reading. */
    private static void dump(Path folder, List<LayoutOracle.Unit> units, LayoutOracle.Layout layout) throws IOException {
        StringBuilder u = new StringBuilder();
        for (LayoutOracle.Unit unit : units) {
            u.append(unit.index()).append('\t').append(unit.block()).append('\t').append(unit.kind()).append('\t')
                    .append(unit.text().replace("\n", "\\n")).append('\n');
        }
        Files.writeString(folder.resolve("units.txt"), u.toString(), java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder l = new StringBuilder();
        for (LayoutOracle.Line line : layout.lines()) {
            l.append(String.format(java.util.Locale.ROOT, "p%d u%d x%.1f y%.1f [%d,%d)%s\t%s%n", line.page() + 1, line.unit(), line.x(), line.baseline(),
                    line.from(), line.to(), line.hyphenated() ? " -" : "", line.text(units.get(line.unit())).replace("\n", "\\n")));
        }
        Files.writeString(folder.resolve("lines.txt"), l.toString(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void showSettling(boolean on) {
        settling.setVisible(on);
        settling.setManaged(on);
    }

    private void showPageIndicator() {
        if (pageStack != null) {
            pageIndicator.setText(DocumentFooter.page(folios(), pageStack.currentPageProperty().get(), pageStack.pageCount())
                    + (typstLayout == null ? "  ·  estimated" : ""));
        }
    }

    /** Go to page…: asks for a page number and scrolls the Page layout to it. */
    private void goToPage() {
        if (views.getValue() != DocumentSurfaceView.View.PAGE_LAYOUT) {
            views.select(DocumentSurfaceView.View.PAGE_LAYOUT);
        }
        if (pageStack == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Folios folios = folios();
        int pages = pageStack.pageCount();
        TextInputDialog dialog = new TextInputDialog(folios.counted(pageStack.currentPageProperty().get()));
        dialog.setTitle("Go to page");
        dialog.setHeaderText(DocumentFooter.pageRange(folios, pages));
        dialog.setContentText("Page:");
        dialog.initOwner(window());
        dialog.showAndWait().ifPresent(text -> goToFolio(text));
    }

    /**
     * Scrolls the Page layout to the page that prints a number, public for automation; must be
     * called on the JavaFX application thread. An arabic number names a page of the body, and a
     * roman numeral a page of the front matter, so a reader sent to page 12 by the table of
     * contents arrives at the page that prints 12.
     *
     * @param folio the page number as the page prints it, such as {@code 12} or {@code iii}
     * @return whether the pages have such a page
     */
    public boolean goToFolio(String folio) {
        if (pageStack == null) {
            return false;
        }
        java.util.OptionalInt page = folios().pageOf(folio, pageStack.pageCount());
        if (page.isEmpty()) {
            statusLine.setText("Not a page of this document: " + (folio == null ? "" : folio.strip()));
            return false;
        }
        pageStack.scrollToPage(page.getAsInt());
        return true;
    }

    /**
     * Scrolls the Page layout to a page by its place among all the pages, public for automation;
     * must be called on the JavaFX application thread. {@link #goToFolio} goes to the page that
     * prints a number.
     *
     * @param page the 1-based page, counted from the first sheet
     */
    public void goToPage(int page) {
        if (pageStack != null) {
            pageStack.scrollToPage(page - 1);
        }
    }

    private String runningHead() {
        return shown != null && !shown.attributes().isEmpty() && !shown.attributes().getFirst().isBlank() ? shown.attributes().getFirst()
                : documents.getValue() == null ? "" : documents.getValue();
    }

    @Override
    public void viewRequested(DocumentSurfaceView.View view) {
        views.select(view);
    }

    @Override
    public void revealPage(int page, double y) {
        if (pageStack != null) {
            pageStack.scrollTo(page, y, 40);
        }
    }

    /** @return the page stack, when the Page layout is shown */
    public Optional<PageStackView> pageStack() {
        return Optional.ofNullable(pageStack);
    }

    /** @return the 1-based page under the eye in the Page layout, 0 when it is not shown */
    public int currentPage() {
        return pageStack == null ? 0 : pageStack.currentPageProperty().get() + 1;
    }

    /** @return the Page layout's page count, 0 when it is not shown */
    public int pageCount() {
        return pageStack == null ? 0 : pageStack.pageCount();
    }

    /**
     * The number a page of the Page layout prints, public for automation.
     *
     * @param page the 1-based page, counted from the first sheet
     * @return the folio, such as {@code iii} or {@code 12}; empty for a title page, which prints none, and when the Page layout is not shown
     */
    public String folioOf(int page) {
        return pageStack == null || page < 1 || page > pageStack.pageCount() ? "" : folios().label(page - 1);
    }

    /** @return whether the Page layout shows Typst's lines (the second pass has settled) */
    public boolean pagesSettled() {
        return pageStack != null && typstLayout != null;
    }

    /**
     * Measures the two passes against each other and against Typst's unlabelled output for the
     * shown document: the greedy JavaFX estimate against Typst's lines (page counts, breaks,
     * lines that agree), whether the word labels moved any block, and the oracle's latency.
     * Must be called on the JavaFX application thread once the pages have settled; public for
     * UI automation.
     *
     * @return the report text, or the reason it could not be made
     */
    public String measurePageFidelity() {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            return "No document is shown.";
        }
        if (pageStack == null || greedyLayout == null) {
            return "Show the Page layout view first.";
        }
        if (typstLayout == null) {
            return "The pages have not settled on Typst's layout yet.";
        }
        PrintSettings settings = documentArea.settings().get();
        try {
            Path folder = oracleFolder != null ? oracleFolder : Files.createTempDirectory("stamped-document-fidelity-");
            PageFidelity.Report report = PageFidelity.compare(greedyLayout, typstLayout, pageUnits);
            // the model as the pages measured it: its lists' entries print the pages the passes settled on, which the printed
            // document reads from Typst's page counter, so the two sources lay out alike
            String perturbation = PageFidelity.perturbation(pageModel != null ? pageModel : model.get(), settings, folder);
            String text = "document: " + documents.getValue() + "\nsettings: " + settings + "\nfolder: " + folder + "\n"
                    + "typst oracle: " + typstLayout.millis() + " ms over " + pagePasses + " pass(es); greedy pass: " + greedyLayout.millis() + " ms\n"
                    + report.text() + perturbation + surface.lineCheck() + "\n";
            Files.writeString(folder.resolve("fidelity-report.txt"), text, java.nio.charset.StandardCharsets.UTF_8);
            return text;
        } catch (IOException e) {
            return "The measurement could not be made: " + e.getMessage();
        }
    }

    /** What the surface's paged preview paginates: the stored blocks as the Reading view draws them, with running head and folios. */
    private PrintSource printSource() {
        return model().map(PrintBlocks::source).orElse(PrintSource.EMPTY);
    }

    /**
     * Promotes the document area to its paged preview — what the area's expand affordance does —
     * so UI automation can reach the pages without the pointer. Must be called on the JavaFX
     * application thread.
     */
    public void showPages() {
        if (documentArea != null) {
            documentArea.expand();
        }
    }

    /** Print to PDF from the menu: asks where the PDF goes, then prints and opens it there. */
    private void chooseAndPrint() {
        if (model().isEmpty()) {
            statusLine.setText("Choose a document first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save the document as a PDF");
        chooser.setInitialFileName(TypstPdf.stem(shownPath != null ? shownPath : documents.getValue()) + ".pdf");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        if (lastPdfFolder != null && lastPdfFolder.isDirectory()) {
            chooser.setInitialDirectory(lastPdfFolder);
        }
        File chosen = chooser.showSaveDialog(window());
        if (chosen != null) {
            lastPdfFolder = chosen.getParentFile();
            printToPdf(TypstPdf.withPdfExtension(chosen.toPath()));
        }
    }

    /**
     * Print to PDF: writes the shown document, with its children, as Typst from the store's
     * blocks under the surface's print settings, compiles it with the installed typst, saves the
     * PDF where it was asked to go and opens it in the system's PDF reader. typst's own error goes
     * on the status line. Public so automation can print without the menu's dialog; must be called
     * on the JavaFX application thread.
     *
     * @param target where the PDF goes
     * @return the print once it has run; completes with {@code null} when no document is shown
     */
    public CompletableFuture<TypstPdf.Printed> printToPdf(Path target) {
        Optional<DocumentModel> model = model();
        if (model.isEmpty()) {
            statusLine.setText("Choose a document first.");
            return CompletableFuture.completedFuture(null);
        }
        String name = shownPath != null ? shownPath : documents.getValue();
        PrintSettings settings = documentArea.settings().get();
        statusLine.setText("Writing " + TypstPdf.stem(name) + ".typ and compiling it with typst …");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return TypstPdf.printTo(model.get(), settings, name, target);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((printed, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Print to PDF failed: " + cause.getMessage());
                LOG.warn("Print to PDF failed", cause);
                return;
            }
            if (!printed.ok()) {
                statusLine.setText(printed.report());
                LOG.warn("typst compile failed for {}: {}", printed.source(), printed.result().output());
                return;
            }
            statusLine.setText("Saved " + target);
            openInReader(target);
        }));
    }

    /** Opens a saved PDF in the system's reader, off the JavaFX thread; the PDF stays saved if it cannot. */
    private void openInReader(Path pdf) {
        CompletableFuture.runAsync(() -> {
            try {
                java.awt.Desktop.getDesktop().open(pdf.toFile());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not open {}", pdf, e);
                Platform.runLater(() -> statusLine.setText("Saved " + pdf + ". It could not be opened: " + e.getMessage()));
            }
        });
    }

    // ── opening a file ────────────────────────────────────────────────────────

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open an AsciiDoc file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("AsciiDoc", "*.adoc", "*.asciidoc"));
        if (repoRoot != null && Files.isDirectory(repoRoot)) {
            chooser.setInitialDirectory(repoRoot.toFile());
        }
        File chosen = chooser.showOpenDialog(window());
        if (chosen != null) {
            open(chosen.toPath());
        }
    }

    /**
     * Opens an AsciiDoc file: resolves its repository, materializes the commits the store lacks
     * (of every file it includes too, with progress, Pause, Resume and Stop on the card), remembers
     * the working tree and shows the store's document. A document is stored from its history, so
     * a file with no commit is first offered one: a location for a repository when it is in
     * none, a first commit when it is in one uncommitted. Public so the assistant's tools and UI
     * automation can open a file without the chooser; must be called on the JavaFX application
     * thread.
     *
     * @param file the file to open
     */
    public void open(Path file) {
        Optional<Path> root = GitRepo.findRoot(file);
        if (root.isEmpty()) {
            offerRepository(file);
            return;
        }
        Path relative = root.get().relativize(file.toAbsolutePath().normalize());
        try {
            if (!GitOperations.committed(root.get(), relative)) {
                offerFirstCommit(root.get(), relative);
                return;
            }
        } catch (IOException e) {
            statusLine.setText("git could not be run: " + e.getMessage());
            return;
        }
        int includes = includeCount(root.get(), relative);
        if (includes < 0) {
            return;
        }
        repoRoot = root.get();
        DocumentNarratives.workingTreeOpened(repoRoot);
        preferences().put(PREF_WORKING_TREE, repoRoot.toString());
        syncPreferences();
        repositoryPanel.refresh();
        materialize(repoRoot, relative, includes > 0);
    }

    /**
     * How many include directives a committed file holds, so a file that includes others is
     * materialized with them.
     *
     * @return the count, or {@code -1} after saying on the status line why HEAD could not be read
     */
    private int includeCount(Path root, Path relative) {
        try (GitRepo repo = GitRepo.open(root)) {
            return GitDocumentMaterializer.includeCount(repo.headLines(relative));
        } catch (IOException | RuntimeException e) {
            statusLine.setText("Cannot read " + relative + " at HEAD: " + e.getMessage());
            return -1;
        }
    }

    /** Asks where a file in no repository should live, with its own folder offered, and places it there. */
    private void offerRepository(Path file) {
        Path source = file.toAbsolutePath().normalize();
        FolderPanel.show(window(), "Choose a Repository",
                        source.getFileName() + " is in no git repository, and a document is stored from its git history. Choose a folder for it.",
                        DocumentLibrary.root(), panelPlaces(source.getParent()), location -> DocumentRepository.placing(source, location))
                .ifPresent(location -> openInRepository(source, location));
    }

    /** The folder panel's side list, with a folder that matters to this choice among them. */
    private static List<FolderPanel.Place> panelPlaces(Path known) {
        return FolderPanel.places(Path.of(System.getProperty("user.home")), DocumentLibrary.root(), known, Path.of("/Volumes"));
    }

    /**
     * Places a file in a repository at a location and opens it there, making the repository when
     * the location has none; public so UI automation can do it without the dialog.
     *
     * @param file     the file
     * @param location the folder for it
     */
    public void openInRepository(Path file, Path location) {
        statusLine.setText("Giving " + file.getFileName() + " a repository …");
        openPlaced(String.valueOf(file.getFileName()), location, "", () -> DocumentRepository.place(file, location));
    }

    /** Places a file off the FX thread and opens it there, with what to do next after the report. */
    private void openPlaced(String name, Path location, String next, IoCall<DocumentRepository.Placed> placing) {
        CompletableFuture.supplyAsync(() -> uncheck(placing)).whenComplete((placed, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Could not give " + name + " a repository: " + cause.getMessage());
                LOG.warn("Placing {} at {} failed", name, location, cause);
                return;
            }
            if (!placed.ok()) {
                if (placed.root() != null) {
                    useWorkingTree(placed.root());                // the repository stands; the panel shows it
                }
                statusLine.setText(placed.report().replace('\n', ' '));
                return;
            }
            repositoryReport = placed.report() + next;
            open(placed.file());
        }));
    }

    /** Asks before giving a file in a repository its first commit, then opens it. */
    private void offerFirstCommit(Path root, Path relative) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("First Commit");
        confirm.getDialogPane().setId("stampedDocumentFirstCommitDialog");
        confirm.setHeaderText(relative + " is not committed in " + root.getFileName() + ".");
        confirm.setContentText("A document is stored from its git history, so it needs a first commit. "
                + "Only this file is committed, locally and unsigned, and nothing is pushed.");
        ButtonType commit = new ButtonType("Commit");
        confirm.getButtonTypes().setAll(commit, ButtonType.CANCEL);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == commit).isEmpty()) {
            return;
        }
        String path = relative.toString().replace('\\', '/');
        statusLine.setText("git commit " + path + " …");
        CompletableFuture.supplyAsync(() -> uncheck(() -> DocumentCommit.first(root, path, "Add " + relative.getFileName() + "\n")))
                .whenComplete((committed, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("git commit could not run: " + cause.getMessage());
                        return;
                    }
                    if (!committed.ok()) {
                        statusLine.setText("Not committed: " + committed.output().replace('\n', ' '));
                        return;
                    }
                    repositoryReport = "First commit " + committed.sha().substring(0, 7) + ", nothing pushed.";
                    open(root.resolve(relative));
                }));
    }

    /**
     * Materializes the shown document's file and every file it includes, or asks for a file when
     * no working tree holds it.
     */
    /**
     * Reads into the knowledge base what the repository has that the card has not read: when the
     * shown document's working tree stands at a commit other than the one the card last read it
     * at, the file and every file it includes are materialized, as More, Materialize does. A
     * commit made outside Komet, or on another machine and pulled, is then in the document the
     * next time it is opened, with no one having to ask. The commit is remembered by the card, so
     * a document whose repository has not moved is not read from git again, and a commit the
     * knowledge base already holds for the document, as one made from this card, is not read at all.
     */
    private void followHead(GitOperations.RepositoryState state) {
        if (shown == null || repoRoot == null || state == null || state.headSha().isBlank() || shownAsOf != Long.MAX_VALUE
                || materializing.running() || !java.util.Objects.equals(state.root(), repoRoot)) {
            return;
        }
        Optional<Path> file = currentFile();
        if (file.isEmpty() || !Files.isRegularFile(repoRoot.resolve(file.get()))) {
            return;
        }
        String key = PREF_READ_AT + shown.anchor().asUuidArray()[0];
        if (state.headSha().equals(preferences().get(key, ""))) {
            return;
        }
        preferences().put(key, state.headSha());                       // before the reading, which shows the document again
        syncPreferences();
        if (summaries.shaByStamp().values().stream().anyMatch(sha -> sha.startsWith(state.headSha()))) {
            return;                                                    // the knowledge base holds that commit: one this card made, or one read before
        }
        LOG.info("The repository of {} stands at {}, which this card has not read: materializing it", file.get(), state.headSha());
        materializeFile(repoRoot, file.get());
    }

    private void materialize() {
        Optional<Path> file = currentFile();
        if (file.isEmpty() || !Files.isRegularFile(repoRoot.resolve(file.get()))) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
            chooseFile();
            return;
        }
        materializeFile(repoRoot, file.get());
    }

    /** Materializes a file, with every file it includes when it includes any. */
    private void materializeFile(Path root, Path relative) {
        int includes = includeCount(root, relative);
        if (includes >= 0) {
            materialize(root, relative, includes > 0);
        }
    }

    /** What a materialization wrote, for the card to show: the document opened and the report on it. */
    private record Materialized(String name, PublicId anchor, String report) { }

    /** Writes assemblies on a thread of their own, which may wait while a person has paused the run. */
    private static final java.util.concurrent.Executor ASSEMBLY_WRITER =
            task -> Thread.ofPlatform().daemon().name("materialize-assembly").start(task);

    /**
     * Writes the file's history into the store off the FX thread and shows the resulting document.
     * A file that includes others is written with them, each as its own document, while the card's
     * strip shows the progress and offers Pause, Resume and Stop.
     */
    private void materialize(Path root, Path relative, boolean assembly) {
        if (assembly) {
            materializeAssembly(root, relative);
            return;
        }
        statusLine.setText("Materializing " + relative + " into the knowledge base …");
        CompletableFuture.supplyAsync(() -> {
            try {
                GitDocumentMaterializer.Result result = GitDocumentMaterializer.materialize(root, root.resolve(relative), true);
                DocumentEditor.Settlement settlement = DocumentEditor.settle(result.anchor());
                return new Materialized(result.name(), result.anchor(), "Materialized '" + result.name() + "': " + result.commits() + " commit(s) written, " + result.lines()
                        + " line(s), " + result.blocks() + " block version(s), " + result.references() + " reference(s), "
                        + result.annotations() + " annotation(s)."
                        + (settlement.settled() || settlement.rebased() ? " Edit in Komet: " + describe(settlement) : "")
                        + VertexMaterializer.fallbackNote());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((materialized, failure) -> Platform.runLater(() -> showMaterialized(relative, materialized, failure)));
    }

    /**
     * Writes a file and every file it includes, in include order, under a run the card's strip
     * shows and a person can pause, resume or stop. A stopped run keeps what it wrote and opens
     * the file's document, whose include lines stand in for the files it did not reach.
     */
    private void materializeAssembly(Path root, Path relative) {
        if (materializing.running()) {
            statusLine.setText("Files are already being materialized on this card. Stop that run first, or wait for it to end.");
            return;
        }
        statusLine.setText("Materializing " + relative + " and the files it includes …");
        AssemblyRun run = materializing.start(root, relative);
        record Written(GitDocumentMaterializer.AssemblyResult result, Materialized materialized) { }
        CompletableFuture.supplyAsync(() -> {
            try {
                GitDocumentMaterializer.AssemblyResult result = GitDocumentMaterializer.materializeAssembly(root, root.resolve(relative), run);
                if (result.documents().isEmpty()) {
                    return new Written(result, null);
                }
                int settled = 0;
                for (GitDocumentMaterializer.Result document : result.documents()) {
                    DocumentEditor.Settlement settlement = DocumentEditor.settle(document.anchor());
                    if (settlement.settled() || settlement.rebased()) {
                        settled++;
                    }
                }
                StringBuilder report = new StringBuilder();
                if (result.stopped()) {
                    report.append("Stopped after ").append(result.documents().size()).append(" of ")
                            .append(result.documents().size() + result.notWritten().size()).append(" files. ")
                            .append(result.notWritten().size()).append(" file(s) are not materialized. ");
                }
                report.append("Materialized assembly '").append(result.assembly().name()).append("': ").append(result.summary()).append('.');
                if (settled > 0) {
                    report.append(" Edits pending in Komet were settled in ").append(settled).append(" document(s).");
                }
                for (GitDocumentMaterializer.Unresolved u : result.unresolved()) {
                    report.append("\nNot resolved: ").append(u);
                }
                report.append(VertexMaterializer.fallbackNote());
                return new Written(result, new Materialized(result.assembly().name(), result.assembly().anchor(), report.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, ASSEMBLY_WRITER).whenComplete((written, failure) -> Platform.runLater(() -> {
            materializing.finished(written == null ? null : written.result(), failure);
            if (failure == null && written.materialized() == null) {
                statusLine.setText("Stopped before any file was materialized.");
                return;
            }
            showMaterialized(relative, written == null ? null : written.materialized(), failure);
        }));
    }

    /** Shows what a materialization wrote, or why it failed. Called on the JavaFX application thread. */
    private void showMaterialized(Path relative, Materialized materialized, Throwable failure) {
        if (failure != null) {
            Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
            statusLine.setText("Materialization failed: " + cause.getMessage());
            LOG.warn("Materializing {} failed", relative, cause);
            return;
        }
        preferences().put(PREF_DOCUMENT, materialized.name());
        syncPreferences();
        String report = (repositoryReport == null ? "" : repositoryReport + " ") + materialized.report();
        repositoryReport = null;
        CompletableFuture.supplyAsync(GitDocumentMaterializer::storedDocuments).whenComplete((found, listFailure) -> Platform.runLater(() -> {
            if (listFailure != null) {
                statusLine.setText(report);
                return;
            }
            anchors = new LinkedHashMap<>(found);
            // Two repositories can hold a document of one name (a clone beside its origin): the
            // one just materialized is the one this name opens from now on.
            anchors.put(materialized.name(), materialized.anchor());
            documents.getItems().setAll(anchors.keySet());
            if (materialized.name().equals(documents.getValue())) {
                open(materialized.anchor(), report, null);
            } else {
                reportAfterOpen = report;
                documents.setValue(materialized.name());         // the chooser's listener opens it with the report
            }
        }));
    }

    /**
     * The run of the last materialization of a file with includes, for automation and tests: its
     * state, and Pause, Resume and Stop as the strip's buttons work them.
     *
     * @return the run, or empty before the card has materialized an assembly
     */
    public Optional<AssemblyRun> materializationRun() {
        return Optional.ofNullable(materializing.run());
    }

    /** @return whether the card is materializing a file and its includes now, for automation and tests */
    public boolean materializing() {
        return materializing.running();
    }

    /** @return the progress strip's text as Markdown, for automation and tests */
    public String materializationReport() {
        return materializing.markdown();
    }

    // ── ephemeral documents: rebuilt from git (IKE-Network/ike-issues#1313) ─────

    /**
     * Drops the shown document from the knowledge base and materializes its file again. Only an
     * ephemeral document is rebuilt: the repository holds its lines, their authors and the
     * annotations signed into commits, so nothing is lost; an edit pending in Komet refuses it.
     */
    private void rebuildFromGit() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<Path> file = currentFile();
        if (file.isEmpty() || !Files.isRegularFile(repoRoot.resolve(file.get()))) {
            statusLine.setText("No working tree holds the shown document's file; Open file… names one.");
            return;
        }
        PublicId anchor = shown.anchor();
        Path root = repoRoot;
        Path relative = file.get();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Rebuild from Git");
        confirm.setHeaderText("Rebuild " + relative + " from git?");
        confirm.setContentText("The document is dropped from the knowledge base and its file's history materialized again. "
                + "Git holds everything an ephemeral document is: its lines, who wrote them, and the annotations signed into commits.");
        ButtonType rebuild = new ButtonType("Rebuild");
        confirm.getButtonTypes().setAll(rebuild, ButtonType.CANCEL);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == rebuild).isEmpty()) {
            return;
        }
        statusLine.setText("Dropping " + relative + " from the knowledge base …");
        CompletableFuture.supplyAsync(() -> DocumentScopes.dropForRebuild(anchor)).whenComplete((dropped, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Not rebuilt: " + cause.getMessage());
                return;
            }
            materialize(root, relative, false);
        }));
    }

    /** Deletes the prose-element documents that vertex documents of the same working tree and path replace. */
    private void removeSuperseded() {
        CompletableFuture.supplyAsync(GitDocumentMaterializer::superseded).whenComplete((superseded, failure) -> Platform.runLater(() -> {
            if (failure != null || superseded.isEmpty()) {
                statusLine.setText(failure != null ? "Could not list superseded documents: " + failure.getMessage()
                        : "No document is superseded: every prose-element document is the only one of its working tree and path.");
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Remove Superseded Documents");
            confirm.setHeaderText("Delete " + superseded.size() + " superseded document" + (superseded.size() == 1 ? "" : "s") + "?");
            confirm.setContentText("Each was materialized as prose elements and has since been materialized as vertices from the same "
                    + "working tree and path. The lists already show the vertex document; git holds what both were.");
            ButtonType delete = new ButtonType("Delete");
            confirm.getButtonTypes().setAll(delete, ButtonType.CANCEL);
            confirm.initOwner(window());
            if (confirm.showAndWait().filter(b -> b == delete).isEmpty()) {
                return;
            }
            statusLine.setText("Deleting " + superseded.size() + " superseded document(s) …");
            CompletableFuture.supplyAsync(DocumentScopes::deleteSuperseded).whenComplete((cleared, deleteFailure) -> Platform.runLater(() -> {
                if (deleteFailure != null) {
                    Throwable cause = deleteFailure.getCause() != null ? deleteFailure.getCause() : deleteFailure;
                    statusLine.setText("Not deleted: " + cause.getMessage());
                    return;
                }
                statusLine.setText("Deleted " + cleared.deleted() + " superseded document(s): " + cleared.components() + " entities, "
                        + cleared.stamps() + " STAMPs." + (cleared.skipped().isEmpty() ? "" : " Left " + cleared.skipped().size() + ": "
                        + cleared.skipped().getFirst()));
                refresh();
            }));
        }));
    }

    // ── export ────────────────────────────────────────────────────────────────

    private void chooseExportTarget() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Working tree to export " + documents.getValue() + " into");
        File chosen = chooser.showDialog(window());
        if (chosen != null) {
            exportTo(chosen.toPath());
        }
    }

    /**
     * Writes the shown document from the store into a working tree at its recorded path and
     * reports how it relates to the file at HEAD there; off the FX thread.
     *
     * @param repoRoot the working tree root
     */
    public void exportTo(Path repoRoot) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        PublicId anchor = shown.anchor();
        statusLine.setText("Exporting to " + repoRoot + " …");
        CompletableFuture.supplyAsync(() -> {
            try {
                GitDocumentExporter.Exported exported = GitDocumentExporter.export(anchor).orElseThrow();
                Path file = GitDocumentExporter.writeTo(exported, repoRoot);
                return file + " — " + describe(GitDocumentExporter.compare(exported, repoRoot));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((report, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Export failed: " + failure.getMessage());
                LOG.warn("Exporting {} failed", anchor, failure);
                return;
            }
            statusLine.setText("Exported " + report);
        }));
    }

    private static String describe(GitDocumentExporter.Comparison comparison) {
        return switch (comparison.relation()) {
            case IDENTICAL_TO_HEAD -> "identical to HEAD, " + comparison.exportedLines() + " lines";
            case DIFFERS_FROM_HEAD -> "differs from HEAD at line " + comparison.firstDifference() + " (" + comparison.exportedLines()
                    + " exported lines, " + comparison.headLines() + " at HEAD)";
            case NOT_IN_HEAD -> "not in HEAD there, " + comparison.exportedLines() + " lines written";
        };
    }

    // ── annotating ────────────────────────────────────────────────────────────

    /** The annotation kinds' display names: "Comment" for the kind whose birth name only tells it apart in the graph. */
    public static String displayName(AnnotationKind kind) {
        return kind == AnnotationKind.COMMENT ? "Comment" : kind.label();
    }

    @Override
    public void annotate(AnnotationKind kind) {
        if (kind == AnnotationKind.RETRACTION) {
            retractSelection();
            return;
        }
        // An annotation is a sentence beside a line, so it is written where it will live: in the margin, no dialog.
        // An affirmation says nothing but itself, so it is recorded at once.
        Optional<Prepared> prepared = prepareAnnotation(kind);
        if (prepared.isEmpty()) {
            return;
        }
        if (kind == AnnotationKind.AFFIRMATION) {
            recordAnnotation(kind, prepared.get(), "", signsAnnotations());
            return;
        }
        if (surface.view() != DocumentSurfaceView.View.READING) {
            // the margin is the Reading view's: from the source, the pages or the graph the note is asked for in a dialog
            annotateSelection(kind, null, null);
            return;
        }
        String prompt = kind == AnnotationKind.REFUTATION ? "Why do you dispute these lines?" : "Your comment";
        surface.composeAnnotation(kind, prompt, kind == AnnotationKind.REFUTATION, signsAnnotations(),
                text -> recordAnnotation(kind, prepared.get(), text, signsAnnotations()));
    }

    private static final String PREF_SIGN_ANNOTATIONS = "network.ike.komet.document.signAnnotations";
    private Boolean signAnnotations;

    /**
     * Whether an annotation's commit is signed, and the commit of an edit: a standing choice,
     * remembered with the card, and at first what the person's git is set to do
     * ({@code commit.gpgsign}).
     */
    @Override
    public boolean signsAnnotations() {
        if (signAnnotations == null) {
            String remembered = preferences().get(PREF_SIGN_ANNOTATIONS, "");
            if (!remembered.isEmpty()) {
                signAnnotations = Boolean.parseBoolean(remembered);
            } else if (repoRoot == null) {
                return false;                                      // git's own setting is read once a working tree is known
            } else {
                signAnnotations = gitSignsByDefault();
            }
        }
        return signAnnotations;
    }

    @Override
    public void layerShown(DocumentSurfaceView.Layer layer) {
        CheckMenuItem item = layerItems.get(layer);
        if (item != null) {
            item.setSelected(true);                                // its listener remembers the choice
        }
    }

    @Override
    public void layerRequested(DocumentSurfaceView.Layer layer, boolean on) {
        CheckMenuItem item = layerItems.get(layer);
        if (item != null) {
            item.setSelected(on);                                  // its listener draws the layer and remembers the choice
        }
    }

    @Override
    public void setSignsAnnotations(boolean sign) {
        signAnnotations = sign;
        preferences().put(PREF_SIGN_ANNOTATIONS, Boolean.toString(sign));
        syncPreferences();
        pendingBar.showSigned(sign);
        statusLine.setText(sign ? "Your commits and annotations are signed." : "Your commits and annotations are not signed.");
    }

    /** What git itself would do: {@code commit.gpgsign} in the working tree's configuration, false when unset or unreadable. */
    private boolean gitSignsByDefault() {
        if (repoRoot == null) {
            return false;
        }
        try {
            GitOperations.Run run = GitOperations.git(repoRoot, "config", "--bool", "--get", "commit.gpgsign");
            return run.ok() && run.output().strip().equals("true");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The assistant's hand-off of a retraction ({@code StampTools}): open the same confirmation
     * the context menu's Retract… opens for the named annotation commit, with the reason prefilled
     * for the person to edit. The person's click is the retraction; nothing is written here.
     *
     * @param sha  the annotation commit to withdraw, in full
     * @param text the reason the person gave, or {@code null}
     */
    public void proposeRetraction(String sha, String text) {
        Platform.runLater(() -> confirmRetraction(sha, shownPath, "The assistant prepared this retraction at your request.", text));
    }

    /**
     * Retract… on the selection: the annotations in force on the selected lines are the choices,
     * each by the commit that recorded it; one is confirmed outright, several are chosen from.
     */
    private void retractSelection() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<DocumentAssembly.Located> located = located();
        if (located.isEmpty()) {
            statusLine.setText("Select a line an annotation covers, then choose Retract… from its menu.");
            return;
        }
        DocumentAssembly.Member member = located.get().member();
        Map<String, String> choices = new LinkedHashMap<>();
        for (int o = located.get().local().min(); o <= located.get().local().max() && o < member.document().lines().size(); o++) {
            for (GitDocumentMaterializer.StoredAnnotation annotation : member.document().lines().get(o).inForce()) {
                if (annotation.stamp() == null) {
                    continue;
                }
                summaries.sha(annotation.stamp().nid()).ifPresent(sha ->
                        choices.putIfAbsent(sha, sha.substring(0, 7) + "  " + ProvenancePopover.annotationText(annotation, view())));
            }
        }
        if (choices.isEmpty()) {
            statusLine.setText("No annotation is in force on the selected lines; there is nothing to retract.");
            return;
        }
        if (choices.size() == 1) {
            confirmRetraction(choices.keySet().iterator().next(), member.path(), null, null);
            return;
        }
        ChoiceDialog<String> choose = new ChoiceDialog<>(choices.values().iterator().next(), choices.values());
        choose.setTitle("Retract Annotation");
        choose.setHeaderText("Which annotation of the selected lines do you withdraw? A retraction withdraws every annotation its commit recorded.");
        choose.getDialogPane().setId("stampedDocumentRetractChoice");
        choose.initOwner(window());
        choose.showAndWait().ifPresent(label -> choices.entrySet().stream().filter(e -> e.getValue().equals(label)).findFirst()
                .ifPresent(e -> confirmRetraction(e.getKey(), member.path(), null, null)));
    }

    /**
     * Confirms and writes a retraction: the person's own empty commit with one {@code Annotation:}
     * trailer naming the annotation commit, the reason as its body, signed or not as they choose;
     * then the commit is re-read into the store, where the retracted annotation gets an inactive
     * version and its lines read as before it.
     */
    private void confirmRetraction(String sha, String memberPath, String headerNote, String initialText) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        if (repoRoot == null || memberPath == null || !Files.isRegularFile(repoRoot.resolve(memberPath))) {
            Optional<Path> chosen = workingTree(memberPath == null ? shownPath : memberPath);
            if (chosen.isEmpty()) {
                return;
            }
        }
        Annotation annotation = Annotation.of(AnnotationKind.RETRACTION, new AnnotationTarget.Commit(sha));
        String about = summaries.records().containsKey(sha)
                ? sha.substring(0, 7) + " (" + summaries.records().get(sha).summary() + ", by " + summaries.records().get(sha).author().name() + ")"
                : sha.substring(0, 7);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Retract Annotation");
        confirm.getDialogPane().setId("stampedDocumentRetractionDialog");
        confirm.setHeaderText((headerNote == null ? "" : headerNote + "\n")
                + "Withdraw the annotation recorded as commit " + about + " as an empty commit?\n"
                + "Every annotation that commit recorded on this file loses force; a judgment it had replaced does not come back, "
                + "and later annotations stand.");
        TextArea text = new TextArea(initialText == null ? "" : initialText);
        text.setId("stampedDocumentRetractionText");
        text.setPromptText("Why do you withdraw it? (required)");
        text.setWrapText(true);
        text.setPrefRowCount(4);
        Label details = new Label(AnnotationCommit.subject(List.of(annotation)) + "\n\n" + annotation.trailer()
                + "\n\nThe text becomes the commit body. Signing (-S) gives the retraction non-repudiation.");
        details.setId("stampedDocumentRetractionTrailer");
        details.setWrapText(true);
        confirm.getDialogPane().setContent(new VBox(8, text, details));
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        for (ButtonType type : List.of(signed, unsigned)) {
            confirm.getDialogPane().lookupButton(type).disableProperty().bind(text.textProperty().isEmpty());
        }
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        String body = text.getText();
        Path root = repoRoot;
        Path file = Path.of(memberPath == null ? shownPath : memberPath);
        PublicId anchor = shown.anchor();
        statusLine.setText("Writing retraction commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                AnnotationCommit.Result result = AnnotationCommit.write(root, List.of(annotation), body, sign);
                if (result.ok()) {
                    GitDocumentMaterializer.materialize(root, root.resolve(file), true);   // the retraction reaches the store: the annotation retires
                }
                return result;
            } catch (IOException e) {
                return new AnnotationCommit.Result(false, "", e.getMessage());
            }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText("Retraction not written: " + failure.getMessage());
                LOG.warn("Retraction commit failed in {}", root, failure);
            } else if (result.ok()) {
                open(anchor, "Retraction recorded as commit " + result.sha().substring(0, Math.min(7, result.sha().length()))
                        + " and read into the store.", null);
            } else {
                statusLine.setText("Retraction not written: " + result.output());
                LOG.warn("Retraction commit failed in {}: {}", root, result.output());
            }
        }));
    }

    /**
     * The assistant's hand-off ({@code StampTools}): select the lines of the given ranges and
     * open the same confirmation the context menu opens, with the text prefilled for the person
     * to edit. The person's click is the annotation; nothing is written here.
     *
     * @param kind   the annotation kind
     * @param ranges 1-based inclusive line ranges of the committed file
     * @param text   the annotation text the person gave, or {@code null}
     */
    public void proposeAnnotation(AnnotationKind kind, List<int[]> ranges, String text) {
        Platform.runLater(() -> {
            LineSelection wanted = LineSelection.covering(ranges, this::ordinalOf);
            if (wanted == null) {
                statusLine.setText("None of the proposed lines is in the shown document.");
                return;
            }
            surface.select(wanted);
            annotateSelection(kind, "The assistant prepared this " + displayName(kind).toLowerCase() + " at your request.", text);
        });
    }

    /** What an annotation of the selection would record: the trailers over the committed file at HEAD. */
    public record Proposal(List<Annotation> annotations, int lines, Path root, String head) {

        /** @return the trailers, one per range */
        String trailers() {
            return String.join("\n", annotations.stream().map(Annotation::trailer).toList());
        }
    }

    /**
     * Prepares the annotation of the given committed line ranges over the repository at HEAD:
     * refuses when the file is not in a known working tree or the file at HEAD is not the text
     * the store holds as committed, since the trailers must name the lines as the store numbers
     * them.
     *
     * @param kind      the kind
     * @param ranges    1-based inclusive ranges
     * @param root      the working tree
     * @param file      the file within it
     * @param committed the document's committed lines as the store holds them
     * @return the proposal
     * @throws IllegalStateException when the trailers cannot be anchored
     */
    public static Proposal prepare(AnnotationKind kind, List<int[]> ranges, Path root, Path file, List<String> committed) {
        if (root == null || file == null || !Files.isRegularFile(root.resolve(file))) {
            throw new IllegalStateException("No working tree holds the document's file; Open file… names one.");
        }
        if (ranges.isEmpty()) {
            throw new IllegalStateException("Select committed lines to annotate.");
        }
        try (GitRepo repo = GitRepo.open(root)) {
            String head = repo.headRevision();
            List<String> content = repo.headLines(file);
            if (!content.equals(committed)) {
                throw new IllegalStateException(file + " at HEAD (" + head.substring(0, Math.min(7, head.length()))
                        + ") is not the text the store holds as committed; Materialize first.");
            }
            List<Annotation> annotations = new ArrayList<>();
            int lines = 0;
            for (int[] r : ranges) {
                if (r[1] > content.size()) {
                    throw new IllegalStateException("Lines " + r[0] + "-" + r[1] + " are beyond the " + content.size() + " lines of " + file + " at HEAD.");
                }
                annotations.add(Annotation.of(kind, LineRange.of(file, r[0], r[1], head, content)));
                lines += r[1] - r[0] + 1;
            }
            return new Proposal(annotations, lines, root, head);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + " at HEAD: " + e.getMessage(), e);
        }
    }

    /**
     * Prepares the annotation of one sentence (IKE-Network/ike-issues#1313): a column-qualified
     * range naming exactly the sentence's characters in the committed file at HEAD, which the walk
     * lands on the sentence itself and on the lines it holds whole.
     *
     * @param kind      the kind
     * @param sentence  the sentence's text as stored, whitespace normalized
     * @param ranges    the committed lines the sentence spans, 1-based inclusive
     * @param root      the working tree
     * @param file      the file within it
     * @param committed the document's committed lines as the store holds them
     * @return the proposal
     * @throws IllegalStateException when the trailer cannot be anchored
     */
    static Proposal prepareSentence(AnnotationKind kind, String sentence, List<int[]> ranges, Path root, Path file, List<String> committed) {
        Proposal lines = prepare(kind, ranges, root, file, committed);
        int first = ranges.stream().mapToInt(r -> r[0]).min().orElse(1);
        int last = ranges.stream().mapToInt(r -> r[1]).max().orElse(1);
        for (design.knowledge.stamp.adoc.dialect.Segmenter.Sentence found : design.knowledge.stamp.adoc.dialect.Segmenter
                .of(design.knowledge.stamp.adoc.dialect.IkeDialectParser.parse(committed)).sentences()) {
            if (found.text().equals(sentence) && found.lines().getFirst() >= first && found.lines().getLast() <= last) {
                LineRange range = LineRange.of(file, found.lines().getFirst(), found.flow().columnAt(found.start()) + 1,
                        found.lines().getLast(), found.flow().columnAt(found.end() - 1) + 1, lines.head(), committed);
                return new Proposal(List.of(Annotation.of(kind, range)), found.lines().size(), lines.root(), lines.head());
            }
        }
        throw new IllegalStateException("The sentence is not in the committed text of " + file + "; Materialize first.");
    }

    /**
     * Annotates the selected lines with {@code kind}: folds them into committed ranges, hashes
     * them over the committed text, asks for the annotation text and the signing choice, writes
     * the empty commit through the person's own git, and re-reads the commit into the store.
     */
    private void annotateSelection(AnnotationKind kind, String headerNote, String initialText) {
        Optional<Prepared> ready = prepareAnnotation(kind);
        if (ready.isEmpty()) {
            return;
        }
        Prepared prepared = ready.get();
        Proposal proposal = prepared.proposal();
        Path memberFile = prepared.memberFile();
        DocumentAssembly.Member member = prepared.member();
        int uncommitted = prepared.uncommitted();
        Optional<DocumentGraph.Node> sentence = prepared.sentence();
        String noun = sentence.isPresent() ? "the sentence on " + proposal.lines() + " line" + (proposal.lines() == 1 ? "" : "s") + " of " + memberFile
                : proposal.lines() + " line" + (proposal.lines() == 1 ? "" : "s") + " of " + memberFile;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(kind.verb() + " Lines");
        confirm.getDialogPane().setId("stampedDocumentAnnotationDialog");
        confirm.setHeaderText((headerNote == null ? "" : headerNote + "\n")
                + "Record your " + displayName(kind).toLowerCase() + (kind.judgment() ? " of " : " on ") + noun + " as an empty commit?"
                + (member.host() ? "" : "\nThe lines are in a document included by " + assembly.host().fileName() + "; the trailer names their own file.")
                + (uncommitted == 0 ? "" : "\n" + uncommitted + " selected line(s) are uncommitted edits and are left out."));
        TextArea text = new TextArea(initialText == null ? "" : initialText);
        text.setId("stampedDocumentAnnotationText");
        text.setPromptText(kind == AnnotationKind.REFUTATION ? "Why do you dispute these lines? (required)" : "Annotation text (optional)");
        text.setWrapText(true);
        text.setPrefRowCount(4);
        Label details = new Label(AnnotationCommit.subject(proposal.annotations()) + "\n\n" + proposal.trailers()
                + "\n\nThe text becomes the commit body. Signing (-S) gives the annotation non-repudiation.");
        details.setId("stampedDocumentAnnotationTrailers");
        details.setWrapText(true);
        confirm.getDialogPane().setContent(new VBox(8, text, details));
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        if (kind == AnnotationKind.REFUTATION) {                 // a refutation without a reason is weak
            for (ButtonType type : List.of(signed, unsigned)) {
                confirm.getDialogPane().lookupButton(type).disableProperty().bind(text.textProperty().isEmpty());
            }
        }
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        recordAnnotation(kind, prepared, text.getText(), choice.get() == signed);
    }

    /**
     * An annotation of the selection, ready to record: what the person must still give is its
     * text, and whether to sign.
     *
     * @param proposal    the annotations to write and the tree to write them in
     * @param member      the member the lines are in
     * @param memberFile  the member's file, relative to the tree
     * @param uncommitted how many selected lines are uncommitted edits, left out
     * @param sentence    the sentence annotated, when one was chosen in the Graph view
     */
    private record Prepared(Proposal proposal, DocumentAssembly.Member member, Path memberFile, int uncommitted, Optional<DocumentGraph.Node> sentence) { }

    /**
     * Prepares an annotation of the selection: the checks, the working tree, and the proposal
     * with its trailers; says on the status line what stands in the way when something does.
     *
     * @return the prepared annotation, or empty when it cannot be made
     */
    private Optional<Prepared> prepareAnnotation(AnnotationKind kind) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return Optional.empty();
        }
        Optional<LineSelection> selection = surface.selection();
        if (selection.isEmpty()) {
            statusLine.setText("Select the lines you have reviewed, then choose Affirm, Refute or Comment from their menu.");
            return Optional.empty();
        }
        Optional<DocumentAssembly.Located> located = located();
        if (located.isEmpty()) {
            statusLine.setText("The selection spans " + assembly.membersTouched(selection.get())
                    + " files; an annotation names one file's lines. Select within the host or within one included document.");
            return Optional.empty();
        }
        DocumentAssembly.Member member = located.get().member();
        Loaded loaded = members.get(member.path());
        int uncommitted = located.get().local().uncommitted(o -> committedNumber(member, o));
        List<int[]> ranges = selectedLineNumbers();
        if (ranges.isEmpty()) {
            statusLine.setText("The selected lines are edited in Komet and not committed; commit the edit before annotating them.");
            return Optional.empty();
        }
        if (repoRoot == null || loaded == null || loaded.path() == null || !Files.isRegularFile(repoRoot.resolve(loaded.path()))) {
            Optional<Path> chosen = annotationTree(kind, member.document().anchor(), loaded == null ? shownPath : loaded.path());
            if (chosen.isEmpty()) {
                return Optional.empty();
            }
        }
        Path memberFile = loaded == null || loaded.path() == null ? null : Path.of(loaded.path());
        // a sentence chosen in the Graph view's Elements mode is annotated as that sentence (#1313)
        Optional<DocumentGraph.Node> sentence = graph != null && surface.view() == DocumentSurfaceView.View.GRAPH
                ? graph.selectedSentence() : Optional.empty();
        try {
            Proposal proposal = sentence.isPresent()
                    ? prepareSentence(kind, sentence.get().markup().text(), ranges, repoRoot, memberFile,
                            loaded == null ? List.of() : loaded.committedLines())
                    : prepare(kind, ranges, repoRoot, memberFile, loaded == null ? List.of() : loaded.committedLines());
            return Optional.of(new Prepared(proposal, member, memberFile, uncommitted, sentence));
        } catch (IllegalStateException refused) {
            statusLine.setText(refused.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Records a prepared annotation as the person's own empty commit, signed or not, and reads
     * the commit back into the store.
     */
    private void recordAnnotation(AnnotationKind kind, Prepared prepared, String body, boolean sign) {
        Proposal proposal = prepared.proposal();
        Path root = proposal.root();
        Path file = prepared.memberFile();
        PublicId anchor = shown.anchor();
        statusLine.setText("Writing annotation commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                AnnotationCommit.Result result = AnnotationCommit.write(root, proposal.annotations(), body, sign);
                if (result.ok()) {
                    GitDocumentMaterializer.materialize(root, root.resolve(file), true);   // the annotation reaches the store, on the member's file
                }
                return result;
            } catch (IOException e) {
                return new AnnotationCommit.Result(false, "", e.getMessage());
            }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                statusLine.setText(displayName(kind) + " not written: " + failure.getMessage());
                LOG.warn("Annotation commit failed in {}", root, failure);
            } else if (result.ok()) {
                open(anchor, displayName(kind) + " recorded as commit " + result.sha().substring(0, Math.min(7, result.sha().length()))
                        + " and read into the store.", null);
            } else {
                statusLine.setText(displayName(kind) + " not written: " + result.output());
                LOG.warn("Annotation commit failed in {}: {}", root, result.output());
            }
        }));
    }

    /** The History view: the commits of the file and the repository, and the selected lines' STAMPs, pinned until closed. */
    @Override
    public void showHistory() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        HistoryDialog.show(window(), repoRoot, selectedFile().or(this::currentFile).orElse(null), summaries, surface::highlightCommit, surface.selectedLines(),
                view(), this::surfaceIdenticon, documents.getValue() == null ? "" : documents.getValue());
    }

    private final Map<Long, javafx.scene.image.Image> historyBadges = new HashMap<>();

    private javafx.scene.image.Image surfaceIdenticon(dev.ikm.tinkar.entity.StampEntity<?> stamp) {
        return historyBadges.computeIfAbsent(stamp.nid(), nid -> {
            UUID seed = stamp.publicId().asUuidArray()[0];
            byte[] png = network.ike.komet.document.KonceptIdenticon.png(dev.ikm.tinkar.common.id.PublicIds.of(seed).idString(),
                    network.ike.komet.document.KonceptIdenticon.DISPLAY_MODULE_SIZE);
            return new javafx.scene.image.Image(new java.io.ByteArrayInputStream(png));
        });
    }

    // ── the repository (phase 4) ──────────────────────────────────────────────

    @Override
    public boolean pendingEdit() {
        return pendingEdit;
    }

    @Override
    public void history() {
        showHistory();
    }

    /**
     * The Repository panel's Commit…: commits a file of the shown document that changed outside
     * Komet, in a dialog that names the file. An edit pending in Komet is committed from the bar
     * at the bottom of the card, so with one pending and no file changed outside, this puts the
     * caret in the bar's message.
     */
    @Override
    public void commit() {
        commitOutsideChangesOr(() -> {
            if (pendingEdit) {
                pendingBar.focusMessage();
                statusLine.setText("Your edit is committed from the bar above: type a message, or leave it empty, and choose Commit.");
            } else {
                commitPending("", signsAnnotations());             // says what is missing
            }
        });
    }

    /** The bar's Commit: the pending edit, with the message typed there, after any change made to the files outside Komet. */
    private void commitFromBar(String message, boolean sign) {
        commitOutsideChangesOr(() -> commitPending(message, sign));
    }

    /**
     * Commits what the shown document's files hold that HEAD does not: changes made outside Komet
     * first, one file at a time, which needs a dialog because the person chooses the file; then
     * the edit pending in Komet. Reads the files again, since nothing tells the card when one
     * changes.
     *
     * @param pending what to do when no file changed outside Komet
     */
    private void commitOutsideChangesOr(Runnable pending) {
        Path root = repoRoot;
        if (root == null || shown == null) {
            pending.run();                                         // says what is missing, or asks for the working tree
            return;
        }
        List<Loaded> read = members.values().stream().filter(l -> l.path() != null).toList();
        CompletableFuture.supplyAsync(() -> uncheck(() -> outsideChanges(root, read))).whenComplete((changes, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Could not read the working tree: " + cause.getMessage());
                return;
            }
            repositoryPanel.refresh();
            if (!changes.isEmpty()) {
                commitWorkingFile(root, changes);
            } else if (pendingEdit) {
                pending.run();
            } else {
                statusLine.setText("Nothing to commit: the document's files match HEAD.");
            }
        }));
    }

    /**
     * A file changed outside Komet, with a first draft of its message.
     *
     * @param path  the file, relative to the working tree
     * @param draft the draft
     */
    private record WorkingChange(String path, String draft) { }

    /** The members' files that differ from HEAD by more than the edit Komet wrote into them; off the FX thread. */
    private static List<WorkingChange> outsideChanges(Path root, List<Loaded> read) throws IOException {
        List<String> changed = GitOperations.changedFiles(root, read.stream().map(Loaded::path).toList());
        List<WorkingChange> changes = new ArrayList<>();
        for (Loaded loaded : read) {
            if (!changed.contains(loaded.path())) {
                continue;
            }
            List<String> working = Files.readAllLines(root.resolve(loaded.path()));
            // A commit that failed leaves the edit in the file: that is the edit, and Commit retries it.
            boolean holdsTheEdit = loaded.pending() && GitDocumentExporter.export(loaded.document().anchor(), true)
                    .filter(pending -> pending.lines().equals(working)).isPresent();
            if (!holdsTheEdit) {
                changes.add(new WorkingChange(loaded.path(), DocumentCommit.draftWorkingFileMessage(loaded.path(), headLines(root, loaded.path()), working)));
            }
        }
        return changes;
    }

    /** A file's lines at HEAD, empty when HEAD does not hold it. */
    private static List<String> headLines(Path root, String path) throws IOException {
        if (!GitOperations.committed(root, Path.of(path))) {
            return List.of();
        }
        try (GitRepo repo = GitRepo.open(root)) {
            return repo.headLines(Path.of(path));
        }
    }

    /** Asks for the message and the signing, commits one changed file as it stands, and reads the commit into the store. */
    private void commitWorkingFile(Path root, List<WorkingChange> changes) {
        TextArea message = new TextArea(changes.getFirst().draft());
        message.setId("stampedDocumentCommitFileMessage");         // the bar's one-line message is stampedDocumentCommitMessage
        message.setWrapText(true);
        message.setPrefRowCount(6);
        ComboBox<String> files = new ComboBox<>();
        files.setId("stampedDocumentCommitFile");
        files.getItems().setAll(changes.stream().map(WorkingChange::path).toList());
        files.setValue(changes.getFirst().path());
        files.setMaxWidth(Double.MAX_VALUE);
        files.valueProperty().addListener((obs, was, is) -> changes.stream().filter(c -> c.path().equals(is)).findFirst()
                .ifPresent(c -> message.setText(c.draft())));
        List<Node> above = changes.size() == 1 ? List.of() : List.of(new Label(changes.size() + " files changed; a commit holds one"), files);
        Optional<CommitChoice> choice = askCommit("Commit Changes Made Outside Komet", "stampedDocumentCommitFileDialog",
                changes.size() == 1 ? "Commit " + changes.getFirst().path() + " as it stands in " + root + "?" : "Commit a changed file in " + root + "?",
                above, message, "The file changed outside Komet. The commit holds this one file as it stands and is yours, made through your own git. "
                        + "Signing (-S) gives it non-repudiation."
                        + (pendingEdit ? " Your edit pending in Komet stays pending unless this commit touches what it edits; then it is settled against the commit, and what the commit did not carry is reported." : ""));
        if (choice.isEmpty()) {
            return;
        }
        String path = files.getValue();
        PublicId hostAnchor = shown.anchor();
        statusLine.setText("Committing " + path + " …");
        CompletableFuture.supplyAsync(() -> uncheck(() -> {
            DocumentCommit.Result result = DocumentCommit.workingFile(root, path, choice.get().message(), choice.get().sign());
            if (!result.ok()) {
                return "Not committed: " + result.output().replace('\n', ' ');
            }
            GitDocumentMaterializer.Result stored = GitDocumentMaterializer.materialize(root, root.resolve(path), true);
            DocumentCommits.committed(new DocumentCommits.Committed(root, path, result.sha()));
            return "Committed " + result.sha().substring(0, 7) + " (" + (choice.get().sign() ? "signed" : "unsigned") + ")"
                    + (DocumentEditor.pending(stored.anchor()).isPresent() ? "; " + describe(DocumentEditor.settle(stored.anchor())) : ".");
        })).whenComplete((report, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Commit failed: " + cause.getMessage());
                LOG.warn("Committing {} failed", path, cause);
                return;
            }
            open(hostAnchor, report, null);
        }));
    }

    /** Takes the person to the branch drop-down of the journal's Documents tray; a card outside a journal asks here instead. */
    @Override
    public void branch() {
        Path root = repoRoot;
        if (root == null) {
            statusLine.setText("No working tree; Open file… names one, New… makes one, or Clone… brings one.");
            return;
        }
        Optional<Path> file = fileOnDisk();
        if (file.isPresent() && DocumentNavigatorTray.revealBranches(fxObject(), file.get())) {
            return;
        }
        CompletableFuture.supplyAsync(() -> uncheck(() -> GitOperations.branches(root))).whenComplete((branches, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Could not list the branches: " + cause.getMessage());
                return;
            }
            askBranch(root, branches);
        }));
    }

    private void askBranch(Path root, GitOperations.Branches branches) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Branches");
        dialog.getDialogPane().setId("stampedDocumentBranchDialog");
        dialog.setHeaderText(root.getFileName() + " is on " + (branches.current().isEmpty() ? "no branch" : branches.current()) + ".");
        ComboBox<String> existing = new ComboBox<>();
        existing.setId("stampedDocumentBranchChoice");
        existing.getItems().addAll(branches.local());
        existing.getItems().addAll(branches.remote());
        existing.setMaxWidth(Double.MAX_VALUE);
        existing.setValue(branches.current().isEmpty() ? null : branches.current());
        javafx.scene.control.TextField created = new javafx.scene.control.TextField();
        created.setId("stampedDocumentBranchNew");
        created.setPromptText("The new branch's name");
        List<String> notes = new ArrayList<>();
        notes.add("Switching changes the files in the working tree, and the document follows. Uncommitted changes that would be overwritten refuse the switch.");
        if (pendingEdit) {
            notes.add("An edit is pending in Komet, so only a new branch is open: it starts here and changes no file. Commit or Discard…, at the bottom of the card, before switching.");
        }
        GitOperations.syncedFolder(root).ifPresent(synced -> notes.add("This repository is in the synced folder " + synced
                + ": the files a switch changes reach your other machines, whose git stays on its own branch. A working set's members switch together with ws:switch-publish."));
        Label about = new Label(String.join("\n\n", notes));
        about.setWrapText(true);
        VBox content = new VBox(8, about, new Label("Switch to"), existing, new Label("Or start a new branch from HEAD"), created);
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        ButtonType switchTo = new ButtonType("Switch");
        ButtonType create = new ButtonType("Create and Switch");
        dialog.getDialogPane().getButtonTypes().setAll(switchTo, create, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(switchTo).disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> pendingEdit || existing.getValue() == null || existing.getValue().equals(branches.current()), existing.valueProperty()));
        dialog.getDialogPane().lookupButton(create).disableProperty().bind(created.textProperty().isEmpty());
        dialog.initOwner(window());
        Optional<ButtonType> choice = dialog.showAndWait();
        if (choice.filter(b -> b == create).isPresent()) {
            operate("switch -c", () -> uncheck(() -> GitOperations.createBranch(root, created.getText())), null);
        } else if (choice.filter(b -> b == switchTo).isPresent()) {
            String chosen = existing.getValue();
            if (branches.remote().contains(chosen)) {
                switchTo(root, chosen, () -> uncheck(() -> GitOperations.trackBranch(root, chosen)));
            } else {
                switchBranch(chosen);
            }
        }
    }

    /**
     * Switches the working tree to a local branch, and brings the shown document to it; public so
     * UI automation can do it without the dialog.
     *
     * @param branch the local branch
     */
    public void switchBranch(String branch) {
        Path root = repoRoot;
        switchTo(root, branch, () -> uncheck(() -> GitOperations.switchBranch(root, branch)));
    }

    /** Refuses while an edit is pending, since a switch would change the file under it; else switches, and the document follows. */
    private void switchTo(Path root, String branch, java.util.function.Supplier<GitOperations.Run> operation) {
        if (pendingEdit) {
            statusLine.setText("An edit is pending: Commit or Discard…, above, before switching branches.");
            return;
        }
        operate("switch " + branch, operation, run -> {
            if (run.ok() && shown != null) {
                followBranch(root, run.report("switch").replace('\n', ' '));
            }
        });
    }

    /**
     * Brings the card to a branch the Documents tray switched its working tree to.
     *
     * @param report what git said
     * @return done once the store holds the branch, so that cards follow one at a time
     */
    CompletableFuture<Void> followSwitch(String report) {
        repositoryPanel.refresh();
        if (shown == null) {
            statusLine.setText(report);
            return CompletableFuture.completedFuture(null);
        }
        return followBranch(repoRoot, report);
    }

    /** Brings the shown document and the documents it includes to HEAD, and reads them again. */
    private CompletableFuture<Void> followBranch(Path root, String report) {
        PublicId hostAnchor = shown.anchor();
        List<DocumentBranch.Member> followed = members.values().stream().filter(l -> l.path() != null)
                .map(l -> new DocumentBranch.Member(l.document().anchor(), l.path())).toList();
        statusLine.setText(report + " Reading the branch into the knowledge base …");
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture.supplyAsync(() -> uncheck(() -> DocumentBranch.follow(root, followed))).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText(report + " The document did not follow: " + cause.getMessage());
                LOG.warn("Following the branch in {} failed", root, cause);
            } else {
                open(hostAnchor, (report + " " + result.report()).strip(), null);
            }
            done.complete(null);
        }));
        return done;
    }

    /** Runs one git operation off the FX thread, reports what git said, and re-reads the panel. */
    private void operate(String verb, java.util.function.Supplier<GitOperations.Run> operation, java.util.function.Consumer<GitOperations.Run> then) {
        if (repoRoot == null) {
            statusLine.setText("No working tree; Open file… names one, New… makes one, or Clone… brings one.");
            return;
        }
        statusLine.setText("git " + verb + " …");
        CompletableFuture.supplyAsync(operation).whenComplete((run, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("git " + verb + " could not run: " + cause.getMessage());
                LOG.warn("git {} in {} failed", verb, repoRoot, cause);
                return;
            }
            statusLine.setText((run.ok() ? "git " + verb + ": " : "git " + verb + " refused: ") + run.report(verb).replace('\n', ' '));
            repositoryPanel.refresh();
            if (then != null) {
                then.accept(run);
            }
        }));
    }

    @Override
    public void fetch() {
        Path root = repoRoot;
        operate("fetch", () -> uncheck(() -> GitOperations.fetch(root)), null);
    }

    @Override
    public void pull() {
        Path root = repoRoot;
        Optional<String> before = uncheck(() -> GitOperations.head(root));
        operate("pull --ff-only", () -> uncheck(() -> GitOperations.pullFastForwardOnly(root)), run -> {
            Optional<Path> file = currentFile();
            Optional<String> after = uncheck(() -> GitOperations.head(root));
            if (run.ok() && !before.equals(after) && file.isPresent() && Files.isRegularFile(root.resolve(file.get()))) {
                // The pull moved HEAD: the commits it brought reach the store the way Open file… does.
                materializeFile(root, file.get());
            }
        });
    }

    @Override
    public void push() {
        Optional<GitOperations.RepositoryState> state = repositoryPanel.state();
        if (state.isEmpty()) {
            statusLine.setText("Nothing known about the repository yet.");
            return;
        }
        boolean first = state.get().upstream().isEmpty();
        Optional<GitOperations.Remote> target = state.get().pushTarget();
        if (first && target.isEmpty()) {
            statusLine.setText("Nowhere to push: Add remote… names a repository for this branch.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Push");
        confirm.getDialogPane().setId("stampedDocumentPushDialog");
        confirm.setHeaderText(first
                ? "Push " + state.get().branch() + " of " + state.get().root().getFileName() + " to " + target.get().name()
                        + " (" + target.get().url() + ")? That branch becomes its upstream."
                : "Push " + state.get().ahead() + " commit(s) of " + state.get().branch() + " to " + state.get().upstream().get() + "?");
        confirm.setContentText("Through your own git, with its credentials. What git says is reported on the status line.");
        ButtonType push = new ButtonType("Push");
        confirm.getButtonTypes().setAll(push, ButtonType.CANCEL);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == push).isEmpty()) {
            return;
        }
        Path root = repoRoot;
        String branch = state.get().branch();
        operate("push", () -> uncheck(() -> first ? GitOperations.pushSettingUpstream(root, target.get().name(), branch) : GitOperations.push(root)), null);
    }

    /** Asks for a folder and makes a new repository there, as the working tree. */
    @Override
    public void newRepository() {
        if (pendingEdit) {
            statusLine.setText("An edit is pending: Commit or Discard…, above, before changing the working tree.");
            return;
        }
        Path library = DocumentLibrary.root();
        FolderPanel.show(window(), "Create a Repository", "Choose a folder for a new repository, or make one with New Folder.",
                        Files.exists(library.resolve(".git")) ? library.getParent() : library, panelPlaces(repoRoot), DocumentRepository::making)
                .ifPresent(this::createRepository);
    }

    /**
     * Makes a new repository and makes it the working tree; public so UI automation can do it
     * without the dialog.
     *
     * @param folder the folder to make it in
     */
    public void createRepository(Path folder) {
        statusLine.setText("git init " + folder + " …");
        CompletableFuture.supplyAsync(() -> uncheck(() -> GitOperations.init(folder))).whenComplete((run, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("git init could not run: " + cause.getMessage());
                return;
            }
            if (!run.ok()) {
                statusLine.setText("git init refused: " + run.report("init").replace('\n', ' '));
                return;
            }
            useWorkingTree(folder);
            statusLine.setText("Made a repository at " + repoRoot + ". Open file… chooses a document for it; the assistant can create one in it.");
        }));
    }

    /** Asks for a remote's name and URL and adds it to the working tree. */
    @Override
    public void addRemote() {
        Optional<GitOperations.RepositoryState> state = repositoryPanel.state();
        if (state.isEmpty()) {
            statusLine.setText("Nothing known about the repository yet.");
            return;
        }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Add a Remote");
        dialog.getDialogPane().setId("stampedDocumentAddRemoteDialog");
        dialog.setHeaderText("Add a remote to " + state.get().root().getFileName() + ".");
        javafx.scene.control.TextField name = new javafx.scene.control.TextField(state.get().remotes().isEmpty() ? "origin" : "");
        name.setId("stampedDocumentRemoteName");
        javafx.scene.control.TextField url = new javafx.scene.control.TextField();
        url.setId("stampedDocumentRemoteUrl");
        url.setPromptText("https://github.com/owner/repository.git or git@github.com:owner/repository.git");
        Label about = new Label("The repository must already exist there. Nothing is contacted until you push.");
        about.setWrapText(true);
        VBox content = new VBox(8, about, new Label("Name"), name, new Label("Repository URL"), url);
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        ButtonType add = new ButtonType("Add");
        dialog.getDialogPane().getButtonTypes().setAll(add, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(add).disableProperty().bind(name.textProperty().isEmpty().or(url.textProperty().isEmpty()));
        dialog.initOwner(window());
        if (dialog.showAndWait().filter(b -> b == add).isEmpty()) {
            return;
        }
        Path root = repoRoot;
        operate("remote add", () -> uncheck(() -> GitOperations.addRemote(root, name.getText(), url.getText())), null);
    }

    /** Makes a folder the working tree, remembers it, and has the panel read it. */
    private void useWorkingTree(Path root) {
        repoRoot = root.toAbsolutePath().normalize();
        DocumentNarratives.workingTreeOpened(repoRoot);
        preferences().put(PREF_WORKING_TREE, repoRoot.toString());
        syncPreferences();
        repositoryPanel.refresh();
    }

    @Override
    public void cloneRepository() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Clone a Repository");
        dialog.getDialogPane().setId("stampedDocumentCloneDialog");
        dialog.setHeaderText("Clone a repository into a new or empty folder, through your own git.");
        javafx.scene.control.TextField url = new javafx.scene.control.TextField();
        url.setId("stampedDocumentCloneUrl");
        url.setPromptText("https://github.com/owner/repository.git or git@github.com:owner/repository.git");
        javafx.scene.control.TextField folder = new javafx.scene.control.TextField();
        folder.setId("stampedDocumentCloneFolder");
        folder.setPromptText("The folder to create; it must not exist, or be empty");
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Parent folder of the clone");
            File chosen = chooser.showDialog(window());
            if (chosen != null) {
                String name = url.getText().replaceFirst("/+$", "").replaceFirst("\\.git$", "");
                name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
                folder.setText(chosen.toPath().resolve(name.isEmpty() ? "repository" : name).toString());
            }
        });
        HBox folderRow = new HBox(6, folder, browse);
        HBox.setHgrow(folder, javafx.scene.layout.Priority.ALWAYS);
        VBox content = new VBox(8, new Label("Repository URL"), url, new Label("Clone into"), folderRow);
        content.setPrefWidth(560);
        dialog.getDialogPane().setContent(content);
        ButtonType clone = new ButtonType("Clone");
        dialog.getDialogPane().getButtonTypes().setAll(clone, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(clone).disableProperty().bind(url.textProperty().isEmpty().or(folder.textProperty().isEmpty()));
        dialog.initOwner(window());
        if (dialog.showAndWait().filter(b -> b == clone).isEmpty()) {
            return;
        }
        cloneInto(url.getText().strip(), Path.of(folder.getText().strip()));
    }

    /**
     * Clones through the person's git into a folder that does not exist or is empty, and makes
     * the clone the working tree; public so UI automation can clone without the dialog.
     *
     * @param url    the repository URL
     * @param target the folder to create the clone in
     */
    public void cloneInto(String url, Path target) {
        statusLine.setText("git clone " + url + " …");
        CompletableFuture.supplyAsync(() -> uncheck(() -> GitOperations.clone(url, target))).whenComplete((run, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("git clone could not run: " + cause.getMessage());
                return;
            }
            if (!run.ok()) {
                statusLine.setText("git clone refused: " + run.report("clone").replace('\n', ' '));
                return;
            }
            useWorkingTree(target);
            statusLine.setText("Cloned into " + repoRoot + "; Open file… chooses a document in it. " + run.output().replace('\n', ' '));
        }));
    }

    private interface IoCall<T> {
        T call() throws IOException;
    }

    private static <T> T uncheck(IoCall<T> call) {
        try {
            return call.call();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── editing ───────────────────────────────────────────────────────────────

    /**
     * Moves the card's view to a time, for UI automation; the coordinates menu's time does the
     * same, and the document is read again as it stood then.
     *
     * @param time the time, {@link Long#MAX_VALUE} for the latest
     */
    public void setViewTime(long time) {
        if (getCardViewProperties() != null) {
            getCardViewProperties().nodeView().stampCoordinate().timeProperty().setValue(time);
        }
    }

    /** Opens a block for editing in place, for UI automation; the context menu's Edit block does the same for the selection. */
    public boolean editBlock(long blockNid) {
        return surface.editBlock(blockNid);
    }

    /** A saved block edit: the action's result, and what a structured block wrote of what it states, when it did. */
    private record Saved(dev.ikm.komet.actions.KometActionResult result, Optional<String> wrote) {
    }

    /**
     * Saves an edit of one block as uncommitted versions by performing the starter set's
     * {@code Edit document block} action, off the FX thread, and re-reads the document. A
     * structured block then writes what it states, a decision tree a version of its tree, an ANF
     * statement its statement and a complex concept its specification, under
     * the same edit. A refused or failed run leaves the editor open with the reason.
     */
    @Override
    public void saveEdit(StoredBlock block, String text) {
        PublicId anchor = shown.anchor();
        EditCoordinateRecord edit = editCoordinate();
        ViewCalculator view = view();
        statusLine.setText("Saving the edit …");
        CompletableFuture.supplyAsync(() -> {
                    Optional<String> previous = GitDocumentMaterializer.load(anchor, true)
                            .map(document -> String.join("\n", StructuredBlocks.source(document, block).lines()));
                    var result = KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK)
                            .perform(EditDocumentBlock.input(block.nid(), text), view, edit);
                    if (!result.completed()) {
                        return new Saved(result, Optional.empty());
                    }
                    StructuredBlockKind.Source source = StructuredBlockKind.Source.of(text);
                    return new Saved(result, StructuredBlocks.style(source.parameters()).flatMap(StructuredBlocks::kindFor)
                            .flatMap(kind -> kind.write(source, block.nid(), previous, view, edit)));
                })
                .whenComplete((saved, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Edit not saved: " + cause.getMessage());
                        LOG.warn("Editing block {} of {} failed", block.nid(), anchor, cause);
                        afterRead = null;                              // a replacement that waited on this edit does not go on
                        replaceBar.setDisable(false);
                        return;
                    }
                    if (!saved.result().completed()) {
                        statusLine.setText("Edit not saved: " + saved.result().message());
                        afterRead = null;
                        replaceBar.setDisable(false);
                        return;
                    }
                    surface.cancelEdit();
                    // only the file the block is in was written: the document's other files are as they were read
                    String changed = members.entrySet().stream().filter(file -> file.getValue().document().blocks().containsKey(block.nid()))
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    open(anchor, null, null, changed);
                    saved.wrote().ifPresent(wrote -> {
                        LOG.info("Block {} of {} saved: {}", block.nid(), anchor.idString(), wrote);
                        statusLine.setText(wrote);
                    });
                }));
    }

    /**
     * Add paragraph after… / Add listing after…: asks for the new block's text and performs the
     * starter set's {@code Add document block} action off the FX thread, so the new block lands as the
     * person's pending edit after the block the selection starts in; the card then re-reads the
     * document and shows the new lines with the edited mark.
     */
    @Override
    public void insertAfterSelection(DocumentInserts.Insertion insertion) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<StoredBlock> after = surface.selectedEditableBlock();
        if (after.isEmpty()) {
            statusLine.setText("Select a line of a paragraph or listing; the new " + insertion.label().toLowerCase() + " goes after its block.");
            return;
        }
        long afterNid = after.get().nid();
        PublicId anchor = shown.anchor();
        performStructural("Inserting the " + insertion.label().toLowerCase(), KometConcept.ADD_DOCUMENT_BLOCK,
                AddDocumentBlock.input(afterNid, insertion.kind(), insertion.text()),
                insertion.opensForEditing() ? () -> editBlockAfter(anchor, afterNid) : null);
    }

    /** Opens the block that now follows one for editing: the block Insert added, once the document is read again. */
    private void editBlockAfter(PublicId anchor, long after) {
        Optional<GitDocumentMaterializer.StoredDocument> document = GitDocumentMaterializer.load(anchor, true);
        if (document.isEmpty()) {
            return;
        }
        List<DocumentOutline.Node> nodes = new DocumentOutline(document.get()).nodes();
        for (int i = 0; i + 1 < nodes.size(); i++) {
            if (nodes.get(i).block().nid() == after) {
                if (!surface.editBlock(nodes.get(i + 1).block().nid())) {
                    statusLine.setText("The new block is pending; Edit block opens it.");
                }
                return;
            }
        }
    }

    @Override
    public void printPdf() {
        chooseAndPrint();
    }

    /** Delete block…: confirms, then performs the starter set's {@code Delete document block} action on the block the selection starts in. */
    @Override
    public void deleteSelectedBlock() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        Optional<StoredBlock> block = surface.selectedEditableBlock();
        if (block.isEmpty()) {
            statusLine.setText("Select a line of a paragraph or listing to delete its block.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Block");
        confirm.getDialogPane().setId("stampedDocumentDeleteBlockDialog");
        confirm.setHeaderText("Delete this " + block.get().kind().name().toLowerCase() + " of " + block.get().members().size()
                + " line" + (block.get().members().size() == 1 ? "" : "s") + "?\nIt leaves the document as your pending edit: Commit records "
                + "the removal in git, Discard… brings it back.");
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() != ButtonType.OK) {
            return;
        }
        performStructural("Deleting the block", KometConcept.DELETE_DOCUMENT_BLOCK, DeleteDocumentBlock.input(block.get().nid()));
    }

    /** Performs an action that changes the document's structure off the FX thread and re-reads the document. */
    private void performStructural(String doing, dev.ikm.tinkar.terms.ConceptFacade action, dev.ikm.komet.actions.KometActionInput input) {
        performStructural(doing, action, input, null);
    }

    /**
     * Performs an action that changes the document's structure off the FX thread, re-reads the
     * document, then runs what follows the showing, such as opening the block Insert added.
     */
    private void performStructural(String doing, dev.ikm.tinkar.terms.ConceptFacade action, dev.ikm.komet.actions.KometActionInput input,
                                   Runnable afterShow) {
        PublicId anchor = shown.anchor();
        EditCoordinateRecord edit = editCoordinate();
        ViewCalculator view = view();
        statusLine.setText(doing + " …");
        CompletableFuture.supplyAsync(() -> KometActions.expect(action).perform(input, view, edit))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText(doing + " failed: " + cause.getMessage());
                        LOG.warn("{} of {} failed", doing, anchor, cause);
                        return;
                    }
                    if (!result.completed()) {
                        statusLine.setText(doing + " refused: " + result.message());
                        return;
                    }
                    open(anchor, "Pending: " + result.message(), afterShow);
                }));
    }

    /** Asks for a sentence's new text, then saves it as {@link #saveSentence} does. */
    private void askToEditSentence(long sentence, String text) {
        TextInputDialog dialog = new TextInputDialog(text);
        dialog.setTitle("Edit sentence");
        dialog.setHeaderText("The sentence's new text. Only the lines it changes are rewritten; it stays uncommitted until you commit in git.");
        dialog.getEditor().setPrefColumnCount(72);
        dialog.showAndWait().filter(t -> !t.isBlank() && !t.equals(text)).ifPresent(t -> saveSentence(sentence, t));
    }

    /**
     * Saves an edit of one sentence as uncommitted versions by performing the starter set's
     * {@code Edit document sentence} action, off the FX thread, and re-reads the document
     * (IKE-Network/ike-issues#1313).
     *
     * @param sentence the sentence's vertex nid
     * @param text     its new text
     */
    public void saveSentence(long sentence, String text) {
        PublicId anchor = shown.anchor();
        EditCoordinateRecord edit = editCoordinate();
        ViewCalculator view = view();
        statusLine.setText("Saving the edit …");
        CompletableFuture.supplyAsync(() -> KometActions.expect(KometConcept.EDIT_DOCUMENT_SENTENCE)
                        .perform(EditDocumentSentence.input(sentence, text), view, edit))
                .whenComplete((result, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Edit not saved: " + cause.getMessage());
                        LOG.warn("Editing sentence {} of {} failed", sentence, anchor, cause);
                        return;
                    }
                    statusLine.setText(result.completed() ? result.message() : "Edit not saved: " + result.message());
                    if (result.completed()) {
                        open(anchor);
                    }
                }));
    }

    /** The card's edit coordinate, for the assistant's tools, which write under their own author on its module and path. */
    public EditCoordinateRecord editCoordinateForTools() {
        return editCoordinate();
    }

    /** The card's edit coordinate, or the default one before the card is hosted. */
    private EditCoordinateRecord editCoordinate() {
        return getCardViewProperties() != null ? getCardViewProperties().nodeView().editCoordinate().toEditCoordinateRecord()
                : Coordinates.Edit.Default();
    }

    /**
     * The working tree to commit into: the one known; else the one tree beside it that holds the
     * file, for a child included from another repository ({@link WorkingTrees}); else the one
     * Komet finds for the document ({@link DocumentHome}); else one the person locates.
     */
    private Optional<Path> workingTree(String path) {
        if (holdsFile(path)) {
            return Optional.of(repoRoot);
        }
        if (repoRoot != null && path != null) {
            List<Path> beside = WorkingTrees.beside(repoRoot).holding(path);
            if (beside.size() == 1) {
                return Optional.of(beside.getFirst());
            }
        }
        return locateWorkingTree(path);
    }

    /**
     * The working tree to annotate in: the one known, or the one Komet finds for the document
     * ({@link DocumentHome}), or, when none holds the file, what the person chooses in the folder
     * panel. Choosing the repository that holds the file carries
     * on; choosing any other folder takes a copy. The copy is a new document with a history of
     * its own, so nothing is annotated yet and the person selects its lines again. An assembly
     * can only be located, since a copy would lose its includes.
     *
     * @param kind   the annotation's kind
     * @param anchor the document's anchor
     * @param path   the file's repository-relative path, or {@code null} when none is recorded
     * @return the working tree, or empty when the person cancelled or the copy is under way
     */
    private Optional<Path> annotationTree(AnnotationKind kind, PublicId anchor, String path) {
        if (holdsFile(path)) {
            return Optional.of(repoRoot);
        }
        if (path == null) {
            return locateWorkingTree(path);
        }
        Optional<Path> home = home(anchor, repoRoot);
        if (home.isPresent()) {
            useWorkingTree(home.get());
            return Optional.of(repoRoot);
        }
        boolean copy = members.size() <= 1;
        String name = Path.of(path).getFileName().toString();
        Optional<DocumentRepository.Plan> plan = choosePlan("Your " + displayName(kind).toLowerCase() + " is recorded as a git commit. Choose the repository that holds "
                + name + (copy ? ", or a folder for a copy." : "."), path, copy ? anchor : null);
        if (plan.isEmpty()) {
            return Optional.empty();
        }
        if (plan.get().placement() == DocumentRepository.Placement.USE) {
            useWorkingTree(plan.get().folder());
            return Optional.of(repoRoot);
        }
        copyInto(anchor, name, plan.get().folder());
        return Optional.empty();
    }

    /**
     * Shows the folder panel, starting at the working tree known or the document library.
     *
     * @param asked  what the panel asks
     * @param path   the file's repository-relative path
     * @param copied the document a folder may take a copy of, or {@code null} when only the repository holding it will do
     * @return the plan for the folder chosen, or empty when the person cancelled
     */
    private Optional<DocumentRepository.Plan> choosePlan(String asked, String path, PublicId copied) {
        return FolderPanel.show(window(), "Choose a Repository", asked, repoRoot != null ? repoRoot : DocumentLibrary.root(), panelPlaces(repoRoot),
                        folder -> plan(folder, path, copied))
                .map(folder -> plan(folder, path, copied));
    }

    /**
     * What choosing a folder would do. A document's identity is its repository folder's name and
     * its path, not where the folder is, so a copy in a same-named repository at the same path
     * would be read as the document itself, its unrelated history over the original's: refused.
     */
    private static DocumentRepository.Plan plan(Path folder, String path, PublicId copied) {
        DocumentRepository.Plan plan = DocumentRepository.plan(folder, path, copied != null);
        if (plan.placement() == DocumentRepository.Placement.COPY || plan.placement() == DocumentRepository.Placement.CREATE) {
            Path root = GitRepo.findRoot(plan.folder()).orElse(plan.folder());
            String relative = root.relativize(plan.folder().resolve(Path.of(path).getFileName())).toString();
            if (GitDocumentMaterializer.documentAnchor(root, relative).equals(copied)) {
                return DocumentRepository.refused(plan.folder(), "Komet would read a copy here as this same document, because the repository's folder name and the "
                        + "file's path match it. Choose a folder with another name.");
            }
        }
        return plan;
    }

    /** Whether the known working tree holds a repository-relative path. */
    private boolean holdsFile(String path) {
        return repoRoot != null && path != null && Files.isRegularFile(repoRoot.resolve(path));
    }

    /**
     * The working tree that holds a stored document's file, when the one known does not: found
     * among the places Komet knows ({@link DocumentHome}), by the identity the store has for the
     * document. Reads the store, so an open calls it off the FX thread.
     *
     * @param anchor the document's anchor
     * @param known  the working tree known, or {@code null}
     * @return the working tree to use in its place; empty when the known one holds the file, or none is found
     */
    private static Optional<Path> home(PublicId anchor, Path known) {
        Optional<String> path = GitDocumentMaterializer.recordedPath(anchor);
        if (path.isEmpty() || (known != null && Files.isRegularFile(known.resolve(path.get())))) {
            return Optional.empty();
        }
        return DocumentHome.find(path.get(), DocumentNarratives.workingTrees(), DocumentLibrary.root(),
                (root, file) -> GitDocumentMaterializer.documentAnchor(root, file).equals(anchor));
    }

    /**
     * The repository that holds a file: the one Komet can find for the member at that path, and
     * otherwise the one the person chooses, told why and refused a folder that does not hold it.
     */
    private Optional<Path> locateWorkingTree(String path) {
        if (path == null) {
            statusLine.setText("The document records no file in a repository; Open file… names one.");
            return Optional.empty();
        }
        Loaded member = members.get(path);
        PublicId anchor = member != null ? member.document().anchor() : path.equals(shownPath) && shown != null ? shown.anchor() : null;
        Optional<Path> home = anchor == null ? Optional.empty() : home(anchor, repoRoot);
        if (home.isPresent()) {
            useWorkingTree(home.get());
            return Optional.of(repoRoot);
        }
        return choosePlan("This is recorded as a git commit. Choose the repository that holds " + path + ".", path, null).map(plan -> {
            useWorkingTree(plan.folder());
            return repoRoot;
        });
    }

    /** Copies the document's committed text into a folder, making its repository when needed, and opens the copy. */
    private void copyInto(PublicId anchor, String name, Path folder) {
        statusLine.setText("Copying " + name + " into " + folder + " …");
        openPlaced(name, folder, " Select the lines again to annotate them.", () -> DocumentRepository.placeCopy(name,
                GitDocumentExporter.export(anchor).orElseThrow(() -> new IOException("the store holds no text for " + name)).text(), folder));
    }

    /**
     * Records the pending edit in git as the person's own commit, with no dialog: writes the
     * document with the edit into the working tree, commits that one file with the message,
     * re-reads the commit into the store and settles the edit. The bar at the bottom of the card
     * calls this with the message typed there; public for automation.
     *
     * @param typed the commit's message, in a line; blank to take the draft written from what the edit changed
     * @param sign  whether to sign the commit
     */
    public void commitPending(String typed, boolean sign) {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        // The member whose edit is pending: the host, or a child edited in place; one commit per member.
        PublicId hostAnchor = shown.anchor();
        PublicId anchor = members.values().stream().filter(Loaded::pending).map(l -> l.document().anchor()).findFirst().orElse(hostAnchor);
        if (DocumentEditor.pending(anchor).isEmpty()) {
            statusLine.setText("Nothing to commit: no edit of this document is pending in Komet.");
            return;
        }
        GitDocumentExporter.Exported pending = GitDocumentExporter.export(anchor, true).orElseThrow();
        GitDocumentExporter.Exported committed = GitDocumentExporter.export(anchor, false).orElseThrow();
        Optional<Path> chosenRoot = workingTree(pending.path());
        if (chosenRoot.isEmpty()) {
            return;
        }
        Path root = chosenRoot.get();
        boolean outside = uncheck(() -> !GitOperations.changedFiles(root, List.of(pending.path())).isEmpty()
                && !Files.readAllLines(root.resolve(pending.path())).equals(pending.lines()));
        if (outside) {
            statusLine.setText(pending.path() + " has changes made outside Komet, which writing your edit would overwrite. Commit records them first.");
            return;
        }
        String text = PendingEditBar.messageOr(typed, DocumentCommit.draftMessage(pending.path(), committed.lines(), pending.lines()));
        statusLine.setText("Writing and committing " + pending.path() + " …");
        pendingBar.setDisable(true);                               // one commit at a time: Enter twice is one commit
        CompletableFuture.supplyAsync(() -> {
            try {
                Path file = GitDocumentExporter.writeTo(pending, root);
                DocumentCommit.Result result = DocumentCommit.write(root, pending.path(), text, sign);
                if (!result.ok()) {
                    return new Committed(false, "Not committed: " + result.output()
                            + " The file in the working tree holds your edit; the edit is still pending in Komet.");
                }
                GitDocumentMaterializer.materialize(root, file, true);
                DocumentCommits.committed(new DocumentCommits.Committed(root, pending.path(), result.sha()));
                return new Committed(true, "Committed " + result.sha().substring(0, 7) + " (" + (sign ? "signed" : "unsigned") + "); "
                        + describe(DocumentEditor.settle(anchor)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((committedNow, failure) -> Platform.runLater(() -> {
            pendingBar.setDisable(false);
            if (failure != null) {
                Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                statusLine.setText("Commit failed: " + cause.getMessage());
                LOG.warn("Committing {} failed", anchor, cause);
                return;
            }
            if (committedNow.ok()) {
                pendingBar.clearMessage();                         // the message went with its commit; one not made keeps it
            }
            open(hostAnchor, committedNow.report(), null);
        }));
    }

    /**
     * What committing the pending edit came to.
     *
     * @param ok     whether git made the commit
     * @param report what to say of it
     */
    private record Committed(boolean ok, String report) { }

    /**
     * What the person chose in a commit dialog.
     *
     * @param message the message
     * @param sign    whether to sign
     */
    private record CommitChoice(String message, boolean sign) { }

    /**
     * Asks for a commit's message and whether to sign it.
     *
     * @param title   the dialog's title
     * @param id      the dialog pane's id
     * @param header  what is being committed
     * @param above   anything to show above the message
     * @param message the message field, holding its draft
     * @param about   what the commit will do
     * @return the choice, or empty when the person cancelled
     */
    private Optional<CommitChoice> askCommit(String title, String id, String header, List<Node> above, TextArea message, String about) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(title);
        confirm.getDialogPane().setId(id);
        confirm.setHeaderText(header);
        Label details = new Label(about);
        details.setWrapText(true);
        VBox content = new VBox(8);
        content.getChildren().addAll(above);
        content.getChildren().addAll(message, details);
        confirm.getDialogPane().setContent(content);
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        for (ButtonType type : List.of(signed, unsigned)) {
            confirm.getDialogPane().lookupButton(type).disableProperty().bind(message.textProperty().isEmpty());
        }
        confirm.initOwner(window());
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        return Optional.of(new CommitChoice(message.getText(), choice.get() == signed));
    }

    /** What settling an edit found, for a status line. */
    static String describe(DocumentEditor.Settlement settlement) {
        if (settlement.rebased()) {
            return "the commit does not touch your edit, which stays pending on top of it.";
        }
        if (!settlement.settled()) {
            return "the commit is not yet in the store, so the edit is still pending.";
        }
        if (settlement.carried()) {
            return "the edit is settled: the commit carries all of it.";
        }
        StringBuilder text = new StringBuilder("the edit is settled against the commit, which differs from it.");
        if (!settlement.notCarried().isEmpty()) {
            text.append(" Not carried: ").append(quoted(settlement.notCarried()))
                    .append(". Restore as pending brings them back over the commit.");
        }
        if (!settlement.committedOnly().isEmpty()) {
            text.append(" Only in the commit: ").append(quoted(settlement.committedOnly())).append('.');
        }
        return text.toString();
    }

    private static String quoted(List<String> lines) {
        String shown = lines.stream().limit(3).map(l -> "“" + (l.length() > 60 ? l.substring(0, 57) + "..." : l) + "”")
                .collect(Collectors.joining(", "));
        return lines.size() + " line(s), " + shown + (lines.size() > 3 ? ", …" : "");
    }

    /**
     * Brings discarded edits back as pending, over the document as now committed. An edit whose
     * block still exists is restored straight away; an edit whose block a commit gave a new
     * identity waits for the person: the one block it could be is highlighted for confirmation, or,
     * when several or none could be, the person picks one. Cancelling leaves the edit restorable.
     */
    private void restorePending() {
        if (shown == null) {
            statusLine.setText("Choose a document first.");
            return;
        }
        PublicId anchor = shown.anchor();
        statusLine.setText("Restoring …");
        record Found(int restored, List<DocumentEditor.Restorable> waiting) { }
        CompletableFuture.supplyAsync(() -> new Found(DocumentEditor.restore(anchor), DocumentEditor.restorable(anchor)))
                .whenComplete((found, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Not restored: " + cause.getMessage());
                        LOG.warn("Restoring the edit of {} failed", anchor, cause);
                        return;
                    }
                    if (found.restored() == 0 && found.waiting().isEmpty()) {
                        open(anchor, "Nothing to restore: no discarded edit of this document differs from what is committed.", null);
                        return;
                    }
                    open(anchor, restoredReport(found.restored()), () -> resolve(anchor, found.waiting(), 0, found.restored()));
                }));
    }

    private static String restoredReport(int restored) {
        return restored == 0 ? "" : "Restored the edit of " + restored + " block(s) as pending over the latest commit; check the"
                + " edited lines against what the commit changed, then choose Commit or Discard… in the bar above.";
    }

    /** Asks the person where each waiting edit goes, one at a time, and restores it there. */
    private void resolve(PublicId anchor, List<DocumentEditor.Restorable> waiting, int at, int restoredSoFar) {
        if (at >= waiting.size()) {
            if (restoredSoFar > 0) {
                statusLine.setText(restoredReport(restoredSoFar));
            }
            return;
        }
        DocumentEditor.Restorable edit = waiting.get(at);
        String editText = edit.lines().stream().filter(DocumentEditor.Kept::edited).map(DocumentEditor.Kept::text)
                .collect(Collectors.joining("\n"));
        Optional<Long> chosen;
        if (edit.match() == DocumentEditor.Match.CONFIRM) {
            surface.highlight(edit.target(), true);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Restore Edit");
            confirm.setHeaderText("A commit rewrote the block this edit was made in. Restore the edit over the highlighted block?");
            TextArea lines = new TextArea(editText);
            lines.setEditable(false);
            lines.setPrefRowCount(Math.min(6, Math.max(2, edit.lines().size())));
            confirm.getDialogPane().setContent(new VBox(6, new Label("The lines you wrote:"), lines));
            ButtonType here = new ButtonType("Restore Here");
            confirm.getButtonTypes().setAll(here, ButtonType.CANCEL);
            confirm.initOwner(window());
            chosen = confirm.showAndWait().filter(b -> b == here).map(b -> edit.target());
            surface.highlight(edit.target(), false);
        } else {
            chosen = choose(edit, editText);
        }
        if (chosen.isEmpty()) {
            statusLine.setText("Kept the edit restorable; Restore as pending offers it again.");
            resolve(anchor, waiting, at + 1, restoredSoFar);
            return;
        }
        long target = chosen.get();
        CompletableFuture.supplyAsync(() -> DocumentEditor.restore(anchor, Map.of(edit.index(), target)))
                .whenComplete((restored, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
                        statusLine.setText("Not restored: " + cause.getMessage());
                        LOG.warn("Restoring an edit of {} over block {} failed", anchor, target, cause);
                        return;
                    }
                    open(anchor, restoredReport(restoredSoFar + restored),
                            () -> resolve(anchor, waiting, at + 1, restoredSoFar + restored));
                }));
    }

    /** Lets the person pick the block an edit goes over, highlighting each as it is chosen; empty when they cancel. */
    private Optional<Long> choose(DocumentEditor.Restorable edit, String editText) {
        Map<String, Long> byLabel = new LinkedHashMap<>();
        for (long candidate : edit.candidates()) {
            StoredBlock block = shown.blocks().get(candidate);
            String first = block == null || block.members().isEmpty() ? "block " + candidate
                    : shown.line(block.members().getFirst()).map(StoredLine::text).orElse("block " + candidate);
            String label = first.length() > 70 ? first.substring(0, 67) + "..." : first;
            while (byLabel.containsKey(label)) {
                label = label + " ";
            }
            byLabel.put(label, candidate);
        }
        ChoiceBox<String> picker = new ChoiceBox<>();
        picker.setId("stampedDocumentRestoreTarget");
        picker.getItems().setAll(byLabel.keySet());
        long[] lit = {0};
        picker.getSelectionModel().selectedItemProperty().addListener((obs, was, is) -> {
            if (lit[0] != 0) {
                surface.highlight(lit[0], false);
            }
            lit[0] = is == null ? 0 : byLabel.get(is);
            if (lit[0] != 0) {
                surface.highlight(lit[0], true);
            }
        });
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.setTitle("Restore Edit");
        ask.setHeaderText(edit.candidates().isEmpty() ? "No block of this kind is left to restore the edit over."
                : "A commit rewrote the block this edit was made in, and more than one block could be it, or none. Pick the block"
                + " to restore the edit over, or cancel to keep it restorable.");
        TextArea lines = new TextArea(editText);
        lines.setEditable(false);
        lines.setPrefRowCount(Math.min(6, Math.max(2, edit.lines().size())));
        ask.getDialogPane().setContent(new VBox(6, new Label("The lines you wrote:"), lines, new Label("Restore over:"), picker));
        ButtonType here = new ButtonType("Restore Here");
        ask.getButtonTypes().setAll(here, ButtonType.CANCEL);
        ask.getDialogPane().lookupButton(here).disableProperty().bind(picker.getSelectionModel().selectedItemProperty().isNull());
        ask.initOwner(window());
        Optional<Long> chosen = ask.showAndWait().filter(b -> b == here).map(b -> byLabel.get(picker.getValue()));
        if (lit[0] != 0) {
            surface.highlight(lit[0], false);
        }
        return chosen;
    }

    /**
     * Discards the pending edit, after the person confirms a question that says how many edited
     * lines will be lost. A discarded edit is kept, so Restore as pending brings it back.
     */
    private void discardPending() {
        if (shown == null) {
            return;
        }
        PublicId anchor = shown.anchor();
        List<PublicId> pending = members.values().stream().filter(Loaded::pending).map(l -> l.document().anchor()).toList();
        if (pending.isEmpty()) {
            statusLine.setText("No edit of this document is pending in Komet.");
            return;
        }
        long lines = editedLines;
        ButtonType discardEdits = new ButtonType("Discard", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType keepEdits = new ButtonType("Keep Edits", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, PendingEditBar.discardConsequence(pending.size()), discardEdits, keepEdits);
        confirm.setTitle("Discard Edits");
        confirm.setHeaderText(PendingEditBar.discardQuestion(lines, pending.size()));
        confirm.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);   // the words wrap; none is cut off
        confirm.getDialogPane().setId("stampedDocumentDiscardDialog");
        // Enter keeps the edits: what cannot be seen again once lost is never the default.
        ((Button) confirm.getDialogPane().lookupButton(discardEdits)).setDefaultButton(false);
        ((Button) confirm.getDialogPane().lookupButton(keepEdits)).setDefaultButton(true);
        confirm.initOwner(window());
        if (confirm.showAndWait().filter(b -> b == discardEdits).isEmpty()) {
            return;
        }
        pendingBar.setDisable(true);
        CompletableFuture.supplyAsync(() -> {
            for (PublicId edited : pending) {
                DocumentEditor.cancel(edited);
            }
            return true;
        }).whenComplete((done, failure) -> Platform.runLater(() -> {
            pendingBar.setDisable(false);
            pendingBar.clearMessage();
            open(anchor, failure != null ? "Could not discard the edit: " + (failure.getCause() != null ? failure.getCause() : failure).getMessage()
                    : PendingEditBar.discarded(lines), null);
        }));
    }

    private Window window() {
        return host.getScene() == null ? null : host.getScene().getWindow();
    }

    @Override
    protected void subCardSave() {
        if (documentArea != null) {
            documentArea.saveSettings();             // the print settings live on the area's own node
        }
    }

    @Override
    public void knowledgeLayoutUnbind() {
        super.knowledgeLayoutUnbind();
        if (documentArea != null) {
            documentArea.knowledgeLayoutUnbind();    // collapses a promoted paged preview, tears the area down
        }
    }

    private void syncPreferences() {
        try {
            preferences().sync();
        } catch (Exception e) {
            LOG.warn("Could not sync the STAMPed Document card preferences", e);
        }
    }

    // ── factories ─────────────────────────────────────────────────────────────

    /** Journal "+" menu entry; {@link java.util.ServiceLoader} instantiates it via the no-arg constructor. */
    public static final class Factory implements KlCardProvider {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Factory() {
        }

        @Override
        public String cardName() {
            return CARD_NAME;
        }

        @Override
        public AbstractHostCard createCard(KometPreferences windowPreferences, UUID journalTopic) {
            KlPreferencesFactory cardPreferencesFactory = KlPreferencesFactory.create(windowPreferences, StampedDocumentCard.class);
            StampedDocumentCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(StampedDocumentCard.class.getSimpleName());
            StampedDocumentCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<StampedDocumentCard> {
        @Override
        public StampedDocumentCard restore(KometPreferences preferences) {
            // before the card reads them: a card saved while it lived in komet-claude-plugin keeps its choices
            RenamedPreferenceKeys.migrateCard(preferences);
            return new StampedDocumentCard(preferences);
        }

        @Override
        public StampedDocumentCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            StampedDocumentCard card = new StampedDocumentCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
