---
name: build-and-parallel-integration-gotchas
description: Pitfalls when integrating parallel worktree-agent work back into this repo (EOL traps, uncommitted-base divergence)
metadata:
  type: feedback
---

When fanning board cards out to parallel worktree-isolated agents and merging their `card/*` branches back, two repo-specific traps bite.

**Why this matters:** ignoring either silently corrupts files or builds against a stale base.

**How to apply:**
- **EOL trap — never `git merge-file`/`git apply` here.** Repo blobs are LF, the Windows working tree is CRLF, and there is **no `.gitattributes`**. Line-based 3-way tools see *every* line as changed and emit whole-file conflicts. Hand-merge with the Edit tool instead; for an arity-style mechanical bump across a file a `perl -0pi -e 's/.../.../g'` is safe.
- **Stale-base trap.** `Agent` worktrees branch from committed HEAD, but this repo routinely carries whole finished board cards as **uncommitted** `M`/`??` in the main tree (e.g. E11-01/02/09 were uncommitted while marked Done). Parallel agents therefore build against a stale base and miss that work. Integrate their branches against the *current working tree*, not HEAD; reconcile only the genuinely 3-way-conflicting files; and watch cross-card breaks (one card changed `CreateGameRequest`'s arity while another constructed it). Always finish with a full reactor build + frontend typecheck.
- Don't commit unless asked — leave the integrated result uncommitted for the user to review.

Build/JDK details live in [[backend-jdk25-build]]; board workflow in [[stellar-compact-board-workflow]].
