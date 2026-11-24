# komet-stamped-document-plugin — Project Notes

<!-- This file is for hand-authored, project-specific information.
     It is created by ws:scaffold-init but never overwritten.
     Commit this file to git. -->

## Architecture

### Who a commit names (IKE-Network/ike-issues#1399)

Komet commits a document through the person's own `git`. A commit of the
person's own work names whom git's environment names: the IKE Dashboard
starts Komet with git's author and committer variables set to the person.
A commit of the assistant's own work has the assistant as git author and
the person as committer. Komet reads the two from the dashboard's file of
authors, `~/.ike/authors.properties` (KEC, 2026-11-21: Komet reads the file
itself). With no file, or no assistant in it, every commit is written as
before.

A person can open Komet with no assistant (KEC, 2026-11-22). The dashboard
then sets `IKE_ASSISTANT=none` in Komet's environment, beside git's
variables. Komet still reads the file and passes the assistant over
(`CommitAuthors.current`), so the assistant's own work is committed as the
person. The tools Komet starts inherit the variable.

The assistant's own work is a pending edit that the Komet assistant alone
wrote (`DocumentEditor.assistantsOwn`), a document the assistant made, and a
narration. An edit the person wrote into, alone or with the assistant on a
paired document, is the person's (KEC, 2026-11-21). An annotation the
assistant proposes is the person's signed statement, so it stays the
person's commit.

### Who is responsible for an author's work (IKE-Network/ike-issues#1399)

An assistant is an author concept of its own, and so is an address the
person committed under before each had an identity address (the file's
optional `person.earlier` line). One semantic of the starter set's
Responsible person pattern on such a concept names the person, and the
reason: Directed assistant or Earlier identity. Komet writes it, if it is
absent, when it reads a document whose history has a commit by that author
(KEC, 2026-11-23). It adds a record and never changes one, and writes it
even when Komet was opened with no assistant, since the record is about
commits already made. The record and the author's concept are in the
Development module, so both are knowledge and are synchronized.

Review then shows "KEC Claude, directed by Keith E Campbell" and "Keith E
Campbell (earlier commits)". With no record an author is shown by name.

An author concept made from now on has its address in the fully qualified
name, "Keith E Campbell (kec@komet.ike.network)", and the name alone as its
regular name. A concept that is there already keeps the name it has.

## Key Classes

- `CommitAuthors`: the file of authors, and the variables for a commit of
  the assistant's work (`assistantsWork`).
- `ResponsiblePersons`: writes the record (`recordIfAbsent`), reads it
  (`of`), and names an author for review (`authorName`). A place in the
  card that names a STAMP's author uses `authorName`, not `StampNames`.
- `DocumentCommit`: `write`, `first` and `annotate` take those variables;
  with none, a commit is git's own, as before.

## Testing Notes

- `AssistantCommitTest` and `CommitAuthorsTest` use invented names. Their
  scratch repositories set `core.hooksPath=/dev/null`, so a runner's global
  hook cannot change what they assert.
- `GitStampTermsTest` checks that KEC Claude's author concept is not Keith's
  (decision 3).
- `ResponsiblePersonsIT` gives each test people of its own, with invented
  names, since a store has one concept for each author. It sets `user.home`
  for the file of authors the materializer reads.
