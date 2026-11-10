#!/usr/bin/env python3
"""Write trial-log/timeline.md from this project's Claude Code transcripts.

  python3 trial-log/tools/timeline.py                 rewrite trial-log/timeline.md
  python3 trial-log/tools/timeline.py --since ISO     also print what happened after ISO
                                                      (for example the time of the last step tag)

Claude Code keeps one JSONL transcript per session in
~/.claude/projects/<project path, every non-alphanumeric character replaced by "-">/
and one file per subagent in <session id>/subagents/. This script reads them and lists
prompts, slash commands, skills, questions and the answers given, subagents (type, model,
tokens, files they read), files written, notable shell commands, and token totals.

It only reads transcripts and writes trial-log/timeline.md. Standard library only.
Transcripts are deleted after `cleanupPeriodDays` (30 by default), so run it before then.
"""
import argparse
import json
import os
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

from redact import scrub

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "trial-log" / "timeline.md"
ACTIVE_GAP_S = 300  # a gap longer than this between two events counts as idle time
NOTABLE_BASH = re.compile(  # matched at the start of each command in a shell line
    r"^(?:\S*/)?(?:git (?:commit|tag|init)|(?:claude|\"?\$\{?\w+\}?\"?) plugin|mvnw?|"
    r"npm (?:run|test|ci|install)|npx|docker(?:-compose| compose)?)\b"
)
SEGMENT_SPLIT = re.compile(r"&&|\|\||;|\|")
LEADING_ASSIGN = re.compile(r"^(?:[A-Za-z_][A-Za-z0-9_]*=\S*\s+)+")
ANSWER_RE = re.compile(r'="([^"]*)"')
FILE_TOOLS = {"Write": "wrote", "Edit": "edited", "NotebookEdit": "edited"}
CMD_RE = re.compile(r"<command-name>(.*?)</command-name>", re.S)
ARGS_RE = re.compile(r"<command-args>(.*?)</command-args>", re.S)
USAGE_KEYS = ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")
EPOCH = datetime.min.replace(tzinfo=timezone.utc)


def parse_ts(value):
    if not isinstance(value, str):
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def short(text, limit=140):
    text = " ".join(str(text).split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def cell(text):
    return str(text).replace("|", "\\|")


def rel(path):
    """Show a path relative to the project, to an installed plugin, or to home."""
    if not path:
        return "?"
    p = str(path)
    root = str(ROOT) + os.sep
    if p.startswith(root):
        return p[len(root):]
    m = re.search(r"/\.claude/plugins/cache/[^/]+/([^/]+)/([^/]+)/(.*)", p)
    if m:
        return f"<plugin {m.group(1)} {m.group(2)}>/{m.group(3)}"
    home = str(Path.home())
    return "~" + p[len(home):] if p.startswith(home) else p


def read_jsonl(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(row, dict):
                rows.append(row)
    return rows


def as_dict(value):
    return value if isinstance(value, dict) else {}


def text_of(content):
    """Return (text, has_attachment) for a message content value."""
    if isinstance(content, str):
        return content, False
    parts, attached = [], False
    for block in content if isinstance(content, list) else []:
        if not isinstance(block, dict):
            continue
        if block.get("type") == "text":
            parts.append(block.get("text", ""))
        elif block.get("type") in ("document", "image"):
            attached = True
    return "\n".join(parts), attached


def notable_command(command):
    """Return the first build, git, plugin or docker command in a shell line, if any."""
    for line in command.splitlines():
        for segment in SEGMENT_SPLIT.split(line):
            segment = LEADING_ASSIGN.sub("", segment.strip())
            if NOTABLE_BASH.search(segment):
                return segment
    return None


IDE_CONTEXT = re.compile(r"<(ide_[a-z_]+|system-reminder)>.*?</\1>", re.S)


def prompt_event(text, attached):
    stripped = IDE_CONTEXT.sub("", text)
    if stripped != text:
        text, attached = stripped, True
    match = CMD_RE.search(text)
    if match:
        args = ARGS_RE.search(text)
        label = match.group(1).strip()
        if args and args.group(1).strip():
            label += " " + args.group(1).strip()
        return "command", f"`{short(label, 120)}`"
    label = short(text) if text.strip() else "(no text)"
    return "prompt", label + (" (+ attachment)" if attached else "")


def parse_file(path, owner):
    """Parse one transcript. `owner` is "main" or the agent id that wrote it."""
    info = {"events": [], "usage": {}, "stamps": [], "version": None, "entrypoint": None,
            "spawned": [], "reads": [], "tools": Counter()}
    pending = {}
    for row in read_jsonl(path):
        ts = parse_ts(row.get("timestamp"))
        if ts:
            info["stamps"].append(ts)
        info["version"] = info["version"] or row.get("version")
        info["entrypoint"] = info["entrypoint"] or row.get("entrypoint")
        kind = row.get("type")
        msg = as_dict(row.get("message"))
        if kind == "assistant":
            usage = as_dict(msg.get("usage"))
            if usage:
                key = msg.get("id") or row.get("uuid")
                prev = info["usage"].get(key)
                if prev is None or (usage.get("output_tokens") or 0) >= (prev[1].get("output_tokens") or 0):
                    info["usage"][key] = (ts, usage, msg.get("model"))
            for block in msg.get("content") or []:
                if not isinstance(block, dict) or block.get("type") != "tool_use":
                    continue
                name, args, tool_id = block.get("name"), as_dict(block.get("input")), block.get("id")
                info["tools"][name] += 1
                if name == "Agent":
                    pending[tool_id] = ("agent", {"type": args.get("subagent_type") or "general-purpose",
                                                  "description": args.get("description", ""), "ts": ts})
                elif name == "AskUserQuestion":
                    questions = args.get("questions") or [{}]
                    first = questions[0] if isinstance(questions[0], dict) else {}
                    pending[tool_id] = ("question", {"q": first.get("question", ""), "ts": ts})
                elif name == "Skill":
                    extra = f" {short(args['args'], 80)}" if args.get("args") else ""
                    info["events"].append((ts, "skill", f"`{args.get('skill', '?')}`{extra}"))
                elif name in FILE_TOOLS:
                    pending[tool_id] = ("event", (ts, FILE_TOOLS[name], f"`{rel(args.get('file_path'))}`"))
                elif name == "Read":
                    info["reads"].append(rel(args.get("file_path")))
                elif name == "Bash":
                    command = notable_command(args.get("command") or "")
                    if command:
                        pending[tool_id] = ("event", (ts, "shell", f"`{short(command, 110)}`"))
        elif kind == "user" and not row.get("isMeta"):
            content = msg.get("content")
            raw = row.get("toolUseResult")
            result = as_dict(raw)
            for block in content if isinstance(content, list) else []:
                if not isinstance(block, dict) or block.get("type") != "tool_result":
                    continue
                what, payload = pending.pop(block.get("tool_use_id"), (None, None))
                failed = bool(block.get("is_error"))
                outcome = " (denied)" if failed and "denied" in str(raw).lower() else " (failed)" if failed else ""
                if what == "event":
                    when, label, text = payload
                    info["events"].append((when, label + outcome, text))
                elif what == "agent":
                    payload.update(id=result.get("agentId"), type=result.get("agentType") or payload["type"],
                                   model=result.get("resolvedModel"), ms=result.get("totalDurationMs"),
                                   tokens=result.get("totalTokens"), tool_uses=result.get("totalToolUseCount"),
                                   status=outcome.strip(" ()") or result.get("status"), parent=owner)
                    info["spawned"].append(payload)
                elif what == "question":
                    answers = as_dict(result.get("answers"))
                    answer = next(iter(answers.values()), None)
                    if answer is None and not failed:
                        reply, _ = text_of(block.get("content"))
                        found = ANSWER_RE.search(reply)
                        answer = found.group(1) if found else (reply or None)
                    text = short(payload["q"], 120) + (f" → **{short(answer, 80)}**" if answer else outcome)
                    info["events"].append((payload["ts"], "question", text))
            origin = as_dict(row.get("origin"))
            # "human" is a person typing; "sdk" is a prompt sent to a headless (claude -p) session.
            if origin.get("kind") == "human" or row.get("turnOrigin") in ("human", "sdk"):
                info["events"].append((ts, *prompt_event(*text_of(content))))
        elif kind == "attachment":
            att = as_dict(row.get("attachment"))
            if att.get("type") == "queued_command" and (as_dict(att.get("origin")).get("kind") == "human"
                                                        or att.get("humanTurn")):
                what, label = prompt_event(*text_of(att.get("prompt")))
                info["events"].append((parse_ts(att.get("timestamp")) or ts, what + " (mid-turn)", label))
    for what, payload in pending.values():
        if what == "agent":
            payload.update(id=None, parent=owner, status="no result recorded")
            info["spawned"].append(payload)
        elif what == "event":
            when, label, text = payload
            info["events"].append((when, label + " (no result)", text))
    return info


def transcript_dirs():
    config = Path(os.environ.get("CLAUDE_CONFIG_DIR", Path.home() / ".claude")).expanduser()
    projects = config / "projects"
    prefix = re.sub(r"[^A-Za-z0-9]", "-", str(ROOT))
    if not projects.is_dir():
        return []
    # Also pick up sessions started in a subfolder of the project (e.g. cce-admin-service/).
    return sorted(p for p in projects.iterdir() if p.is_dir() and (p.name == prefix or p.name.startswith(prefix + "-")))


def load_sessions(dirs):
    sessions = []
    for folder in dirs:
        for main_file in sorted(folder.glob("*.jsonl")):
            sid = main_file.stem
            agents = {}
            sub = folder / sid / "subagents"
            for agent_file in sorted(sub.glob("*.jsonl")) if sub.is_dir() else []:
                aid = agent_file.stem.removeprefix("agent-")
                agents[aid] = parse_file(agent_file, aid)
            main = parse_file(main_file, "main")
            if main["stamps"]:
                sessions.append({"id": sid, "main": main, "agents": agents})
    sessions.sort(key=lambda s: min(s["main"]["stamps"]))
    return sessions


def usage_total(infos, since=None):
    total = Counter()
    for info in infos:
        for ts, usage, _model in info["usage"].values():
            if since and (ts is None or ts < since):
                continue
            for key in USAGE_KEYS:
                if isinstance(usage.get(key), int):
                    total[key] += usage[key]
    return total


def spawned(session):
    rows = list(session["main"]["spawned"])
    for info in session["agents"].values():
        rows.extend(info["spawned"])
    return rows


def stamps(session, since=None):
    all_ts = list(session["main"]["stamps"])
    for info in session["agents"].values():
        all_ts.extend(info["stamps"])
    return sorted(t for t in all_ts if not since or t >= since)


def active_seconds(times):
    return sum(min(g, ACTIVE_GAP_S) if g <= ACTIVE_GAP_S else 0
               for g in ((b - a).total_seconds() for a, b in zip(times, times[1:])))


def fmt_dur(seconds):
    seconds = int(seconds or 0)
    hours, rest = divmod(seconds, 3600)
    minutes, secs = divmod(rest, 60)
    return f"{hours}h {minutes:02d}m" if hours else (f"{minutes}m {secs:02d}s" if minutes else f"{secs}s")


def fmt_tokens(total):
    return " / ".join(f"{total[k]:,}" for k in USAGE_KEYS)


def collapse(events):
    """Merge consecutive identical events, e.g. several edits of one file."""
    merged = []
    for ts, kind, text in sorted(events, key=lambda e: e[0] or EPOCH):
        if merged and merged[-1][1] == kind and merged[-1][2] == text:
            merged[-1][3] += 1
        else:
            merged.append([ts, kind, text, 1])
    return merged


def render(sessions, dirs):
    out = ["# Trial timeline", "",
           "Generated by `trial-log/tools/timeline.py` from Claude Code session transcripts. Do not edit "
           "by hand. Times are UTC. *Span* is first to last event. *Active* adds up only the gaps of "
           f"{ACTIVE_GAP_S // 60} minutes or less. Token columns are input / cache write / cache read / "
           "output, summed over API calls. Subagent tokens are the totals Claude Code reports per subagent.",
           "", "Transcript folders: " + ", ".join(f"`{rel(d)}`" for d in dirs), "",
           "## Sessions", "",
           "| Session | Started | Span | Active | Claude Code | Prompts | Main-thread tokens | Subagents | Subagent tokens |",
           "|---|---|---|---|---|---|---|---|---|"]
    kinds = {"claude-vscode": "VS Code", "sdk-cli": "headless"}
    for s in sessions:
        times = stamps(s)
        prompts = sum(1 for e in s["main"]["events"] if e[1].startswith(("prompt", "command")))
        subs = spawned(s)
        sub_tokens = sum(a.get("tokens") or 0 for a in subs)
        out.append(f"| [`{s['id'][:8]}`](#session-{s['id'][:8]}) | {times[0]:%Y-%m-%d %H:%M} | "
                   f"{fmt_dur((times[-1] - times[0]).total_seconds())} | {fmt_dur(active_seconds(times))} | "
                   f"{s['main']['version'] or '?'} ({kinds.get(s['main']['entrypoint'], s['main']['entrypoint'] or '?')}) | "
                   f"{prompts} | {fmt_tokens(usage_total([s['main']]))} | "
                   f"{len(subs)} | {sub_tokens:,} |")
    for s in sessions:
        times = stamps(s)
        out += ["", f"## Session {s['id'][:8]}", "",
                f"Full id `{s['id']}`, {times[0]:%Y-%m-%d %H:%M} to {times[-1]:%H:%M} UTC. "
                f"Tool calls in the main thread: " +
                (", ".join(f"{name} {n}" for name, n in s["main"]["tools"].most_common()) or "none") + ".",
                "", "| Time | Event | Detail |", "|---|---|---|"]
        events = list(s["main"]["events"])
        for a in spawned(s):
            detail = f"`{a['type']}`"
            if a.get("model"):
                detail += f" ({a['model']})"
            detail += f": {short(a.get('description', ''), 80)}"
            if a.get("tokens") is not None:
                detail += f". {a['tokens']:,} tokens, {fmt_dur((a.get('ms') or 0) / 1000)}"
            if a.get("parent") != "main":
                detail += f", spawned by agent `{a['parent']}`"
            events.append((a.get("ts"), "subagent", detail))
        for ts, kind, text, count in collapse(events):
            when = f"{ts:%H:%M:%S}" if ts else "?"
            out.append(f"| {when} | {kind} | {cell(text)}{f' (×{count})' if count > 1 else ''} |")
        subs = spawned(s)
        if subs:
            out += ["", "Subagents:", "",
                    "| Agent | Type | Model | Spawned by | Status | Duration | Tokens | Tool uses | Files read |",
                    "|---|---|---|---|---|---|---|---|---|"]
            for a in subs:
                reads = s["agents"].get(a.get("id") or "", {}).get("reads", [])
                files = ", ".join(f"`{cell(r)}`" for r in dict.fromkeys(reads)) or "none"
                tokens = f"{a['tokens']:,}" if isinstance(a.get("tokens"), int) else "?"
                out.append(f"| `{a.get('id') or '?'}` | `{a['type']}` | {a.get('model') or '?'} | "
                           f"{a.get('parent')} | {a.get('status') or '?'} | {fmt_dur((a.get('ms') or 0) / 1000)} | "
                           f"{tokens} | {a.get('tool_uses') if a.get('tool_uses') is not None else '?'} | {files} |")
    return "\n".join(out) + "\n"


def summary_since(sessions, since):
    lines = [f"Activity since {since:%Y-%m-%d %H:%M:%S} UTC"]
    touched = [s for s in sessions if stamps(s, since)]
    if not touched:
        return "\n".join(lines + ["  nothing recorded"])
    span = active = 0
    main_total = Counter()
    sub_rows, prompts, writes = [], [], []
    for s in touched:
        times = stamps(s, since)
        span += (times[-1] - times[0]).total_seconds()
        active += active_seconds(times)
        main_total += usage_total([s["main"]], since)
        sub_rows += [a for a in spawned(s) if a.get("ts") and a["ts"] >= since]
        for ts, kind, text in s["main"]["events"]:
            if ts and ts >= since:
                if kind.startswith(("prompt", "command")):
                    prompts.append(f"{ts:%H:%M} {kind}: {text}")
                elif kind in ("wrote", "edited"):
                    writes.append(text)
    types = Counter(a["type"] for a in sub_rows)
    lines += [
        "  sessions: " + ", ".join(s["id"][:8] for s in touched),
        f"  span: {fmt_dur(span)}; active: {fmt_dur(active)}",
        f"  main-thread tokens (input / cache write / cache read / output): {fmt_tokens(main_total)}",
        f"  subagents: {len(sub_rows)}" + (" (" + ", ".join(f"{t} ×{n}" for t, n in types.most_common()) + ")" if types else ""),
        f"  subagent tokens: {sum(a.get('tokens') or 0 for a in sub_rows):,}",
        "  files written: " + (", ".join(dict.fromkeys(writes)) or "none"),
        "  prompts:",
    ] + [f"    {p}" for p in prompts]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--since", help="ISO time; print a summary of activity after it")
    parser.add_argument("--transcripts", nargs="*", type=Path, help="transcript folders to read instead of the default")
    args = parser.parse_args()
    dirs = args.transcripts or transcript_dirs()
    sessions = load_sessions(dirs)
    if not sessions:
        print(f"No transcripts found for {ROOT}", file=sys.stderr)
        return 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(scrub(render(sessions, dirs)), encoding="utf-8")
    print(f"Wrote {rel(OUT)}: {len(sessions)} session(s), "
          f"{sum(len(spawned(s)) for s in sessions)} subagent(s).")
    if args.since:
        since = parse_ts(args.since)
        if since is None:
            print(f"Could not parse --since {args.since!r}", file=sys.stderr)
            return 2
        if since.tzinfo is None:
            since = since.replace(tzinfo=timezone.utc)
        print(summary_since(sessions, since))
    return 0


if __name__ == "__main__":
    sys.exit(main())
