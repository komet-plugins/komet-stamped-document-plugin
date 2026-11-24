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

import design.knowledge.stamp.adoc.git.GitRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Saves a conversation with the assistant to a file, as an IKE Document (IKE-Network/ike-issues#1313;
 * step 3 of {@code design-2026-11-22-knowledge-shared-local}, its four questions settled by KEC on
 * 2026-11-23).
 *
 * <p>The file is AsciiDoc: the conversation's title, the attribute {@code :conversation-id:} naming the
 * conversation, then one section for each turn, anchored {@code turn-N} and headed by who spoke, with
 * the turn's Markdown turned into AsciiDoc ({@link MarkdownToAsciiDoc}). In a git working tree each
 * turn is a commit at the time it was said: the person's turn their own, under the identity the authors
 * file gives them, and the assistant's with the assistant as author and the person as committer, as
 * {@link CommitAuthors#assistantsWork} names them (IKE-Network/ike-issues#1399). With no authors file,
 * every turn is committed under git's own identity. Git blame, and so the document's STAMPs, then say who wrote each line
 * and when. In a folder without git the file is written once, as one version, unless the person lets
 * Komet make the folder a repository first.
 *
 * <p>The conversation stays where it is. Saving again to the same file adds the turns said since,
 * found by the file's own anchors, so the file is its own record of what it holds. Komet never writes
 * over a file of another conversation or another document, and never commits a file that has changes
 * not committed, so the assistant's commits hold nothing but the conversation.
 */
public final class ConversationFile {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationFile.class);

    /** The header attribute naming the conversation a file holds. */
    static final String CONVERSATION_ATTRIBUTE = "conversation-id";

    /** The name the assistant's turns are headed by when the authors file names no assistant. */
    static final String ASSISTANT = "Komet Assistant";

    private static final Pattern TURN_ANCHOR = Pattern.compile("^\\[\\[turn-(\\d+)]]$");
    private static final Pattern ATTRIBUTE = Pattern.compile("^:" + CONVERSATION_ATTRIBUTE + ":\\s*(\\S+)\\s*$");
    private static final int TIMEOUT_SECONDS = 60;

    private ConversationFile() {
    }

    /**
     * One turn of a conversation.
     *
     * @param markdown  what was said, as Markdown
     * @param assistant whether the assistant said it
     * @param time      when it was said, in epoch milliseconds; 0 when the conversation did not record it
     */
    public record Turn(String markdown, boolean assistant, long time) { }

    /** What a save did. */
    public enum Outcome {
        /** Turns were written. */
        SAVED,
        /** The file already holds every turn. */
        NOTHING_NEW,
        /** The file holds something else, so nothing was written. */
        NOT_THIS_CONVERSATION,
        /** Git or the file refused, so nothing more was written. */
        REFUSED
    }

    /**
     * What a save did.
     *
     * @param outcome what happened
     * @param file    the file
     * @param added   the turns written
     * @param commits the commits made
     * @param git     whether the file is in a git working tree
     * @param report  what happened, in a sentence or two for the person
     */
    public record Saved(Outcome outcome, Path file, int added, int commits, boolean git, String report) { }

    /**
     * The names turns are headed by.
     *
     * @param person    the person's name
     * @param assistant the assistant's name
     */
    public record Speakers(String person, String assistant) {

        /**
         * The names from the authors file, or for the person git's own, and for the assistant {@value #ASSISTANT}.
         *
         * @param authors the authors file, when there is one
         * @param folder  the folder the file goes in, whose git configuration names the person otherwise
         * @return the names
         */
        public static Speakers of(Optional<CommitAuthors> authors, Path folder) {
            String person = authors.map(a -> a.person().name())
                    .or(() -> gitName(folder))
                    .orElse("You");
            String assistant = authors.flatMap(CommitAuthors::assistant).map(CommitAuthors.Author::name).orElse(ASSISTANT);
            return new Speakers(person, assistant);
        }
    }

    /**
     * How many turns a file holds of a conversation.
     *
     * @param file           the file
     * @param conversationId the conversation's id
     * @return the turns it holds, 0 when there is no file yet; empty when the file holds something else
     * @throws IOException if the file cannot be read
     */
    public static OptionalInt held(Path file, String conversationId) throws IOException {
        if (!Files.exists(file)) {
            return OptionalInt.of(0);
        }
        return held(Files.readAllLines(file, StandardCharsets.UTF_8), conversationId);
    }

    /** How many turns the lines hold of a conversation; empty when they are not its file. */
    static OptionalInt held(List<String> lines, String conversationId) {
        boolean ours = false;
        int turns = 0;
        for (String line : lines) {
            Matcher attribute = ATTRIBUTE.matcher(line);
            if (attribute.matches()) {
                ours = attribute.group(1).equals(conversationId);
            }
            Matcher anchor = TURN_ANCHOR.matcher(line);
            if (anchor.matches()) {
                turns = Math.max(turns, Integer.parseInt(anchor.group(1)));
            }
        }
        return ours ? OptionalInt.of(turns) : OptionalInt.empty();
    }

    /**
     * Whether a file is in a git working tree.
     *
     * @param file the file
     * @return whether its folder is in one
     */
    public static boolean inRepository(Path file) {
        Path folder = file.toAbsolutePath().normalize().getParent();
        return folder != null && GitRepo.findRoot(folder).isPresent();
    }

    /**
     * Saves the turns a file does not hold yet.
     *
     * @param file           the file, new or this conversation's
     * @param conversationId the conversation's id
     * @param title          the conversation's title
     * @param turns          the conversation's turns, in order
     * @param makeRepository whether to make the folder a git repository when it is not in one
     * @param authors        the person and the assistant, from the authors file; empty to commit under git's own
     * @return what was done
     * @throws IOException if the file cannot be written or git cannot be run
     */
    public static Saved save(Path file, String conversationId, String title, List<Turn> turns, boolean makeRepository,
                             Optional<CommitAuthors> authors) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path folder = target.getParent();
        String name = title == null || title.isBlank() ? "Conversation" : title.strip();
        OptionalInt holds = held(target, conversationId);
        if (holds.isEmpty()) {
            return new Saved(Outcome.NOT_THIS_CONVERSATION, target, 0, 0, inRepository(target),
                    target.getFileName() + " holds another document, so Komet did not write over it. Choose a new file.");
        }
        int from = holds.getAsInt();
        if (from >= turns.size()) {
            return new Saved(Outcome.NOTHING_NEW, target, 0, 0, inRepository(target),
                    target.getFileName() + " already holds every turn of " + name + ".");
        }
        Files.createDirectories(folder);
        Optional<Path> root = GitRepo.findRoot(folder);
        if (root.isEmpty() && makeRepository) {
            Run init = git(folder, List.of("init", "--quiet"), Map.of());
            if (!init.ok()) {
                return new Saved(Outcome.REFUSED, target, 0, 0, false, "Komet could not make " + folder + " a repository: " + init.output());
            }
            root = Optional.of(folder);
        }
        Speakers speakers = Speakers.of(authors, folder);
        String existing = Files.exists(target) ? Files.readString(target, StandardCharsets.UTF_8) : "";
        if (root.isEmpty()) {
            StringBuilder text = new StringBuilder(existing.isEmpty() ? header(name, conversationId) : existing);
            for (int i = from; i < turns.size(); i++) {
                appendTurn(text, i + 1, turns.get(i), speakers);
            }
            Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
            int added = turns.size() - from;
            return new Saved(Outcome.SAVED, target, added, 0, false, (from == 0 ? "Saved " : "Added ") + count(added) + " of " + name
                    + " to " + target.getFileName() + ", in a folder without git, as one version.");
        }
        return commitTurns(root.get(), target, conversationId, name, turns, from, existing, authors, speakers);
    }

    /** Writes the turns from the given one on, a commit each, stopping at the first git refuses. */
    private static Saved commitTurns(Path root, Path target, String conversationId, String name, List<Turn> turns, int from,
                                     String existing, Optional<CommitAuthors> authors, Speakers speakers) throws IOException {
        String path = root.relativize(target).toString().replace('\\', '/');
        Run staged = git(root, List.of("diff", "--cached", "--name-only"), Map.of());
        if (!staged.ok() || !staged.output().isBlank()) {
            return new Saved(Outcome.REFUSED, target, 0, 0, true, staged.ok()
                    ? "The repository has staged changes. Commit or unstage them first, so each commit holds only the conversation."
                    : staged.output());
        }
        boolean tracked = !existing.isEmpty() && git(root, List.of("ls-files", "--error-unmatch", "--", path), Map.of()).ok();
        if (tracked) {
            Run status = git(root, List.of("status", "--porcelain", "--", path), Map.of());
            if (!status.output().isBlank()) {
                return new Saved(Outcome.REFUSED, target, 0, 0, true, target.getFileName()
                        + " has changes that are not committed. Commit or discard them first, so the new turns are committed alone.");
            }
        }
        if (!tracked && !existing.isEmpty()) {
            // Saved before in a folder without git: the turns it holds are the person's first commit, as the file stands.
            String held = from == 1 ? "turn 1" : "turns 1 to " + from;
            DocumentCommit.Result first = DocumentCommit.first(root, path, name + ", " + held + ", saved before in a folder without git",
                    authors.map(ConversationFile::personsOwn).orElse(Map.of()));
            if (!first.ok()) {
                return new Saved(Outcome.REFUSED, target, 0, 0, true, "Git refused the turns saved before: " + first.output());
            }
            tracked = true;
        }
        boolean assistantNamed = authors.flatMap(CommitAuthors::assistant).isPresent();
        StringBuilder text = new StringBuilder(existing.isEmpty() ? header(name, conversationId) : existing);
        int commits = 0;
        int undated = 0;
        // Git keeps seconds, and two commits of the file in one second leave its reader two latest
        // versions; the journal writes a turn and its reply a millisecond apart. Each commit is therefore
        // at least a second after the one before, the file's last commit included.
        long lastSecond = tracked ? lastCommitSecond(root, path) : 0;
        for (int i = from; i < turns.size(); i++) {
            Turn turn = turns.get(i);
            String before = text.toString();
            appendTurn(text, i + 1, turn, speakers);
            Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
            long time = turn.time() > 0 ? turn.time() : System.currentTimeMillis();
            if (turn.time() <= 0) {
                undated++;
            }
            long second = Math.max(time / 1000, lastSecond + 1);
            lastSecond = second;
            time = second * 1000;
            Map<String, String> variables = new HashMap<>(authors.map(a -> turn.assistant() && a.assistant().isPresent() ? a.assistantsWork() : personsOwn(a)).orElse(Map.of()));
            variables.put("GIT_AUTHOR_DATE", gitDate(time));
            String speaker = turn.assistant() ? speakers.assistant() : speakers.person();
            String message = subject(name, i + 1, speaker);
            DocumentCommit.Result result = !tracked && commits == 0
                    ? DocumentCommit.first(root, path, message, variables)
                    : DocumentCommit.write(root, path, message, false, variables);
            if (!result.ok()) {
                Files.writeString(target, before, StandardCharsets.UTF_8);
                String done = commits == 0 ? "No turn was saved" : "Saved " + count(commits) + " before it";
                return new Saved(commits == 0 ? Outcome.REFUSED : Outcome.SAVED, target, commits, commits, true,
                        "Git refused turn " + (i + 1) + ": " + result.output() + ". " + done + ".");
            }
            commits++;
        }
        LOG.info("Saved {} turn(s) of conversation {} to {} as {} commit(s)", commits, conversationId, target, commits);
        StringBuilder report = new StringBuilder(from == 0 ? "Saved " : "Added ").append(count(commits)).append(" of ").append(name)
                .append(" to ").append(target.getFileName()).append(" as ").append(commits == 1 ? "1 commit" : commits + " commits")
                .append(", each at the time it was said, to the second.");
        if (undated > 0) {
            report.append(' ').append(undated == 1 ? "1 turn had" : undated + " turns had")
                    .append(" no recorded time and took the time of saving.");
        }
        if (!assistantNamed && turns.subList(from, turns.size()).stream().anyMatch(Turn::assistant)) {
            report.append(authors.isEmpty() ? " Komet found no authors file (~/.ike/authors.properties)" : " Your authors file names no assistant")
                    .append(", so the assistant's turns are committed as yours; each section's heading says who spoke.");
        }
        return new Saved(Outcome.SAVED, target, commits, commits, true, report.toString());
    }

    /**
     * The person's own turn: the person as author and committer, as the authors file names them, the
     * identity their work in a document repository is committed under (IKE-Network/ike-issues#1399).
     */
    private static Map<String, String> personsOwn(CommitAuthors authors) {
        return Map.of(CommitAuthors.AUTHOR_NAME, authors.person().name(), CommitAuthors.AUTHOR_EMAIL, authors.person().email(),
                CommitAuthors.COMMITTER_NAME, authors.person().name(), CommitAuthors.COMMITTER_EMAIL, authors.person().email());
    }

    /**
     * The file name a conversation is offered, made from its title.
     *
     * @param title the conversation's title
     * @return lower-case words joined by hyphens, at most 60 characters, and {@code .adoc}
     */
    public static String fileName(String title) {
        String base = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.length() > 60) {
            base = base.substring(0, 60).replaceAll("-+$", "");
        }
        return (base.isEmpty() ? "conversation" : base) + ".adoc";
    }

    /** The author time of the last commit of a file, in epoch seconds; 0 when it has none. */
    private static long lastCommitSecond(Path root, String path) throws IOException {
        Run last = git(root, List.of("log", "-1", "--format=%at", "--", path), Map.of());
        try {
            return last.ok() && !last.output().isBlank() ? Long.parseLong(last.output().strip()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The file's first lines: the title and the attribute naming the conversation. */
    static String header(String title, String conversationId) {
        return "= " + title.replace('\n', ' ') + "\n:" + CONVERSATION_ATTRIBUTE + ": " + conversationId + "\n";
    }

    /** Adds one turn's section after a blank line. */
    static void appendTurn(StringBuilder text, int ordinal, Turn turn, Speakers speakers) {
        if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        if (!text.toString().endsWith("\n\n")) {
            text.append('\n');
        }
        text.append("[[turn-").append(ordinal).append("]]\n")
                .append("== ").append(turn.assistant() ? speakers.assistant() : speakers.person()).append("\n\n");
        String body = MarkdownToAsciiDoc.convert(turn.markdown(), 1);
        if (!body.isBlank()) {
            text.append(body).append('\n');
        }
    }

    private static String subject(String title, int ordinal, String speaker) {
        String shortTitle = title.length() > 50 ? title.substring(0, 49) + "…" : title;
        return shortTitle + ", turn " + ordinal + ", by " + speaker;
    }

    private static String count(int turns) {
        return turns == 1 ? "1 turn" : turns + " turns";
    }

    /** A time as git reads it: epoch seconds and this computer's offset. */
    static String gitDate(long millis) {
        ZoneOffset offset = ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(millis));
        int minutes = offset.getTotalSeconds() / 60;
        return String.format("@%d %s%02d%02d", millis / 1000, minutes < 0 ? "-" : "+", Math.abs(minutes) / 60, Math.abs(minutes) % 60);
    }

    private static Optional<String> gitName(Path folder) {
        try {
            Run name = git(folder, List.of("config", "user.name"), Map.of());
            return name.ok() && !name.output().isBlank() ? Optional.of(name.output().strip()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private record Run(int exit, String output) {
        boolean ok() {
            return exit == 0;
        }
    }

    private static Run git(Path directory, List<String> args, Map<String, String> variables) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(args);
        Path where = Files.isDirectory(directory) ? directory : directory.getParent();
        ProcessBuilder builder = new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        builder.environment().putAll(variables);
        Process process = builder.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "git " + args.getFirst() + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "interrupted");
        }
        return new Run(process.exitValue(), output);
    }
}
