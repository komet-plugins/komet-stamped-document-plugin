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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The person using Komet and the assistant they direct, as git names them on a commit to a document
 * repository (IKE-Network/ike-issues#1399). A commit of the assistant's own work, a narration first, has
 * the assistant as git author and the person as committer: the person is responsible for it. The
 * person's own work is committed as before, under the git author and committer Komet was started with.
 *
 * <p>Komet reads the two from the IKE Dashboard's file of authors, {@code ~/.ike/authors.properties}
 * (KEC, 2026-11-21: Komet reads the file itself), the same file every other tool reads, with the same
 * keys: {@code person.name}, {@code person.email}, {@code assistant.name} and {@code assistant.email}.
 * With invented values:
 *
 * <pre>
 * person.name=Ada Lovelace
 * person.email=ada@komet.ike.network
 * assistant.name=Ada's Claude
 * assistant.email=ada-claude@komet.ike.network
 * </pre>
 *
 * <p>The file is read each time a commit needs it, so a change takes effect at the next commit. With no
 * file, a file that cannot be used, or no assistant in it, every commit is written as it was before this
 * class: under git's own environment and configuration.
 *
 * <p>A person can open Komet with no assistant (KEC, 2026-11-22: the IKE Dashboard's Open row ends with
 * who Komet opens as, and no assistant reaches Komet as a variable beside git's). The dashboard then sets
 * {@value #ASSISTANT_VARIABLE} to {@value #NO_ASSISTANT} in Komet's environment. Komet still reads the
 * file itself and passes the assistant over, so a narration and the assistant's own edits are committed as
 * the person, as with a file that names no assistant. The tools Komet starts inherit the variable.
 *
 * <p>The author concept Komet makes from a commit is made from its author's email
 * ({@code GitIdentityAdapter.author}), so the assistant is an author concept of its own, apart from the
 * person (decision 3 of the issue).
 *
 * @param person    the person, who commits and is responsible
 * @param assistant the assistant the person directs, when the file names one
 */
public record CommitAuthors(Author person, Optional<Author> assistant) {

    private static final Logger LOG = LoggerFactory.getLogger(CommitAuthors.class);

    /** Git's variable for the name of a commit's author. */
    public static final String AUTHOR_NAME = "GIT_AUTHOR_NAME";
    /** Git's variable for the email of a commit's author. */
    public static final String AUTHOR_EMAIL = "GIT_AUTHOR_EMAIL";
    /** Git's variable for the name of a commit's committer. */
    public static final String COMMITTER_NAME = "GIT_COMMITTER_NAME";
    /** Git's variable for the email of a commit's committer. */
    public static final String COMMITTER_EMAIL = "GIT_COMMITTER_EMAIL";

    /**
     * The variable that leaves the assistant out for this Komet and the tools it starts: set to
     * {@value #NO_ASSISTANT}, the file's assistant is passed over. The IKE Dashboard sets it
     * ({@code OpenAs} there) and reads it the same way ({@code Authors.current}).
     */
    public static final String ASSISTANT_VARIABLE = "IKE_ASSISTANT";
    /** The value of {@value #ASSISTANT_VARIABLE} that leaves the assistant out. Any other value says nothing. */
    public static final String NO_ASSISTANT = "none";

    private static final String PERSON_NAME = "person.name";
    private static final String PERSON_EMAIL = "person.email";
    private static final String ASSISTANT_NAME = "assistant.name";
    private static final String ASSISTANT_EMAIL = "assistant.email";

    /**
     * Checks that both are given.
     *
     * @param person    the person
     * @param assistant the assistant, or empty
     */
    public CommitAuthors {
        if (person == null || assistant == null) {
            throw new IllegalArgumentException("The person, and the assistant or none, are both needed");
        }
    }

    /**
     * One author: a name and an email, as git writes them on a commit.
     *
     * @param name  the name, without the marks git puts round an email
     * @param email the email, an identity address under {@code komet.ike.network} by the design
     */
    public record Author(String name, String email) {

        /**
         * Checks the two as git and the IKE Dashboard do: a name and an email that are not empty, and
         * neither holding {@code <}, {@code >} or a line break, which git refuses; an email with one
         * {@code @} between two parts, and no space.
         *
         * @param name  the name
         * @param email the email
         */
        public Author {
            name = name == null ? "" : name.strip();
            email = email == null ? "" : email.strip();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("An author's name is empty");
            }
            if (!plain(name)) {
                throw new IllegalArgumentException("The name " + name + " holds <, > or a line break, which git refuses");
            }
            int at = email.indexOf('@');
            if (email.isEmpty() || !plain(email) || email.chars().anyMatch(Character::isWhitespace)
                    || at <= 0 || at != email.lastIndexOf('@') || at == email.length() - 1) {
                throw new IllegalArgumentException("The email of " + name + ", " + (email.isEmpty() ? "an empty one" : email)
                        + ", is not an address of the form name@domain");
            }
        }

        private static boolean plain(String text) {
            return text.chars().noneMatch(c -> c == '<' || c == '>' || c == '\n' || c == '\r');
        }

        /**
         * The author as git shows one.
         *
         * @return {@code Name <email>}
         */
        public String ident() {
            return name + " <" + email + ">";
        }
    }

    /**
     * The variables that make git write a commit of the assistant's own work: the assistant as author,
     * the person as committer. Git prefers them to its configuration and to the variables Komet was
     * started with.
     *
     * @return the four variables, in a fixed order; none when there is no assistant
     */
    public Map<String, String> assistantsWork() {
        if (assistant.isEmpty()) {
            return Map.of();
        }
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(AUTHOR_NAME, assistant.get().name());
        variables.put(AUTHOR_EMAIL, assistant.get().email());
        variables.put(COMMITTER_NAME, person.name());
        variables.put(COMMITTER_EMAIL, person.email());
        return variables;
    }

    /**
     * The variables for a commit of the assistant's own work by the authors the file names now: none when
     * the file names no assistant, so the commit is written as before.
     *
     * @return the variables to add to git's environment
     */
    public static Map<String, String> assistantsWorkNow() {
        return current().map(CommitAuthors::assistantsWork).orElse(Map.of());
    }

    /**
     * Who a git commit names, as a sentence's end: the assistant and the person when the variables name
     * them, else nothing.
     *
     * @param variables the variables a commit was written with ({@link #assistantsWork})
     * @return {@code " as KEC Claude, committed by Keith E Campbell"}, or empty
     */
    public static String described(Map<String, String> variables) {
        return variables.containsKey(AUTHOR_NAME)
                ? " as " + variables.get(AUTHOR_NAME) + ", committed by " + variables.get(COMMITTER_NAME) : "";
    }

    /**
     * Where the file is for a user.
     *
     * @param home the user's home folder
     * @return {@code ~/.ike/authors.properties}
     */
    public static Path file(Path home) {
        return home.resolve(".ike").resolve("authors.properties");
    }

    /**
     * The person alone, with no assistant.
     *
     * @return the same person, and no assistant
     */
    public CommitAuthors withoutAssistant() {
        return assistant.isEmpty() ? this : new CommitAuthors(person, Optional.empty());
    }

    /**
     * Whether an environment leaves the assistant out.
     *
     * @param environment a process's environment
     * @return {@code true} when {@value #ASSISTANT_VARIABLE} is {@value #NO_ASSISTANT}, in any case
     */
    public static boolean leavesAssistantOut(Map<String, String> environment) {
        String value = environment.get(ASSISTANT_VARIABLE);
        return value != null && value.strip().equalsIgnoreCase(NO_ASSISTANT);
    }

    /**
     * The authors the file of the user running Komet names, as this Komet was started: with
     * {@value #ASSISTANT_VARIABLE} set to {@value #NO_ASSISTANT} in its environment, the assistant is left
     * out. A file that cannot be used is reported in the log and names no one, so commits are written as
     * before.
     *
     * @return the authors, or empty with no usable file
     */
    public static Optional<CommitAuthors> current() {
        return current(Path.of(System.getProperty("user.home")), System.getenv());
    }

    /**
     * The authors a user's file names, as a process with an environment sees them.
     *
     * @param home        the user's home folder
     * @param environment the process's environment
     * @return the authors, with the assistant left out when the environment says so; empty with no usable
     *         file
     */
    static Optional<CommitAuthors> current(Path home, Map<String, String> environment) {
        Path file = file(home);
        try {
            Optional<CommitAuthors> authors = read(file);
            return leavesAssistantOut(environment) ? authors.map(CommitAuthors::withoutAssistant) : authors;
        } catch (IllegalArgumentException unusable) {
            LOG.warn("{} is not used, so Komet commits under git's own author: {}", file, unusable.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Reads a file of authors, as UTF-8. Keys this class does not know are passed over, so a file the
     * registry writes later can say more.
     *
     * @param file the file
     * @return the authors, or empty when there is no file
     * @throws IllegalArgumentException if the file cannot be read, does not name the person in full, names
     *                                  the assistant in part, or holds a name or email git does not take
     */
    public static Optional<CommitAuthors> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("it cannot be read: " + e.getMessage(), e);
        }
        return Optional.of(of(properties));
    }

    /**
     * The authors a file's properties name.
     *
     * @param properties the properties
     * @return the authors
     * @throws IllegalArgumentException if the person is not named in full, the assistant is named in part,
     *                                  or a name or an email is not one git takes
     */
    static CommitAuthors of(Properties properties) {
        Optional<String> personName = value(properties, PERSON_NAME);
        Optional<String> personEmail = value(properties, PERSON_EMAIL);
        if (personName.isEmpty() || personEmail.isEmpty()) {
            throw new IllegalArgumentException("it names no person: " + PERSON_NAME + " and " + PERSON_EMAIL + " are both needed");
        }
        Optional<String> assistantName = value(properties, ASSISTANT_NAME);
        Optional<String> assistantEmail = value(properties, ASSISTANT_EMAIL);
        if (assistantName.isPresent() != assistantEmail.isPresent()) {
            throw new IllegalArgumentException(ASSISTANT_NAME + " and " + ASSISTANT_EMAIL + " go together, and it has only "
                    + (assistantName.isPresent() ? ASSISTANT_NAME : ASSISTANT_EMAIL));
        }
        return new CommitAuthors(new Author(personName.get(), personEmail.get()),
                assistantName.map(name -> new Author(name, assistantEmail.get())));
    }

    private static Optional<String> value(Properties properties, String key) {
        return Optional.ofNullable(properties.getProperty(key)).map(String::strip).filter(text -> !text.isEmpty());
    }
}
