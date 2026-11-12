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
import network.ike.komet.document.print.InlineText;
import network.ike.komet.document.print.StoredDocumentModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a cross reference of an assembly can point at (IKE-Network/ike-issues#1351): every
 * anchor the documents write, with the block it names and the words it is known by. An anchor
 * on a line of its own, {@code [[rda-approval-tree]]} over a title or a table, names that block.
 * An anchor in a line names the block the line is in; the one that opens a bibliography entry,
 * {@code [[[hhsvalues2026,HHS 2026, Standard Values]]]}, names the entry. An id is found where
 * it is first read, so a file shown twice is pointed at where it is first shown, and an anchor
 * a conditional leaves out is no target.
 *
 * <p>A reference is read by what it finds ({@link #read}). One that finds its target becomes a
 * link to the target's block, {@code link:#b1234[text]}, which every view draws as a link and
 * follows: the PDF by the block's label, the pages and the Reading view by the block. A bare
 * reference, {@code <<hhsvalues2026>>}, takes the target's words: a section's title, and for a
 * bibliography entry its label in brackets, {@code [HHS 2026, Standard Values]}, as AsciiDoc
 * prints a citation. The entry itself begins with the same label, so a reader who has the page
 * in hand can match the two. A reference that finds nothing is left as written, and reads as
 * it did: its text, or its target's words.
 */
public final class DocumentTargets {

    /** An anchor on a line of its own: {@code [[id]]} or {@code [[id,reference text]]}. */
    private static final Pattern BLOCK_ANCHOR = Pattern.compile("^\\[\\[([\\w:.-]+)(?:,\\s*([^\\]]*))?\\]\\]$");
    /** A bibliography entry's anchor: {@code [[[id]]]} or {@code [[[id,label]]]}; the label may hold a comma. */
    private static final Pattern ENTRY_ANCHOR = Pattern.compile("\\[\\[\\[([\\w:.-]+)(?:,\\s*([^\\]]*))?\\]\\]\\]");
    /** An anchor in a line. */
    private static final Pattern INLINE_ANCHOR = Pattern.compile("(?<!\\[)\\[\\[([\\w:.-]+)(?:,\\s*([^\\]]*))?\\]\\](?!\\])");
    /** A block attribute line: {@code [#id]}, {@code [source#id]}, {@code [id=name]}. */
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern SHORTHAND_ID = Pattern.compile("^[^,#]*#([\\w:-]+)");
    private static final Pattern NAMED_ID = Pattern.compile("(?:^|,)\\s*id\\s*=\\s*\"?([\\w:.-]+)");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|\\+{4,}|/{4,})$");

    /** {@code <<id>>} and {@code <<id,text>>}, not escaped. */
    private static final Pattern SHORT_REFERENCE = Pattern.compile("(?<!\\\\)<<([^<>,\\s][^<>,]*?)(?:,\\s*([^>]*?))?>>");
    /** The opening of {@code xref:id[text]}: a reference's text may run on to the next line, its opening does not. */
    private static final Pattern MACRO_REFERENCE = Pattern.compile("(?<![\\w\\\\])xref:([^\\[\\s]+)\\[");

    /**
     * One thing a reference can point at.
     *
     * @param id     the anchor's id
     * @param member the showing it is first read in
     * @param block  the nid of the block it names
     * @param label  the words a bare reference to it reads as
     */
    public record Target(String id, DocumentAssembly.Member member, long block, String label) {

        /** @return the block's key, which a link's target is written with */
        public String key() {
            return StoredDocumentModel.key(block);
        }
    }

    private static final DocumentTargets NONE = new DocumentTargets(Map.of());

    private final Map<String, Target> byId;
    private final Map<String, Long> blocksByKey = new HashMap<>();

    private DocumentTargets(Map<String, Target> byId) {
        this.byId = byId;
        for (Target target : byId.values()) {
            blocksByKey.putIfAbsent(target.key(), target.block());
        }
    }

    /**
     * What the references of a document read as, for comparing two readings of it: each target's
     * id, the block it names and its label, in the order of their ids. Two assemblies with the
     * same list read every cross reference the same way.
     *
     * @return the targets as text
     */
    public List<String> drawn() {
        return byId.values().stream().map(target -> target.id() + '|' + target.block() + '|' + target.label()).sorted().toList();
    }

    /** @return no target at all: every reference stays as written */
    public static DocumentTargets none() {
        return NONE;
    }

    /**
     * The targets of an assembly, from what each of its showings reads.
     *
     * @param assembly the assembly
     * @return the targets
     */
    public static DocumentTargets of(DocumentAssembly assembly) {
        Map<String, Target> found = new HashMap<>();
        for (DocumentAssembly.Member member : assembly.members()) {
            StoredDocument document = assembly.read(member);
            if (document.root() != null) {
                block(assembly, member, document, document.root(), found);
            }
        }
        return found.isEmpty() ? NONE : new DocumentTargets(found);
    }

    private static void block(DocumentAssembly assembly, DocumentAssembly.Member member, StoredDocument document, StoredBlock block,
                              Map<String, Target> found) {
        boolean verbatim = block.kind() == Block.Kind.LISTING || block.kind() == Block.Kind.LITERAL || block.kind() == Block.Kind.DIAGRAM;
        boolean inside = false;
        for (long nid : block.members()) {
            Optional<StoredLine> line = document.line(nid);
            if (line.isEmpty()) {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    block(assembly, member, document, child, found);
                }
                continue;
            }
            String text = line.get().text().strip();
            if (verbatim && (inside || DELIMITER.matcher(text).matches())) {
                inside = true;                                    // a listing's lines are its own text: an anchor written there names nothing
                continue;
            }
            Matcher own = BLOCK_ANCHOR.matcher(text);
            if (own.matches()) {
                found.putIfAbsent(own.group(1), new Target(own.group(1), member, block.nid(), label(assembly, member, block, own.group(1), own.group(2))));
                continue;
            }
            Matcher attributes = ATTRIBUTE_LINE.matcher(text);
            if (attributes.matches()) {
                for (Pattern named : new Pattern[] {SHORTHAND_ID, NAMED_ID}) {
                    Matcher id = named.matcher(attributes.group(1));
                    if (id.find()) {
                        found.putIfAbsent(id.group(1), new Target(id.group(1), member, block.nid(), label(assembly, member, block, id.group(1), null)));
                    }
                }
                continue;
            }
            Matcher entry = ENTRY_ANCHOR.matcher(text);
            while (entry.find()) {
                String label = entry.group(2) == null || entry.group(2).isBlank() ? entry.group(1) : entry.group(2).strip();
                found.putIfAbsent(entry.group(1), new Target(entry.group(1), member, block.nid(), "[" + label + "]"));
            }
            Matcher inline = INLINE_ANCHOR.matcher(text);
            while (inline.find()) {
                String label = inline.group(2) == null || inline.group(2).isBlank() ? "[" + inline.group(1) + "]" : inline.group(2).strip();
                found.putIfAbsent(inline.group(1), new Target(inline.group(1), member, block.nid(), label));
            }
        }
    }

    /**
     * The words a bare reference to a block reads as: the anchor's own reference text; else the
     * block's title, a section's or a table's, with its attribute references read and its notes
     * left with it; else the id in brackets, as AsciiDoc prints a reference to an untitled block.
     */
    private static String label(DocumentAssembly assembly, DocumentAssembly.Member member, StoredBlock block, String id, String referenceText) {
        if (referenceText != null && !referenceText.isBlank()) {
            return referenceText.strip();
        }
        String title = block.title();
        if (title.isEmpty() && block.kind() == Block.Kind.DOCUMENT && !member.document().attributes().isEmpty()) {
            title = member.document().attributes().getFirst();
        }
        if (title.isEmpty()) {
            return "[" + id + "]";
        }
        return InlineText.withoutNotes(DocumentAttributes.reading().line(title, assembly.attributes(member, block))).strip();
    }

    /** @return the target an id names, if an anchor has it */
    public Optional<Target> of(String id) {
        return Optional.ofNullable(byId.get(named(id)));
    }

    /**
     * The block a link's target names.
     *
     * @param key the key a link is written with, the part of {@code #b1234} after the mark
     * @return the block's nid, if a target has that key
     */
    public Optional<Long> block(String key) {
        return Optional.ofNullable(blocksByKey.get(key));
    }

    /** @return whether there is no target */
    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** The id a reference names: its fragment when it has one, else its file's name without {@code .adoc} or a path. */
    static String named(String target) {
        String id = target.strip();
        int hash = id.lastIndexOf('#');
        if (hash >= 0 && hash < id.length() - 1) {
            id = id.substring(hash + 1);
        } else {
            id = AsciiDocMarkdown.topicOf(id);
            int slash = id.lastIndexOf('/');
            if (slash >= 0) {
                id = id.substring(slash + 1);
            }
        }
        return id;
    }

    /**
     * One line with its references read by what they find: a reference whose target is here
     * becomes a link to the target's block, a bare one with the target's words for its text; a
     * bibliography entry's anchor becomes an anchor of the entry's block and the entry's label.
     * What finds nothing stays as written.
     *
     * @param text the line, after its attribute references are read
     * @return the line as it is read; the same text when it holds no reference that finds a target
     */
    public String read(String text) {
        if (byId.isEmpty() || (text.indexOf("<<") < 0 && text.indexOf("xref:") < 0 && text.indexOf("[[[") < 0)) {
            return text;
        }
        String read = text;
        if (read.indexOf("[[[") >= 0) {
            Matcher entry = ENTRY_ANCHOR.matcher(read);
            StringBuilder out = new StringBuilder();
            while (entry.find()) {
                Target target = byId.get(entry.group(1));
                entry.appendReplacement(out, Matcher.quoteReplacement(target == null ? entry.group() : "[[" + target.key() + "]]" + target.label()));
            }
            read = entry.appendTail(out).toString();
        }
        if (read.indexOf("<<") >= 0) {
            Matcher reference = SHORT_REFERENCE.matcher(read);
            StringBuilder out = new StringBuilder();
            while (reference.find()) {
                Target target = byId.get(named(reference.group(1)));
                // a link's macro is not read straight after a letter: such a reference stays a reference
                boolean afterWord = reference.start() > 0 && Character.isLetterOrDigit(read.charAt(reference.start() - 1));
                String text2 = reference.group(2) == null || reference.group(2).isBlank() ? null : reference.group(2).strip();
                reference.appendReplacement(out, Matcher.quoteReplacement(target == null || afterWord ? reference.group()
                        : "link:#" + target.key() + "[" + (text2 == null ? target.label() : text2) + "]"));
            }
            read = reference.appendTail(out).toString();
        }
        if (read.indexOf("xref:") >= 0) {
            Matcher reference = MACRO_REFERENCE.matcher(read);
            StringBuilder out = new StringBuilder();
            while (reference.find()) {
                Target target = byId.get(named(reference.group(1)));
                boolean bare = reference.end() < read.length() && read.charAt(reference.end()) == ']';
                reference.appendReplacement(out, Matcher.quoteReplacement(target == null ? reference.group()
                        : "link:#" + target.key() + "[" + (bare ? target.label() : "")));
            }
            read = reference.appendTail(out).toString();
        }
        return read;
    }
}
