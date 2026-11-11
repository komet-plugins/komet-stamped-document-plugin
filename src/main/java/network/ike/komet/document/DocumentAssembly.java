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
import network.ike.komet.document.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.document.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;
import network.ike.komet.document.GitDocumentMaterializer.StoredReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An assembly as one document on the Stamped Document surface (ike-lab-documents topics
 * {@code dev-stamped-document-surface} and {@code dev-document-surface-pages};
 * IKE-Network/ike-issues#1307): the host document the person opened, and, wherever one of its
 * lines is an {@code include::} directive naming a file the store holds, that file's document
 * as a flowed child — its own lines, STAMPs, annotations and commit summaries, stacked in the
 * host's include order. A child that includes in turn is followed the same way.
 *
 * <p>A file may be shown more than once, and an include may show part of a file (topic
 * {@code dev-document-front-matter}): {@code include::summary.adoc[tag=brief]} shows the lines
 * between the file's {@code tag::brief[]} and {@code end::brief[]} lines ({@link IncludeTags}),
 * and a second include of the same file shows it again. Each showing is a {@link Member} of its
 * own, and every showing of a file holds the same stored lines: one nid and one history for a
 * line, however many places it is read in. Nothing is copied. Only a file that would be shown
 * inside itself is refused, since that include would never end.
 *
 * <p>Every line keeps the document it belongs to: a selection is located to one member and
 * that member's own line numbers, which is what an annotation trailer names. The surface order
 * — the host's lines with each resolved include line followed by its child's lines — is the
 * order both views stack and the ordinals a {@link LineSelection} runs over. A line shown twice
 * has an ordinal in each showing, so a place on the surface is a member and a line:
 * {@link #ordinal(Member, long)}.
 *
 * <p>The lines are walked once in that order for their attributes ({@link DocumentAttributes}):
 * an attribute entry holds from its line on, through every include after it, and a conditional
 * directive, {@code ifdef} to its {@code endif}, says whether the lines between are read. Every
 * line stays a line of the assembly, since each is source a person may come to change; what a
 * member's lines read as, with the lines a conditional leaves out gone and the directives with
 * them, is {@link #read(Member)}, which is what the Reading view, the pages and the PDF show. An
 * include that a conditional leaves out is not followed.
 *
 * <p>Resolution follows the materializer ({@link IncludeResolver}): the text's own attribute
 * entries, the AsciiDoc roots the caller lists, then the including file's directory, against
 * the paths the store records. The assembly reads nothing itself; it is a pure function of the host, the
 * host's recorded path and a lookup of stored documents by repository-relative path.
 */
public final class DocumentAssembly {

    /** A root for resolving paths that are all repository-relative: nothing is read from it. */
    private static final Path RESOLUTION_ROOT = Path.of("/").toAbsolutePath();

    /**
     * One showing of a document on the surface: the host, or a child in include order. A file
     * included twice has two members. They are told apart by where they stand, so a member equals
     * itself alone.
     *
     * @param document   the document as the store holds it, whole: its lines are the file's, which is what a line
     *                   number counts through
     * @param path       its repository-relative path, forward slashes
     * @param depth      how many includes deep it sits ({@code 0} for the host)
     * @param includedBy the member whose include line brought it in, {@code null} for the host
     * @param shown      what this showing reads of the document: the document itself, or, for an include that
     *                   names tags, the same lines and blocks cut down to the tagged regions
     * @param tags       the include's tag attribute as written, {@code tag=brief}; empty for a showing of the whole file
     */
    public record Member(StoredDocument document, String path, int depth, Member includedBy, StoredDocument shown, String tags) {

        /** A showing of the whole file. */
        public Member(StoredDocument document, String path, int depth, Member includedBy) {
            this(document, path, depth, includedBy, document, "");
        }

        /** @return whether this is the host, the document the person opened */
        public boolean host() {
            return includedBy == null;
        }

        /** @return whether the showing reads the whole file, rather than its tagged regions */
        public boolean whole() {
            return shown == document;
        }

        /** @return whether the showing reads every line of a block: a block cut by a region's edge is read, never edited, there */
        public boolean holds(StoredBlock block) {
            if (whole() || block == null) {
                return true;
            }
            StoredBlock stored = document.blocks().get(block.nid());
            return stored != null && lineCount(document, stored) == lineCount(shown, block);
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        /** @return the file name of the path */
        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /**
     * One include directive and where it led.
     *
     * @param in     the member whose line it is
     * @param line   the directive's line
     * @param target the target as written
     * @param child  the child it shows in place, or {@code null} when it stays a line
     * @param note   why it stays a line: not in the store, a file inside itself, no line under its tags; for an
     *               include that resolved, the tags it names that its file does not have, else empty
     */
    public record Include(Member in, StoredLine line, String target, Member child, String note) {

        /** @return whether the include shows a child in place */
        public boolean resolved() {
            return child != null;
        }
    }

    /**
     * A selection located to one member: the member and the selection over that member's own
     * lines, from which the file's line numbers follow.
     *
     * @param member the member every selected line belongs to
     * @param local  the selection as ordinals into the member's lines
     */
    public record Located(Member member, LineSelection local) { }

    /**
     * A part of a block's lines as the Reading view shows them: a run of the block's own lines,
     * or a resolved include whose child's blocks stand where its line was.
     *
     * @param lines   the block's lines of this part, in order; empty for an include part
     * @param include the include shown in place of its line, or {@code null} for a text part
     */
    public record Part(List<StoredLine> lines, Include include) {

        /** @return whether the part stands for a child document */
        public boolean included() {
            return include != null;
        }
    }

    /** One line in surface order. */
    private record Entry(StoredLine line, Member member, int indexInMember) { }

    private final Member host;
    private final List<Member> members = new ArrayList<>();
    private final List<Include> includes = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<StoredLine> lines = new ArrayList<>();
    /** A line's first ordinal: where it is first shown. */
    private final Map<Long, Integer> ordinals = new HashMap<>();
    /** A line's ordinal in each showing. */
    private final Map<Member, Map<Long, Integer>> placed = new IdentityHashMap<>();
    private final Map<Long, StoredLine> linesByNid = new HashMap<>();
    /** The include on a line, where the line is first shown. */
    private final Map<Long, Include> includesByLine = new HashMap<>();
    /** The include on a line in each showing: a file shown twice follows its own includes twice. */
    private final Map<Member, Map<Long, Include>> includesByPlace = new IdentityHashMap<>();
    private final Map<Long, List<StoredReference>> references = new HashMap<>();
    /** The attributes carried through the lines in surface order. */
    private final DocumentAttributes.Walk attributes = new DocumentAttributes.Walk();
    /** The attributes each line of a showing was read in. */
    private final Map<Member, Map<Long, DocumentAttributes>> attributesByPlace = new IdentityHashMap<>();
    /** What each showing reads as: its document without the lines a conditional leaves out. */
    private final Map<Member, StoredDocument> read = new IdentityHashMap<>();
    /** What a cross reference can find, gathered when first asked for. */
    private DocumentTargets targets;
    /** How each showing's lines are read, made when first asked for. */
    private final Map<Member, DocumentReading> readings = new IdentityHashMap<>();
    private final Function<String, Optional<StoredDocument>> store;
    private final Function<String, List<Path>> underRoots;
    /** The attribute names a reader set, entered before the host's first line. */
    private final List<String> set;
    private final Map<String, Optional<StoredDocument>> looked = new HashMap<>();

    private DocumentAssembly(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store,
                             Function<String, List<Path>> underRoots) {
        this(host, hostPath, store, underRoots, List.of());
    }

    /** An assembly read with attributes a reader set: each is entered before the host's first line, as an attribute given to AsciiDoc on its command line is. */
    private DocumentAssembly(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store,
                             Function<String, List<Path>> underRoots, List<String> set) {
        this.store = store;
        this.underRoots = underRoots;
        this.set = List.copyOf(set);
        for (String name : this.set) {
            attributes.line(":" + name + ":");
        }
        this.host = new Member(host, hostPath == null ? "" : hostPath, 0, null);
        flatten(this.host);
    }

    /**
     * The assembly of a host document: its includes resolved against the store, each resolved
     * child flowed in place, children's includes followed in turn.
     *
     * @param host     the host document
     * @param hostPath the host's repository-relative path, or {@code null} when none is recorded (then nothing resolves)
     * @param store    a lookup of the store's documents by repository-relative path (forward slashes)
     * @return the assembly, with nothing resolved under the AsciiDoc roots since no listing is given
     */
    public static DocumentAssembly of(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store) {
        return new DocumentAssembly(host, hostPath, store, rest -> List.of());
    }

    /**
     * The assembly of a host document whose includes may name files under the AsciiDoc roots
     * (topic {@code dev-adoc-packages}): {@code {adoc}/network.ike.lab/dev/overview.adoc}, or
     * {@code {topics}/topics/dev/overview.adoc} in the layout before packages, is a path under
     * some library's {@code src/docs/asciidoc}, in this working tree or one beside it.
     *
     * @param host       the host document
     * @param hostPath   the host's repository-relative path, or {@code null} when none is recorded (then nothing resolves)
     * @param store      a lookup of the store's documents by repository-relative path (forward slashes)
     * @param underRoots the recorded paths whose tail is {@code src/docs/asciidoc} and the path given,
     *                   {@link IncludeResolver#endingWith} over the paths the caller knows, nearest tree first
     * @return the assembly
     */
    public static DocumentAssembly of(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store,
                                      Function<String, List<Path>> underRoots) {
        return new DocumentAssembly(host, hostPath, store, underRoots);
    }

    /**
     * The assembly of a host document read with attributes a reader set, so the document reads as
     * another document that includes the same files reads them: {@code compendium} set, a topic
     * shows the passage it keeps for the compendium. Each name is set before the host's first
     * line; a document that unsets or sets it again has the last word from that line on.
     *
     * @param host       the host document
     * @param hostPath   the host's repository-relative path, or {@code null} when none is recorded (then nothing resolves)
     * @param store      a lookup of the store's documents by repository-relative path (forward slashes)
     * @param underRoots the recorded paths under the AsciiDoc roots, as {@link #of(StoredDocument, String, Function, Function)} takes them
     * @param set        the attribute names set, each with no value
     * @return the assembly
     */
    public static DocumentAssembly of(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store,
                                      Function<String, List<Path>> underRoots, List<String> set) {
        return new DocumentAssembly(host, hostPath, store, underRoots, set);
    }

    /** @return the attribute names a reader set for this assembly, none when it is read as written */
    public List<String> set() {
        return set;
    }

    /**
     * A document alone: its include lines stay lines.
     *
     * @param document the document
     * @param path     its recorded path, or {@code null}
     * @return the assembly of one member
     */
    public static DocumentAssembly single(StoredDocument document, String path) {
        return new DocumentAssembly(document, path, p -> Optional.empty(), rest -> List.of());
    }

    /** Appends the lines a member shows in surface order, each resolved include line followed by its child's. */
    private void flatten(Member member) {
        members.add(member);
        StoredDocument document = member.document();
        references.putAll(document.references());
        Set<Long> region = null;
        if (!member.whole()) {
            region = new HashSet<>();
            for (StoredLine line : member.shown().lines()) {
                region.add(line.nid());
            }
        }
        Map<Long, Integer> places = new HashMap<>();
        placed.put(member, places);
        Map<Long, Include> followed = new HashMap<>();
        includesByPlace.put(member, followed);
        Map<Integer, IncludeResolver.Resolution> byLine = new HashMap<>();
        if (!member.path().isEmpty()) {
            List<String> texts = document.lines().stream().map(StoredLine::text).toList();
            if (!IncludeResolver.includes(texts).isEmpty()) {
                for (IncludeResolver.Resolution r : IncludeResolver.resolve(RESOLUTION_ROOT, Path.of(member.path()), texts, this::stored, underRoots)) {
                    byLine.put(r.line(), r);
                }
            }
        }
        Map<Long, DocumentAttributes> states = new HashMap<>();
        attributesByPlace.put(member, states);
        Set<Long> kept = new HashSet<>();
        Set<Long> prose = new HashSet<>();                        // the conditionals that hold their text, and are read
        boolean cut = false;
        for (int i = 0; i < document.lines().size(); i++) {
            StoredLine line = document.lines().get(i);
            if (region != null && !region.contains(line.nid())) {
                continue;                                         // outside the tagged regions this showing reads
            }
            entries.add(new Entry(line, member, i));
            lines.add(line);
            ordinals.putIfAbsent(line.nid(), lines.size() - 1);
            places.put(line.nid(), lines.size() - 1);
            linesByNid.putIfAbsent(line.nid(), line);
            boolean reads = attributes.line(line.text());
            states.put(line.nid(), attributes.state());
            if (!reads) {
                cut = true;                                       // a directive, or a line its conditional leaves out
                continue;
            }
            kept.add(line.nid());
            if (DocumentAttributes.holdsText(line.text())) {
                prose.add(line.nid());
            }
            IncludeResolver.Resolution resolution = byLine.get(i + 1);
            if (resolution == null) {
                continue;
            }
            Include include = follow(member, line, resolution);
            includes.add(include);
            includesByLine.putIfAbsent(line.nid(), include);
            followed.put(line.nid(), include);
            if (include.resolved()) {
                flatten(include.child());
            }
        }
        StoredDocument reads = cut ? project(document, kept) : member.shown();
        read.put(member, prose.isEmpty() ? reads : withProse(reads, prose));
    }

    /**
     * A document in which a conditional that holds its text, and is read, is a paragraph. The
     * parser stores a directive on a line of its own as a block that prints nothing, as an
     * include line or an attribute entry is; one whose condition is met is its text, which the
     * views draw and print as any paragraph's ({@link DocumentAttributes.Reading#line}).
     */
    private static StoredDocument withProse(StoredDocument document, Set<Long> prose) {
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>(document.blocks());
        boolean any = false;
        for (StoredBlock block : document.blocks().values()) {
            if (block.kind() == Block.Kind.OPAQUE && !block.members().isEmpty() && prose.containsAll(block.members())) {
                blocks.put(block.nid(), new StoredBlock(block.nid(), Block.Kind.PARAGRAPH, block.level(), block.title(), block.parameters(),
                        block.members(), block.stamp(), block.tree()));
                any = true;
            }
        }
        if (!any) {
            return document;                                      // each is a line of a paragraph already
        }
        StoredBlock root = document.root() == null ? null : blocks.get(document.root().nid());
        return new StoredDocument(document.anchor(), document.commit(), document.lines(), document.manifestVersions(), document.attributes(),
                root, blocks, document.references(), document.sentences());
    }

    /** Where one include leads: a child flowed in place, or a line with a note. */
    private Include follow(Member member, StoredLine line, IncludeResolver.Resolution resolution) {
        if (resolution.resolved().isEmpty()) {
            // The resolver names what it tried against the store; that is the note, not the attribute arithmetic behind it.
            String reason = resolution.reason();
            int tried = reason.indexOf("tried ");
            return new Include(member, line, resolution.target(), null,
                    tried >= 0 ? "not in the store (" + reason.substring(tried) + ")" : "not resolved: " + reason);
        }
        String path = resolution.resolved().get().toString().replace('\\', '/');
        for (Member within = member; within != null; within = within.includedBy()) {
            if (within.path().equals(path)) {
                // the one include that cannot be followed: it would show the file inside itself, without end
                return new Include(member, line, resolution.target(), null, "a cycle: " + path + " is already being shown here, and would include itself");
            }
        }
        Optional<StoredDocument> child = stored(path);
        if (child.isEmpty()) {
            return new Include(member, line, resolution.target(), null, "not in the store: " + path);
        }
        Optional<IncludeTags> tags = IncludeTags.of(resolution.reference().text());
        if (tags.isEmpty()) {
            return new Include(member, line, resolution.target(), new Member(child.get(), path, member.depth() + 1, member), "");
        }
        // a region is a run of the child's stored lines: the same lines the whole file shows, and no copy of them
        IncludeTags.Region region = tags.get().select(child.get().lines().stream().map(StoredLine::text).toList());
        String missing = region.missing().isEmpty() ? "" : "no tag " + String.join(", ", region.missing()) + " in " + path;
        if (region.shown().isEmpty()) {
            return new Include(member, line, resolution.target(), null,
                    missing.isEmpty() ? "no line of " + path + " is under " + tags.get().written() : missing);
        }
        Set<Long> kept = new HashSet<>();
        for (int index : region.shown()) {
            kept.add(child.get().lines().get(index).nid());
        }
        return new Include(member, line, resolution.target(),
                new Member(child.get(), path, member.depth() + 1, member, project(child.get(), kept), tags.get().written()), missing);
    }

    /**
     * A document cut down to some of its lines: the same lines, and the blocks that hold any of
     * them, each with the members it keeps. A section whose heading is left out is no section
     * there: what is kept of it stands where the section stood, as the lines would read to
     * AsciiDoc in the including document. A document whose title line is left out has no title.
     */
    static StoredDocument project(StoredDocument document, Set<Long> kept) {
        List<StoredLine> lines = document.lines().stream().filter(line -> kept.contains(line.nid())).toList();
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        StoredBlock root = null;
        if (document.root() != null) {
            Held held = projectMembers(document, document.root(), kept, blocks);
            boolean titled = heads(document, document.root(), kept);
            root = new StoredBlock(document.root().nid(), document.root().kind(), document.root().level(), titled ? document.root().title() : "",
                    titled ? document.root().parameters() : "", held.members(), document.root().stamp(), held.tree());
            blocks.put(root.nid(), root);
        }
        Map<Long, List<StoredReference>> references = new HashMap<>();
        document.references().forEach((line, found) -> {
            if (kept.contains(line)) {
                references.put(line, found);
            }
        });
        Map<Long, List<GitDocumentMaterializer.StoredSentence>> sentences = new HashMap<>();
        document.sentences().forEach((block, found) -> {
            // a block kept whole keeps its sentences; a block cut by the region's edge is read from its lines
            if (blocks.containsKey(block) && lineCount(document, document.blocks().get(block)) == lineCount(blocks, blocks.get(block))) {
                sentences.put(block, found);
            }
        });
        boolean titled = root != null && !root.title().isEmpty();
        return new StoredDocument(document.anchor(), document.commit(), lines, document.manifestVersions(),
                titled ? document.attributes() : List.of(), root, blocks, references, sentences);
    }

    /**
     * What a block keeps, in the two orders a stored block has: its members, and its tree, which in a
     * document stored as vertices also holds the blank lines between its blocks.
     */
    private record Held(List<Long> members, List<Long> tree) { }

    /** What a block keeps: its kept lines, and its child blocks that keep any; a section without its heading gives up what it keeps to its parent. */
    private static Held projectMembers(StoredDocument document, StoredBlock block, Set<Long> kept, Map<Long, StoredBlock> blocks) {
        Map<Long, Held> children = new HashMap<>();
        List<Long> members = projectList(document, block.members(), kept, blocks, children, false);
        List<Long> tree = block.tree().equals(block.members()) ? members : projectList(document, block.tree(), kept, blocks, children, true);
        return new Held(members, tree);
    }

    /** One of a block's two lists cut to the lines kept; a child block is projected once and read from {@code children} the second time. */
    private static List<Long> projectList(StoredDocument document, List<Long> listed, Set<Long> kept, Map<Long, StoredBlock> blocks,
                                          Map<Long, Held> children, boolean tree) {
        List<Long> out = new ArrayList<>();
        for (long nid : listed) {
            if (kept.contains(nid)) {
                out.add(nid);
                continue;
            }
            StoredBlock child = document.blocks().get(nid);
            if (child == null) {
                continue;                                         // a line the region leaves out
            }
            Held held = children.get(nid);
            if (held == null) {
                held = projectMembers(document, child, kept, blocks);
                children.put(nid, held);
                if (!held.members().isEmpty() && !(child.kind() == Block.Kind.SECTION && !heads(document, child, kept))) {
                    blocks.put(child.nid(), new StoredBlock(child.nid(), child.kind(), child.level(), child.title(), child.parameters(),
                            held.members(), child.stamp(), held.tree()));
                }
            }
            if (held.members().isEmpty()) {
                continue;
            }
            if (!blocks.containsKey(child.nid())) {
                out.addAll(tree ? held.tree() : held.members());   // a section without its heading: what it keeps stands where it stood
                continue;
            }
            out.add(child.nid());
        }
        return out;
    }

    /** Whether a section's or a document's heading line is among the lines kept. */
    private static boolean heads(StoredDocument document, StoredBlock block, Set<Long> kept) {
        for (long nid : block.members()) {
            if (kept.contains(nid) && document.line(nid).map(line -> line.text().startsWith("=")).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** How many lines a block holds, its child blocks' among them. */
    private static int lineCount(StoredDocument document, StoredBlock block) {
        return lineCount(document.blocks(), block);
    }

    private static int lineCount(Map<Long, StoredBlock> blocks, StoredBlock block) {
        int count = 0;
        for (long nid : block.members()) {
            StoredBlock child = blocks.get(nid);
            count += child == null ? 1 : lineCount(blocks, child);
        }
        return count;
    }

    private boolean stored(Path path) {
        return stored(path.toString().replace('\\', '/')).isPresent();
    }

    private Optional<StoredDocument> stored(String path) {
        return looked.computeIfAbsent(path, store);
    }

    // ── what the surface reads ────────────────────────────────────────────────

    /** @return the host, the document the person opened */
    public Member host() {
        return host;
    }

    /** @return every member in surface order, the host first */
    public List<Member> members() {
        return List.copyOf(members);
    }

    /** @return the members shown in place of an include line, in include order */
    public List<Member> children() {
        return members.stream().filter(m -> !m.host()).toList();
    }

    /**
     * What a showing reads as: the lines it shows without the conditional directives among them
     * and without the lines a conditional leaves out, and the blocks that still hold a line. It
     * is the showing's own document, {@link Member#shown}, when no conditional cuts it.
     *
     * @param member the showing
     * @return the document the Reading view, the pages and the PDF read
     */
    public StoredDocument read(Member member) {
        return read.getOrDefault(member, member.shown());
    }

    /**
     * Whether a showing reads every line of a block: a block cut by a tagged region's edge or by
     * a conditional is read there, and edited where its lines are all shown.
     *
     * @param member the showing
     * @param block  the block, as {@link #read(Member)} holds it
     * @return whether the block stands whole
     */
    public boolean holds(Member member, StoredBlock block) {
        if (block == null) {
            return true;
        }
        StoredDocument reads = read(member);
        StoredBlock stored = member.document().blocks().get(block.nid());
        return reads == member.document() || stored != null && lineCount(member.document(), stored) == lineCount(reads, block);
    }

    /**
     * What a cross reference of the assembly can point at: every anchor its showings read, with
     * the block it names.
     *
     * @return the targets
     */
    public DocumentTargets targets() {
        if (targets == null) {
            targets = DocumentTargets.of(this);
        }
        return targets;
    }

    /**
     * How the lines of a showing are read before their inline markup: each line's attribute
     * references as the values in force at it, and its cross references by what they find.
     *
     * @param member the showing
     * @return the reading
     */
    public DocumentReading reading(Member member) {
        return readings.computeIfAbsent(member, m -> new DocumentReading(nid -> attributes(m, nid), targets()));
    }

    /**
     * The attributes a line of a showing is read in: what {@code {name}} on it stands for.
     *
     * @param member the showing
     * @param nid    the line
     * @return the attributes in force there; those every document starts with for a line the showing does not hold
     */
    public DocumentAttributes attributes(Member member, long nid) {
        Map<Long, DocumentAttributes> states = attributesByPlace.get(member);
        DocumentAttributes at = states == null ? null : states.get(nid);
        return at == null ? DocumentAttributes.intrinsic() : at;
    }

    /** @return the attributes a line is read in where it is first shown */
    public DocumentAttributes attributes(long nid) {
        return memberOf(nid).map(member -> attributes(member, nid)).orElse(DocumentAttributes.intrinsic());
    }

    /**
     * The attributes a block is read in: those of its first line.
     *
     * @param member the showing
     * @param block  the block
     * @return the attributes in force where the block begins
     */
    public DocumentAttributes attributes(Member member, StoredBlock block) {
        StoredDocument document = member.document();
        StoredBlock at = block;
        while (at != null && !at.members().isEmpty()) {
            long first = at.members().getFirst();
            if (document.blocks().get(first) == null) {
                return attributes(member, first);
            }
            at = document.blocks().get(first);
        }
        return DocumentAttributes.intrinsic();
    }

    /** @return every include directive met, in surface order, resolved or not */
    public List<Include> includes() {
        return List.copyOf(includes);
    }

    /** @return the includes that stayed lines, with their notes */
    public List<Include> unresolved() {
        return includes.stream().filter(i -> !i.resolved()).toList();
    }

    /** @return every line in surface order; a line shown twice is here twice */
    public List<StoredLine> lines() {
        return List.copyOf(lines);
    }

    /** @return how many stored lines the assembly shows: a line shown twice is one line */
    public int storedLines() {
        return ordinals.size();
    }

    /** @return the ordinal of a line in surface order where it is first shown, or {@code -1} */
    public int ordinal(long nid) {
        return ordinals.getOrDefault(nid, -1);
    }

    /**
     * The ordinal of a line in one showing: the place a reader points at when the line is shown
     * more than once.
     *
     * @param member the showing
     * @param nid    the line
     * @return the ordinal in surface order, or {@code -1} when that showing does not read the line
     */
    public int ordinal(Member member, long nid) {
        Map<Long, Integer> places = placed.get(member);
        return places == null ? -1 : places.getOrDefault(nid, -1);
    }

    /** @return every ordinal a line stands at, in surface order: one for each showing that reads it */
    public List<Integer> ordinals(long nid) {
        List<Integer> all = new ArrayList<>();
        for (Member member : members) {
            int ordinal = ordinal(member, nid);
            if (ordinal >= 0) {
                all.add(ordinal);
            }
        }
        return all;
    }

    /** @return the showings of a file, in surface order */
    public List<Member> showings(String path) {
        return members.stream().filter(m -> m.path().equals(path)).toList();
    }

    /** @return the line with that nid, if any member holds it */
    public Optional<StoredLine> line(long nid) {
        return Optional.ofNullable(linesByNid.get(nid));
    }

    /** @return the member the line with that ordinal belongs to */
    public Optional<Member> memberAt(int ordinal) {
        return ordinal < 0 || ordinal >= entries.size() ? Optional.empty() : Optional.of(entries.get(ordinal).member());
    }

    /** @return the member that first shows the line with that nid */
    public Optional<Member> memberOf(long nid) {
        return memberAt(ordinal(nid));
    }

    /** @return the index of the line with that ordinal within its member's lines, or {@code -1} */
    public int indexInMember(int ordinal) {
        return ordinal < 0 || ordinal >= entries.size() ? -1 : entries.get(ordinal).indexInMember();
    }

    /** @return the include directive on that line, if the line is one, as it was followed where the line is first shown */
    public Optional<Include> includeOf(long lineNid) {
        return Optional.ofNullable(includesByLine.get(lineNid));
    }

    /**
     * The include directive on a line of one showing: a file shown twice follows its own includes
     * once for each showing, and each has its own children.
     *
     * @param member  the showing the line is read in
     * @param lineNid the line
     * @return the include as that showing followed it, if the line is one
     */
    public Optional<Include> includeOf(Member member, long lineNid) {
        Map<Long, Include> followed = includesByPlace.get(member);
        return followed == null ? Optional.empty() : Optional.ofNullable(followed.get(lineNid));
    }

    /** @return the active references of every member, by the nid of the line they occur on */
    public Map<Long, List<StoredReference>> references() {
        return references;
    }

    /**
     * Locates a selection to the one member every selected line belongs to.
     *
     * @param selection the selection in surface ordinals
     * @return the member and the selection over its own lines, or empty when the selection is
     *         out of range or spans more than one member
     */
    public Optional<Located> locate(LineSelection selection) {
        if (selection == null || selection.min() < 0 || selection.max() >= entries.size()) {
            return Optional.empty();
        }
        Member member = entries.get(selection.min()).member();
        for (int o = selection.min(); o <= selection.max(); o++) {
            if (entries.get(o).member() != member) {
                return Optional.empty();
            }
        }
        return Optional.of(new Located(member,
                new LineSelection(entries.get(selection.anchor()).indexInMember(), entries.get(selection.caret()).indexInMember())));
    }

    /** @return how many members a selection touches */
    public int membersTouched(LineSelection selection) {
        if (selection == null) {
            return 0;
        }
        Set<Member> touched = new LinkedHashSet<>();
        for (int o = Math.max(0, selection.min()); o <= Math.min(entries.size() - 1, selection.max()); o++) {
            touched.add(entries.get(o).member());
        }
        return touched.size();
    }

    /**
     * The ordinal, in surface order, of a member's line by its position in that member.
     *
     * @param member the member
     * @param index  the index into the member's lines
     * @return the ordinal, or {@code -1}
     */
    public int ordinalIn(Member member, int index) {
        return index < 0 || index >= member.document().lines().size() ? -1 : ordinal(member, member.document().lines().get(index).nid());
    }

    /**
     * A block's lines split for the Reading view: runs of the block's own lines, and, where a
     * line is an include shown in place, a part standing for the child. An include that stayed a
     * line stays in its run; the view notes it there.
     *
     * @param blockLines the block's lines in order
     * @return the parts in order; one text part when nothing is shown in place
     */
    public List<Part> parts(List<StoredLine> blockLines) {
        return parts(null, blockLines);
    }

    /**
     * A block's lines split for the Reading view, in one showing.
     *
     * @param member     the showing the block is read in, or {@code null} for where its lines are first shown
     * @param blockLines the block's lines in order
     * @return the parts in order; one text part when nothing is shown in place
     */
    public List<Part> parts(Member member, List<StoredLine> blockLines) {
        List<Part> parts = new ArrayList<>();
        List<StoredLine> run = new ArrayList<>();
        for (StoredLine line : blockLines) {
            Include include = member == null ? includesByLine.get(line.nid()) : includeOf(member, line.nid()).orElse(null);
            if (include != null && include.resolved()) {
                if (!run.isEmpty()) {
                    parts.add(new Part(List.copyOf(run), null));
                    run.clear();
                }
                parts.add(new Part(List.of(), include));
            } else {
                run.add(line);
            }
        }
        if (!run.isEmpty() || parts.isEmpty()) {
            parts.add(new Part(List.copyOf(run), null));
        }
        return parts;
    }

    /** @return the members by path, in surface order; a file shown twice is here as its first showing */
    public Map<String, Member> byPath() {
        Map<String, Member> byPath = new LinkedHashMap<>();
        for (Member m : members) {
            byPath.putIfAbsent(m.path(), m);
        }
        return byPath;
    }
}
