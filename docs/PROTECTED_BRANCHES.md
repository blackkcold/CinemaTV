# Protected branches and how a change reaches them

This applies to every branch the repository protects — currently `dev-compose` (the only
development mainline and the Dev channel source) and `main` (the Stable release line). It
describes what the protection actually enforces, what a bypass looks like in practice, and what
must happen when one is used. It is a process contract, not a build gate.

## What the protection enforces

`dev-compose` requires a pull request, requires the `apk` status check, and does not enforce its
rules on administrators. No approvals are required beyond the checks. Verify the live values
before relying on them — a settings change is not announced anywhere:

```sh
gh api repos/<owner>/<repo>/branches/dev-compose/protection \
  | python3 -c "import json,sys; d=json.load(sys.stdin); \
      print(bool(d.get('required_pull_request_reviews')), \
            (d.get('required_status_checks') or {}).get('contexts'), \
            (d.get('enforce_admins') or {}).get('enabled'))"
```

Two consequences worth knowing:

- **A bypass succeeds silently.** The server prints `remote: Bypassed rule violations for
  refs/heads/<branch>` followed by the rules it ignored, and the push still returns exit 0. A
  mount of that message in a log is the only evidence that a change skipped review; nothing in
  the commit records it.
- **`enforce_admins: false` is why a push works at all** for an account with admin rights. If
  that is ever set to true, the same command fails instead. Neither outcome is a substitute for
  a pull request.

Check whether a commit actually went through review rather than assuming it did:

```sh
gh api repos/<owner>/<repo>/commits/<sha>/pulls | python3 -c "import json,sys; print(len(json.load(sys.stdin)))"
```

Zero means the commit is not attached to any pull request, whatever the branch protection says.

## The rule

Every change to a protected branch goes through a pull request, including documentation,
version-ledger and other process-only edits. "It is only docs" and "the product code is
untouched" are not exemptions: the review is over the change, and a version-ledger or public
document edit changes what the project promises just as surely as a code change does.

```sh
git switch -c docs/<topic>
# edit, verify locally, commit
git push -u origin docs/<topic>
gh pr create --base dev-compose --fill
```

A direct push to a protected branch is a breach of this rule even when the server accepts it.
The server's acceptance is not permission.

## What to do when a bypass has already happened

A bypass cannot be undone by rewriting history; force-pushing a shared branch is a separate and
worse breach, and is itself blocked. What makes the situation sound is disclosure plus a
verifiable statement of what was and was not reviewed:

1. **Say it in the change itself.** The commit or the release note states that the branch
   protection was bypassed and why. A reader must not have to find the rule-violation line in a
   CI log.
2. **State the reviewed scope precisely**, and keep it comparable to what a PR would have
   checked. For a process-only change, prove that the product code is untouched:
   ```sh
   git diff --name-only <last-accepted-sha>..<head> | grep -E '^app/src|^backdrop/src'
   ```
   An empty result is the evidence. Anything under those roots is a product change and needs
   the full gate, not this shortcut.
3. **Re-verify the accepted artifacts still describe the branch.** A version-ledger or document
   commit that lands after a candidate was built does not invalidate the candidate only because
   the bytes did not change; it invalidates it if the published identity or the notes now
   disagree with the release. Compare the release body and assets against the branch before
   claiming consistency.
4. **Feed it forward.** If the bypass happened because the flow was awkward, fix the flow in the
   same pass — a ledger edit that only ever lands by bypass is a design gap, not a one-off
   mistake.

## Prevention

`scripts/updates/build-unsigned-candidate.sh` refuses a dirty tree, and the release process
already needs a clean, exact commit. Run the ledger and document edits as their own PR, merge it,
and then build the candidate from the merge commit. That order keeps every published artifact
traceable to a reviewed commit and makes a bypass unnecessary rather than merely discouraged.
