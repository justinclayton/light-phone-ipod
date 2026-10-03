# Issue tracker

Issues live on GitHub: `justinclayton/light-phone-ipod`. Use the `gh` CLI.

## Labels and states

| Label | Meaning |
|---|---|
| `needs-triage` | Filed, not yet shaped. Agents don't pick these up. |
| `ready-for-agent` | Scope and Done-when are settled; an agent may implement it alone. |
| `in-progress` | Claimed by an agent or person. Removed when the PR merges or the claim is dropped. |
| `bug` / `enhancement` / `question` | Type. `question` = research; the output is findings, not necessarily code. |

An issue is `ready-for-agent` only when its body has a **Done-when** (or
**Acceptance**) section and no open decisions. Decisions go to the user first.

List the batch:

```bash
gh issue list --state open --label ready-for-agent --json number,title,labels
```

Read one with its history:

```bash
gh issue view <n> --comments
```

Blockers: write `Blocked by #<n>` on its own line in the issue body (and a
comment saying so). An issue with an open blocker is not runnable.

## Claiming

```bash
gh issue edit <n> --add-label in-progress --add-assignee @me
gh issue comment <n> --body "Claimed by agent on branch <branch>."
```

Drop a claim by removing the label and commenting why.

## Branches and PRs

- Branch: `issue-<n>-<short-slug>`, cut from `main`.
- One issue per PR. Title is imperative ("Keep playback position when cycling repeat").
- Body starts with `Closes #<n>`, then: what changed, how it was verified, and
  any Done-when line not met (with why).
- Research issues (`question`): record findings in `FINDINGS.md`, open a PR
  with that change, and file follow-up issues (`needs-triage`) for anything
  actionable. Link them in the PR.
- Don't merge your own PR. The coordinator or the user merges.

## Media

Anything a person will see on the phone gets a screenshot in the PR: take it
with `adb exec-out screencap -p > shot.png`. `gh` can't upload images, so
commit them under `.scratch/pr-media/<n>/` on the branch and link them from
the PR body. Before/after pairs for UI changes. Audio behaviour (e.g. playback continuity) gets a short written
log of what was done and observed, plus relevant `make logs` lines.

## Running issues with subagents

### Defaults

- Model: `sonnet`; `opus` for cross-file architecture/refactor work.
- Wave size: 2–3 agents, but see the device rule below — most issues here
  touch the phone, so in practice one device-using issue per wave.
- Each agent works in its own git worktree:

  ```bash
  git worktree add ../ipod-wt/issue-<n> -b issue-<n>-<slug> main
  ```

  Worktrees sit beside the repo (not inside it) so Gradle and Xcode don't
  pick them up. Copy `local.properties` from the main checkout into the
  worktree; it's gitignored and Gradle needs it.

### Environment

- JDK 17 at `/opt/homebrew/opt/openjdk@17` (the Makefile exports it).
- Android SDK at `/opt/homebrew/share/android-commandlinetools`, adb at
  `/opt/homebrew/bin/adb`.
- Phone tool: `make build`, unit tests `./gradlew :tool:testDebugUnitTest`.
- Mac app (`mac/`): `xcodegen generate`, then `xcodebuild test` with
  `-derivedDataPath` **outside** `~/Documents` (Finder xattrs break codesign).
  Tests must pass their own keychain `keyTag:` so they don't overwrite the
  app's identity.
- Light SDK gotcha: never use trailing-lambda `navigateTo {}`; use
  `screenFactory =` or a `::Ctor` reference.

### Shared resources (exclusive)

Only one agent at a time may use each:

- **The emulator** (`make emu`, AVD `lp3`). One emulator instance; `make
  install`/`make sync` replace the tool and its library.
- **The real LP3** (USB, shows in `make status`). If it's missing, USB
  debugging may have been reset — long-press the Menu button to reach Android
  Settings. Only the user can reconnect it; report it rather than wait.
- **The Mac loader's keychain identity** (running the Music Loader app).

Acquire by commenting `Using <resource>` on the issue; release by commenting
`Released <resource>`. Never leave the emulator running a stale build without
saying so. The coordinator puts at most one resource-using issue in a wave.

### Setup before a wave

1. `git fetch && git status` on `main` — clean and up to date.
2. `make status` — note whether the emulator and/or LP3 are present.
3. `./gradlew :tool:testDebugUnitTest` passes on `main` (known-good baseline).

### Verifying a change

1. Unit tests pass.
2. `make run` on the emulator; `make sync` if the change needs music. Exercise
   the Done-when steps by hand via adb input/screencap, and watch `make logs`.
3. If Done-when says "on LP3", repeat on hardware when it's connected;
   otherwise say in the PR that the hardware check is outstanding.

### Where conflicts cluster

`tool/src/main/kotlin/com/thelightphone/ipod/player/PlayerController.kt`,
the screen/navigation files under `tool/src/main/kotlin/.../ui`, and
`docs/mac-loader.protocol.md`. Issues touching the same one of these go in
separate waves.
