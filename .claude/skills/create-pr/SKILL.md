---
name: create-pr
description: Push the current branch and open a GitHub PR against main whose description is a summarized version of the CHANGES.txt lines this branch added. Use when the user asks to create, open or make a PR / pull request.
---

# Create a PR from CHANGES.txt

The PR description is the branch's `CHANGES.txt` entries, summarized. A release notes script later
merges every PR's body into one set of release notes, so the body is **nothing but `- ` lines**: no
headers, no blank lines, no "What to test" section and no footer.

## 1. Check the branch

```bash
git branch --show-current
git status --short
git fetch origin main
git log --oneline origin/main..HEAD
```

- On `main`: stop and ask for a branch name. Never open a PR from `main`.
- **Anything not committed** (modified, staged or untracked files from `git status --short`): always
  tell the user, listing each file, and ask whether to commit it first or leave it out of the PR. Don't
  commit without being asked. Repeat the list next to the title and body in step 4 so it isn't missed.
- No commits ahead of `origin/main`: say so and stop.
- `gh pr view --json url,state` already shows an **open** PR for this branch: push, then offer to
  update that PR's title and body (`gh pr edit --title … --body-file -`) instead of opening a second one.

## 2. Collect this branch's entries

Use only the lines this branch **added** to `CHANGES.txt`, not the whole file:

```bash
git diff origin/main...HEAD -- CHANGES.txt | grep '^+- ' | sed 's/^+//'
```

If that returns nothing, say the branch has no CHANGES.txt entries and ask whether to continue with
a body written from the commits instead.

## 3. Summarize

Each line keeps the `CHANGES.txt` style: `- Area: what changed`, under 100 characters.

- **Keep the area prefix** (`Devices:`, `Files:`, `Scripts:` …) on every line, since there are no
  headers to carry it. Lines that had no prefix stay without one.
- **Order the lines by area**, starting with the area that has the most entries. Within an area,
  put the biggest features first and fixes last.
- **Merge related lines into one line** when they describe the same feature. For example, three
  separate filter-chip lines become one line about filter chips. Keep separate features separate.
- **A later line can cancel an earlier one.** If the branch adds a feature and then removes or
  replaces it, keep only the outcome.
- Don't invent behavior that isn't in the lines, and don't add rationale or file names.

**Title:** line 1 of the summarized list, copied word for word, leading `- ` included.

**Body:** every line *after* line 1. The title is not repeated in the body: the release notes script
prints the PR title and then its body, so a title that is also in the body shows up twice in the
release notes. If the list has only one line, the body is empty.

For a summarized list of

```
- Files: bookmark a folder to keep it under Places
- Files: double-click a file to download it and open it
- Scripts: a script that asks a question shows a prompt to answer it
- Devices: fixed a phone number or custom field ending in __ADM_NEXT__
```

the title is `- Files: bookmark a folder to keep it under Places` and the body is exactly this,
nothing else:

```
- Files: double-click a file to download it and open it
- Scripts: a script that asks a question shows a prompt to answer it
- Devices: fixed a phone number or custom field ending in __ADM_NEXT__
```

## 4. Confirm, then open it

Show the title and body to the user and wait for an OK. Opening a PR is outward-facing. Then:

```bash
git push -u origin HEAD
gh pr create --base main --title "<title>" --body-file - <<'EOF'
<body>
EOF
```

Reply with the PR URL.
