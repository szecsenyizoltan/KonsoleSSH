#!/usr/bin/env python3
"""Record terminal conformance fixtures, with tmux as the reference emulator.

Each scenario runs a real program in a fixed-size tmux pane. tmux pipe-pane
records the exact bytes the program writes; at every checkpoint the script
also stores what tmux shows (characters, colours, attributes, cursor). The
Kotlin test TerminalConformanceTest replays the bytes into TerminalBuffer and
compares the screen at the same byte offsets.

Usage: tools/terminal-conformance/record.py [scenario ...]
Needs tmux 3.x, vim, less, htop, mc and bash. Output goes to
app/src/test/resources/terminal-conformance/<scenario>.{bin,json}.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "terminal-conformance")
SOCKET = "konsole-conformance"
COLS, ROWS = 80, 24

# DEC Special Graphics, as TerminalBuffer maps it ('_' .. '~').
DEC = dict(zip("_`abcdefghijklmnopqrstuvwxyz{|}~", " ◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"))

PRINTF_BASICS = r"""
printf '\033[31mred\033[0m \033[1;32mbold green\033[0m \033[38;5;208m208\033[0m \033[38;2;10;200;30mrgb\033[0m\r\n'
printf '\033[2mdim\033[0m \033[3mitalic\033[0m \033[4munder\033[0m \033[7mreverse\033[0m \033[9mstrike\033[0m \033[44;97mbg\033[0m\r\n'
printf '%s\r\n' "$(printf 'w%.0s' $(seq 1 100))"
printf 'a\tb\tc\tend\r\n'
printf 'wide 中文 emoji 🔥 combining e\314\201 done\r\n'
printf 'overwrite\rOVER\r\n'
printf 'back\b\b\bX\r\n'
printf '\033[44mbce line\033[K\033[0m\r\n'
printf '\033(0lqqqqk\033(B\r\n\033(0x\033(Bbox \033(0x\033(B\r\n\033(0mqqqqj\033(B\r\n'
printf 'abcdef\033[3D\033[2@XY\033[1P\r\n'
printf 'line A\r\nline B\r\nline C\r\n\033[2A\033[1L inserted\r\n\033[1M'
printf '\033[20;23r\033[23;1Hregion1\nregion2\nregion3\nregion4\033[r\033[24;1Hafter region'
"""

BASH_ENV = {"PS1": "$ ", "HOME": "", "INPUTRC": "/dev/null"}


def tmux(*args, capture=False):
    cmd = ["tmux", "-L", SOCKET, "-f", "/dev/null", "-u", *args]
    if capture:
        return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    subprocess.run(cmd, check=True)
    return None


def keys(*k):
    return ("keys", k)


def wait(s):
    return ("wait", s)


def check(label):
    return ("check", label)


def resize(cols, rows):
    return ("resize", (cols, rows))


SCENARIOS = {
    "printf-basics": {
        "command": "sh {dir}/basics.sh",
        "steps": [wait(1.5), check("all")],
    },
    "bash-line-editing": {
        "command": "bash --norc --noprofile",
        "steps": [
            wait(1),
            keys("echo " + "x" * 90 + " end"),
            check("long line wraps"),
            keys("C-a", "Right", "Right", "Right", "Right", "Right"),
            check("cursor moved back over the wrap"),
            keys("C-c"),
            keys("printf 'one\\ntwo\\nthree\\n'", "Enter"),
            check("after output"),
        ],
    },
    "vim": {
        "command": "vim -u NONE -N -i NONE {sample}",
        "steps": [
            wait(1.5),
            check("file open"),
            keys(":set number", "Enter", "G", "o", "added line with 中文", "Escape"),
            check("numbers and insert"),
            resize(60, 18),
            wait(1),
            check("resized"),
            resize(COLS, ROWS),
            wait(1),
            keys(":q!", "Enter"),
            wait(1),
            check("main screen restored"),
        ],
        "before": "printf '$ before vim\\n'",
    },
    "less": {
        "command": "less -X {sample}",
        "steps": [wait(1), check("first page"), keys("G"), check("end"), keys("q"), wait(0.5), check("quit")],
    },
    "htop": {
        "command": "htop -d 600 --no-mouse",
        "steps": [wait(2.5), check("running"), keys("q"), wait(0.5)],
    },
    "mc-utf8": {
        "command": "mc --nosubshell --nomouse {dir}",
        "steps": [wait(2.5), check("panels"), keys("F10"), wait(0.5)],
    },
    "mc-acs": {
        "command": "env LANG=C LC_ALL=C mc --nosubshell --nomouse {dir}",
        "steps": [wait(2.5), check("panels"), keys("F10"), wait(0.5)],
    },
}

SGR_FLAGS = {1: "bold", 2: "dim", 3: "italic", 4: "underline", 7: "reverse", 9: "strike"}


def parse_capture(text):
    """tmux capture-pane -e output → rows of [char, fg, bg, flags]."""
    rows = []
    # tmux emits SGR and SO/SI only on change, so the state runs across lines.
    fg, bg, flags, acs = None, None, set(), False
    for line in text.split("\n")[:-1]:
        cells = []
        i = 0
        while i < len(line):
            ch = line[i]
            if ch == "\x1b":
                m = re.match(r"\x1b\[([0-9;:]*)m", line[i:])
                if not m:
                    raise ValueError("unexpected escape in capture: %r" % line[i:i + 10])
                fg, bg, flags = apply_sgr(m.group(1), fg, bg, flags)
                i += len(m.group(0))
                continue
            if ch == "\t":
                # tmux keeps tab cells as one tab character; expand to the next stop.
                col = sum(width(c[0]) for c in cells)
                for _ in range(8 - col % 8):
                    cells.append([" ", fg, bg, sorted(flags)])
            elif ch == "\x0e":
                acs = True
            elif ch == "\x0f":
                acs = False
            else:
                if acs:
                    ch = DEC.get(ch, ch)
                # Combining marks belong to the previous cell, as in TerminalBuffer.
                if cells and is_combining(ch):
                    cells[-1][0] += ch
                else:
                    cells.append([ch, fg, bg, sorted(flags)])
            i += 1
        # Trailing blank cells in the default colours are not part of the comparison.
        while cells and cells[-1][0] == " " and cells[-1][2] is None and "reverse" not in cells[-1][3]:
            cells.pop()
        rows.append(cells)
    return rows


def width(ch):
    import unicodedata
    cp = ord(ch[0])
    if unicodedata.east_asian_width(ch[0]) in ("W", "F") or 0x1F300 <= cp <= 0x1FAFF:
        return 2
    return 1


def is_combining(ch):
    import unicodedata
    return unicodedata.combining(ch) != 0 or ch in "‍️"


def apply_sgr(params, fg, bg, flags):
    nums = [int(p) if p else 0 for p in re.split("[;:]", params)] if params else [0]
    flags = set(flags)
    i = 0
    while i < len(nums):
        n = nums[i]
        if n == 0:
            fg, bg, flags = None, None, set()
        elif n in SGR_FLAGS:
            flags.add(SGR_FLAGS[n])
        elif n == 21:
            flags.add("underline")
        elif n == 22:
            flags -= {"bold", "dim"}
        elif n == 23:
            flags.discard("italic")
        elif n == 24:
            flags.discard("underline")
        elif n == 27:
            flags.discard("reverse")
        elif n == 29:
            flags.discard("strike")
        elif 30 <= n <= 37:
            fg = n - 30
        elif 40 <= n <= 47:
            bg = n - 40
        elif 90 <= n <= 97:
            fg = n - 90 + 8
        elif 100 <= n <= 107:
            bg = n - 100 + 8
        elif n == 39:
            fg = None
        elif n == 49:
            bg = None
        elif n in (38, 48):
            if nums[i + 1] == 5:
                color = nums[i + 2]
                i += 2
            else:
                color = "#%02x%02x%02x" % tuple(nums[i + 2:i + 5])
                i += 4
            if n == 38:
                fg = color
            else:
                bg = color
        i += 1
    return fg, bg, flags


def record(name, spec, workdir):
    sample = os.path.join(workdir, "sample.txt")
    command = spec["command"].format(sample=sample, dir=workdir)
    env_prefix = "env LANG=C.UTF-8 TERM=xterm-256color PS1='$ ' INPUTRC=/dev/null HOME=%s " % workdir
    raw = os.path.join(workdir, name + ".bin")
    before = spec.get("before", "true")
    tmux("kill-server") if server_running() else None
    tmux("new-session", "-d", "-x", str(COLS), "-y", str(ROWS),
         "%s sh -c \"sleep 0.4; %s; %s; sleep 600\"" % (env_prefix, before, command.replace('"', '\\"')))
    tmux("set", "-g", "status", "off")
    tmux("pipe-pane", "-o", "cat > " + raw)
    checkpoints = []
    for kind, arg in spec["steps"]:
        if kind == "wait":
            time.sleep(arg)
        elif kind == "keys":
            for k in arg:
                tmux("send-keys", "-t", "0", *(["-l", k] if len(k) > 1 and not is_key_name(k) else [k]))
                time.sleep(0.15)
            time.sleep(0.6)
        elif kind == "resize":
            time.sleep(0.4)
            checkpoints.append({"type": "resize", "offset": os.path.getsize(raw), "cols": arg[0], "rows": arg[1]})
            tmux("resize-window", "-t", "0", "-x", str(arg[0]), "-y", str(arg[1]))
            time.sleep(0.4)
        elif kind == "check":
            time.sleep(0.5)
            capture = tmux("capture-pane", "-p", "-e", "-N", "-t", "0", capture=True)
            info = tmux("display", "-p", "-t", "0",
                        "#{cursor_x} #{cursor_y} #{alternate_on} #{pane_width} #{pane_height}",
                        capture=True).split()
            checkpoints.append({
                "type": "check",
                "label": arg,
                "offset": os.path.getsize(raw),
                "cursor": [int(info[0]), int(info[1])],
                "alternate": info[2] == "1",
                "cols": int(info[3]),
                "rows": int(info[4]),
                "screen": parse_capture(capture),
            })
    tmux("kill-server")
    time.sleep(0.2)
    shutil.copyfile(raw, os.path.join(OUT, name + ".bin"))
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump({"scenario": name, "cols": COLS, "rows": ROWS, "checkpoints": checkpoints},
                  f, ensure_ascii=False, indent=1)
    print("%-18s %6d bytes, %d checkpoints" % (name, os.path.getsize(raw), len(checkpoints)))


def is_key_name(k):
    return k in ("Enter", "Escape", "Up", "Down", "Left", "Right", "F10") or k.startswith("C-")


def server_running():
    return subprocess.run(["tmux", "-L", SOCKET, "has-session"], capture_output=True).returncode == 0


def main():
    names = sys.argv[1:] or list(SCENARIOS)
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory() as workdir:
        with open(os.path.join(workdir, "basics.sh"), "w") as f:
            f.write(PRINTF_BASICS + "\nsleep 600\n")
        with open(os.path.join(workdir, "sample.txt"), "w") as f:
            for i in range(1, 121):
                f.write("%03d The quick brown fox jumps over the lazy dog; árvíztűrő tükörfúrógép\n" % i)
        for name in names:
            record(name, SCENARIOS[name], workdir)


if __name__ == "__main__":
    main()
