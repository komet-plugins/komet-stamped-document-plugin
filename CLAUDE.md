# komet-stamped-document-plugin

The stamped document as a Komet plugin: AsciiDoc documents in git, read and edited line by line with each line's STAMP, on a block surface that pages and prints (ike-issues#1351)

## Build Standards

Files in `.claude/standards/` are build artifacts unpacked from `ike-build-standards`. DO NOT edit or commit them. See the workspace root CLAUDE.md for details.

## Build

```bash
mvn clean verify -DskipTests -T 1C
```

Inside a workspace, build from the workspace root: `mvn -pl <this-subproject>/<module> ...` there,
not from this directory. Maven 4 resolves siblings outside the build from the project-local
repository of the nearest `.mvn` root, and this subproject's own one goes stale
(IKE-Network/ike-issues#1265).

## Key Facts

- GroupId: `network.ike.komet`
- Version: `1-SNAPSHOT`
- Uses `--enable-preview` (Java 27)
- BOM: imports `dev.ikm.ike:ike-bom` for dependency version management

## Prohibited Patterns

- **Never use `maven-antrun-plugin`** — use a proper Maven goal or `exec-maven-plugin`
- **Never use `build-helper-maven-plugin` for multi-execution property chaining** —
  write a proper Maven goal in `ike-maven-plugin`
- **Never embed shell commands inline in POM** — extract to a named script

See `.claude/standards/` (after `mvn validate`) for full standards.
See `CLAUDE-komet-stamped-document-plugin.md` for project-specific notes.
