# Terminal conformance fixtures

`TerminalConformanceTest` checks `TerminalBuffer` against a reference terminal.
The reference is tmux: `record.py` runs real programs (bash, vim, less, htop,
mc) in a fixed-size tmux pane, records the exact bytes they write with
`pipe-pane`, and at each checkpoint stores what tmux shows — characters,
colours, attributes, cursor position and whether the alternate screen is on.

The test replays the same bytes into `TerminalBuffer`, applies the same
resizes at the same byte offsets, and compares the screen at every checkpoint.
The fixtures are committed, so CI needs neither tmux nor the programs.

## Re-recording

```bash
tools/terminal-conformance/record.py            # all scenarios
tools/terminal-conformance/record.py vim less   # some of them
```

Needs tmux 3.x, bash, vim, less, htop and mc. Output goes to
`app/src/test/resources/terminal-conformance/`. htop and mc show the machine's
own data, so their fixtures change on every recording; each fixture is still
self-consistent, because bytes and reference come from the same run.

## Adding a scenario

Add an entry to `SCENARIOS` in `record.py` (a command and a list of
`wait` / `keys` / `resize` / `check` steps), record it, and add a test method
that calls `replay("<name>")`.

When a difference shows up, decide which side is right before changing
anything: tmux follows xterm closely, but where it deliberately differs, the
expected behaviour is xterm's.
