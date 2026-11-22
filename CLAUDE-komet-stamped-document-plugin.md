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

The assistant's own work is a pending edit that the Komet assistant alone
wrote (`DocumentEditor.assistantsOwn`), a document the assistant made, and a
narration. An edit the person wrote into, alone or with the assistant on a
paired document, is the person's (KEC, 2026-11-21). An annotation the
assistant proposes is the person's signed statement, so it stays the
person's commit.

## Key Classes

- `CommitAuthors`: the file of authors, and the variables for a commit of
  the assistant's work (`assistantsWork`).
- `DocumentCommit`: `write`, `first` and `annotate` take those variables;
  with none, a commit is git's own, as before.

## Testing Notes

- `AssistantCommitTest` and `CommitAuthorsTest` use invented names. Their
  scratch repositories set `core.hooksPath=/dev/null`, so a runner's global
  hook cannot change what they assert.
- `GitStampTermsTest` checks that KEC Claude's author concept is not Keith's
  (decision 3).
