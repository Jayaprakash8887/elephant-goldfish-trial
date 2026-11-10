#!/usr/bin/env python3
"""Drive EGM in headless Claude Code sessions and record every turn in trial-log/HISTORY.md.

EGM's skills and reviewer agents load only in a session that started after the plugin was
installed, and the skills start only from a prompt (they set disable-model-invocation). The
session that runs the trial therefore sends each message to a headless Claude Code session in
this folder, and this script records what was sent, the reply, and which files changed.

  egm.py run  --label TEXT (--prompt TEXT | --prompt-file PATH) [--resume SESSION_ID]
              [--watch PATH ...] [--timeout SECONDS]
  egm.py note --label TEXT --actor TEXT --input-file PATH --output-file PATH [--watch PATH ...]

`run` prints "SESSION <id>" and "ENTRY H-NNN" lines, then the reply. The raw JSON result of
each run is saved to trial-log/runs/H-NNN.json. `note` records something the trial session did
itself (an implementation agent, a build, a test run) in the same format.
"""
import argparse
import fcntl
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import qa
from redact import scrub

ROOT = Path(__file__).resolve().parents[2]
LOG_DIR = ROOT / "trial-log"
HISTORY = LOG_DIR / "HISTORY.md"
RUNS = LOG_DIR / "runs"
LOCK = LOG_DIR / ".history.lock"
SKIP_DIRS = {".git", "node_modules", "target", "dist", "coverage", ".vite", "trial-log"}
MODEL = os.environ.get("EGM_MODEL", "claude-opus-5-5")
EFFORT = os.environ.get("EGM_EFFORT", "high")
# acceptEdits lets EGM write its drafts and reports inside this folder. Reviewers are read-only
# by their own definitions. Only read-only git and ls commands are pre-approved. EGM gates use
# plain chat because nobody can answer a structured question in a headless run.
#
# Headless sessions ignore the project's permission rules until the folder has been trusted in an
# interactive terminal session ("Ignoring 4 permissions.allow entries ... this workspace has not
# been trusted"). The user's four read-only rules for the plugin's own folders are therefore
# passed again here, in the absolute form EGM's docs use (CHALLENGES.md C-11).
PLUGIN_READ_RULES = [f"Read(/{Path.home().as_posix()}/.claude/plugins/cache/egm/egm/*/{folder}/**)"
                     for folder in ("agents", "docs", "resources", "skills")]
FLAGS = ["--output-format", "json", "--permission-mode", "acceptEdits", "--model", MODEL,
         "--effort", EFFORT, "--disallowedTools", "AskUserQuestion",
         "--allowedTools", *PLUGIN_READ_RULES, "Bash(git config *)", "Bash(git log *)",
         "Bash(git diff *)", "Bash(git status *)", "Bash(git show *)", "Bash(ls *)"]


def find_claude():
    """Newest Claude Code bundled with the VS Code extension, unless EGM_CLAUDE_BIN is set."""
    if os.environ.get("EGM_CLAUDE_BIN"):
        return os.environ["EGM_CLAUDE_BIN"]
    found = glob.glob(os.path.expanduser(
        "~/.vscode/extensions/anthropic.claude-code-*/resources/native-binary/claude"))
    version = lambda p: [int(x) for x in re.findall(r"claude-code-(\d+)\.(\d+)\.(\d+)", p)[0]]
    if found:
        return max(found, key=version)
    return "claude"


def clean_env():
    """A fresh environment, so the headless session does not attach to the parent session."""
    keep = ("HOME", "PATH", "USER", "LOGNAME", "LANG", "LC_ALL", "SHELL", "TMPDIR", "XDG_CONFIG_HOME")
    env = {k: os.environ[k] for k in keep if k in os.environ}
    env.update(TERM="dumb", NO_COLOR="1")
    return env


def snapshot(watch):
    state = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = Path(dirpath) / name
            rel = path.relative_to(ROOT).as_posix()
            if watch and not any(rel == w or rel.startswith(w.rstrip("/") + "/") for w in watch):
                continue
            try:
                state[rel] = hashlib.sha1(path.read_bytes()).hexdigest()
            except OSError:
                continue
    return state


def changes(before, after):
    out = [f"`{p}` (added)" for p in sorted(after.keys() - before.keys())]
    out += [f"`{p}` (changed)" for p in sorted(p for p in after.keys() & before.keys() if after[p] != before[p])]
    out += [f"`{p}` (deleted)" for p in sorted(before.keys() - after.keys())]
    return out


def fenced(text, lang):
    longest = max((len(m) for m in re.findall(r"`+", text)), default=0)
    fence = "`" * max(4, longest + 1)
    return f"{fence}{lang}\n{text.rstrip()}\n{fence}"


def append_entry(label, kind, meta, input_text, input_from, output_text, output_from, raw=None):
    LOG_DIR.mkdir(exist_ok=True)
    with open(LOCK, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        existing = HISTORY.read_text(encoding="utf-8") if HISTORY.exists() else ""
        number = len(re.findall(r"^### H-\d+", existing, re.M)) + 1
        entry_id = f"H-{number:03d}"
        now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        lines = [f"### {entry_id} · {now} · {kind} · {label}", ""]
        lines += [f"- **{k}:** {v}" for k, v in meta]
        lines += ["", f"**Input** ({input_from}):", "", fenced(scrub(input_text), "text"), "",
                  f"**Output** ({output_from}):", "", fenced(scrub(output_text or "(empty)"), "markdown"), "", ""]
        with open(HISTORY, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines))
        if raw is not None:
            RUNS.mkdir(exist_ok=True)
            (RUNS / f"{entry_id}.json").write_text(scrub(json.dumps(raw, indent=2)), encoding="utf-8")
        qa.write_qa()
    return entry_id


def cmd_run(args):
    prompt = args.prompt if args.prompt is not None else Path(args.prompt_file).read_text(encoding="utf-8")
    claude = find_claude()
    version = subprocess.run([claude, "--version"], capture_output=True, text=True, env=clean_env()).stdout.strip()
    command = [claude, "-p", *FLAGS] + (["--resume", args.resume] if args.resume else [])
    before = snapshot(args.watch)
    started = time.time()
    proc = subprocess.run(command, input=prompt, capture_output=True, text=True, cwd=ROOT,
                          env=clean_env(), timeout=args.timeout)
    elapsed = time.time() - started
    after = snapshot(args.watch)
    try:
        raw = json.loads(proc.stdout.strip().splitlines()[-1])
    except (json.JSONDecodeError, IndexError):
        raw = {"is_error": True, "result": proc.stdout[-4000:] + "\n--- stderr ---\n" + proc.stderr[-4000:]}
    session = raw.get("session_id") or args.resume or "?"
    cost = raw.get("total_cost_usd")
    usage = raw.get("usage") or {}
    answered = qa.previous_entry(args.resume) if args.resume else None
    meta = [
        ("Session", f"`{session}` ({'resumed' if args.resume else 'new'})"),
        *([("Answers", f"the question at the end of {answered} (see [QA.md](QA.md))")] if answered else []),
        ("Took", f"{elapsed / 60:.1f} min · {raw.get('num_turns', '?')} model turns"
                 + (f" · est. ${cost:.2f}" if isinstance(cost, (int, float)) else "")
                 + (f" · output tokens {usage.get('output_tokens'):,}" if isinstance(usage.get("output_tokens"), int) else "")),
        ("Ran", f"Claude Code {version} headless, model `{MODEL}`, effort `{EFFORT}`, permission mode `acceptEdits`"),
        ("Result", "error" if raw.get("is_error") or proc.returncode else "ok"),
        ("Files changed", ", ".join(changes(before, after)) or "none"),
    ]
    entry = append_entry(args.label, "EGM session turn", meta, prompt,
                         "sent by the orchestrating Claude session", raw.get("result", ""),
                         "final reply of the headless session", raw)
    print(f"SESSION {session}\nENTRY {entry}\n")
    print(raw.get("result", ""))
    return 0 if not raw.get("is_error") and proc.returncode == 0 else 1


def cmd_note(args):
    meta = [("Actor", args.actor)]
    if args.watch:
        meta.append(("Scope", ", ".join(f"`{w}`" for w in args.watch)))
    entry = append_entry(args.label, "orchestrator action", meta,
                         Path(args.input_file).read_text(encoding="utf-8"), "what was asked or run",
                         Path(args.output_file).read_text(encoding="utf-8"), "what came back")
    print(f"ENTRY {entry}")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="cmd", required=True)
    run = sub.add_parser("run")
    run.add_argument("--label", required=True)
    group = run.add_mutually_exclusive_group(required=True)
    group.add_argument("--prompt")
    group.add_argument("--prompt-file")
    run.add_argument("--resume")
    run.add_argument("--watch", nargs="*", default=[])
    run.add_argument("--timeout", type=int, default=3 * 3600)
    note = sub.add_parser("note")
    note.add_argument("--label", required=True)
    note.add_argument("--actor", required=True)
    note.add_argument("--input-file", required=True)
    note.add_argument("--output-file", required=True)
    note.add_argument("--watch", nargs="*", default=[])
    args = parser.parse_args()
    return cmd_run(args) if args.cmd == "run" else cmd_note(args)


if __name__ == "__main__":
    sys.exit(main())
