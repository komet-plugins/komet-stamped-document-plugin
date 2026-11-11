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

import network.ike.komet.document.DocumentAssembly.Member;
import network.ike.komet.document.GitDocumentMaterializer.StoredLine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The conditional directives of an assembly, read for a person who wants to see and change what
 * they leave out: for every line a showing does not read, the directive that leaves it out, in
 * the author's words and in plain ones; which lines are the directives themselves; and the
 * attribute names the directives ask about, which are what a reader might set to read the
 * document as another document reads it.
 *
 * <p>Whether a line is read is the assembly's to say ({@link DocumentAssembly#read}), and whether
 * a directive is met is asked of the attributes the line was read in
 * ({@link DocumentAssembly#attributes(Member, long)}); nothing of AsciiDoc's rules for a
 * condition is decided here. What is added is the bookkeeping the assembly does not keep: which
 * open directive a line stands under.
 */
public final class DocumentConditions {

    private static final Pattern CONDITIONAL = Pattern.compile("^(ifdef|ifndef|ifeval|endif)::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern REFERENCE = Pattern.compile("\\{([A-Za-z0-9_][A-Za-z0-9_-]*)\\}");

    /**
     * A conditional directive that leaves lines out.
     *
     * @param directive the directive as written, {@code ifdef::compendium[]}
     * @param says      when its lines are read, in plain words: {@code where compendium is set}
     * @param member    the showing its line stands in
     * @param line      its line
     */
    public record Condition(String directive, String says, Member member, StoredLine line) {

        /** @return what stands over the lines it leaves out */
        public String label() {
            return "Left out here: read only " + says;
        }
    }

    /** An open directive: what it is, and whether the attributes at its line meet it. */
    private record Open(Condition condition, boolean met) { }

    private final Map<Member, Map<Long, Condition>> leftOut = new IdentityHashMap<>();
    private final Map<Member, Set<Long>> directives = new IdentityHashMap<>();
    private final Set<String> names = new LinkedHashSet<>();

    private DocumentConditions() { }

    /**
     * Reads the conditional directives of an assembly.
     *
     * @param assembly the assembly
     * @return its conditions
     */
    public static DocumentConditions of(DocumentAssembly assembly) {
        DocumentConditions conditions = new DocumentConditions();
        Deque<Open> open = new ArrayDeque<>();
        List<StoredLine> lines = assembly.lines();
        for (int ordinal = 0; ordinal < lines.size(); ordinal++) {
            StoredLine line = lines.get(ordinal);
            Member member = assembly.memberAt(ordinal).orElse(null);
            if (member == null) {
                continue;
            }
            boolean read = assembly.read(member).line(line.nid()).isPresent();
            Matcher conditional = CONDITIONAL.matcher(line.text());
            if (conditional.matches() && !conditional.group(1).equals("endif")) {
                conditions.name(conditional.group(1), conditional.group(2).strip(), conditional.group(3));
            }
            if (read || !conditional.matches()) {
                // a line of the document, or a directive's look-alike inside a comment block: left out when it stands under an unmet directive
                if (!read) {
                    conditions.leftOut.computeIfAbsent(member, m -> new HashMap<>()).put(line.nid(), governing(open, null));
                }
                continue;
            }
            String directive = conditional.group(1);
            String target = conditional.group(2).strip();
            String brackets = conditional.group(3);
            if (directive.equals("endif")) {
                open.pollLast();
                conditions.directives.computeIfAbsent(member, m -> new HashSet<>()).add(line.nid());
                continue;
            }
            Condition condition = new Condition(line.text(), says(directive, target, brackets), member, line);
            if (!directive.equals("ifeval") && !brackets.isEmpty()) {
                // a conditional that holds its text on its own line opens nothing; not read, it is left out by itself or by what stands over it
                conditions.leftOut.computeIfAbsent(member, m -> new HashMap<>()).put(line.nid(), governing(open, condition));
                continue;
            }
            open.addLast(new Open(condition, met(assembly, member, line)));
            conditions.directives.computeIfAbsent(member, m -> new HashSet<>()).add(line.nid());
        }
        return conditions;
    }

    /** The directive that leaves a line out: the outermost open one that is not met, else the line's own. */
    private static Condition governing(Deque<Open> open, Condition own) {
        for (Iterator<Open> it = open.iterator(); it.hasNext(); ) {
            Open at = it.next();
            if (!at.met()) {
                return at.condition();
            }
        }
        return own != null ? own : new Condition("", "where its condition is met", null, null);
    }

    /** Whether the attributes at a directive's line meet it: asked of a walk that begins there. */
    private static boolean met(DocumentAssembly assembly, Member member, StoredLine line) {
        DocumentAttributes.Walk walk = new DocumentAttributes.Walk(assembly.attributes(member, line.nid()));
        walk.line(line.text());
        return walk.line("a line of text");
    }

    private void name(String directive, String target, String brackets) {
        if (directive.equals("ifeval")) {
            Matcher reference = REFERENCE.matcher(brackets);
            while (reference.find()) {
                names.add(reference.group(1));
            }
            return;
        }
        for (String name : target.split("[,+]")) {
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        }
    }

    /**
     * When a directive's lines are read, in plain words, as AsciiDoc reads the directive: a comma
     * between names is any of them, a plus all of them.
     */
    static String says(String directive, String target, String brackets) {
        if (directive.equals("ifeval")) {
            return "where " + brackets.strip() + " holds";
        }
        boolean all = target.contains("+");
        List<String> names = new ArrayList<>();
        for (String name : target.split("[,+]")) {
            if (!name.isBlank()) {
                names.add(name.strip());
            }
        }
        if (names.isEmpty()) {
            return "where its condition is met";
        }
        boolean set = directive.equals("ifdef");
        if (names.size() == 1) {
            return "where " + names.getFirst() + (set ? " is set" : " is not set");
        }
        if (names.size() == 2) {
            String a = names.get(0);
            String b = names.get(1);
            if (set) {
                return all ? "where " + a + " and " + b + " are both set" : "where " + a + " or " + b + " is set";
            }
            return all ? "where " + a + " and " + b + " are not both set" : "where neither " + a + " nor " + b + " is set";
        }
        String list = String.join(", ", names);
        if (set) {
            return all ? "where all of " + list + " are set" : "where any of " + list + " is set";
        }
        return all ? "where " + list + " are not all set" : "where none of " + list + " is set";
    }

    /**
     * The directive that leaves a line of a showing out.
     *
     * @param member the showing
     * @param nid    the line
     * @return the directive, or empty for a line the showing reads and for a directive's own line
     */
    public Optional<Condition> leftOut(Member member, long nid) {
        Map<Long, Condition> of = leftOut.get(member);
        return of == null ? Optional.empty() : Optional.ofNullable(of.get(nid));
    }

    /**
     * Whether a line of a showing is a directive that opens or closes a conditional: markup of
     * the document's making, which no view of the document as it reads draws.
     *
     * @param member the showing
     * @param nid    the line
     * @return whether the line is an {@code ifdef}, {@code ifndef} or {@code ifeval} that opens, or an {@code endif}
     */
    public boolean directive(Member member, long nid) {
        Set<Long> of = directives.get(member);
        return of != null && of.contains(nid);
    }

    /** @return whether a showing has any line a conditional leaves out, or any directive */
    public boolean touches(Member member) {
        return leftOut.containsKey(member) || directives.containsKey(member);
    }

    /** @return the attribute names the assembly's directives ask about, in the order they are first asked */
    public List<String> names() {
        return List.copyOf(names);
    }
}
