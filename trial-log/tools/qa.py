#!/usr/bin/env python3
"""Write trial-log/QA.md: each question an EGM session asked, paired with the answer it got.

  python3 trial-log/tools/qa.py

Reads trial-log/HISTORY.md. In a session, the reply of turn N ends with EGM's question or gate,
and the input of turn N+1 is the answer. egm.py calls write_qa() after every turn, so QA.md
stays current. Only EGM session turns are paired. Orchestrator notes are left out.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HISTORY = ROOT / "trial-log" / "HISTORY.md"
QA = ROOT / "trial-log" / "QA.md"
ENTRY_RE = re.compile(r"^### (H-\d+) · ([^·]+) · ([^·]+) · (.+)$", re.M)
SESSION_RE = re.compile(r"\*\*Session:\*\* `([^`]+)`")
BLOCK_RE = r"\*\*{name}\*\*[^\n]*\n\n(`{{4,}})[a-z]*\n(.*?)\n\1"
QUESTION_HINT = re.compile(r"(?i)(\*\*(gate|question)|^(gate|question)\b|how would you like to proceed|\?\s*$)")


def entries():
    text = HISTORY.read_text(encoding="utf-8") if HISTORY.exists() else ""
    marks = list(ENTRY_RE.finditer(text))
    for i, mark in enumerate(marks):
        chunk = text[mark.end(): marks[i + 1].start() if i + 1 < len(marks) else len(text)]
        session = SESSION_RE.search(chunk)
        inp = re.search(BLOCK_RE.format(name="Input"), chunk, re.S)
        out = re.search(BLOCK_RE.format(name="Output"), chunk, re.S)
        yield {"id": mark.group(1), "time": mark.group(2).strip(), "kind": mark.group(3).strip(),
               "label": mark.group(4).strip(), "session": session.group(1) if session else None,
               "input": inp.group(2) if inp else "", "output": out.group(2) if out else ""}


def question_of(output):
    """The closing question or gate of a reply: from its last question-like line to the end."""
    lines = output.rstrip().splitlines()
    for i in range(len(lines) - 1, -1, -1):
        if QUESTION_HINT.search(lines[i]):
            start = i
            # Include the heading line and its options when the question is a short gate block.
            while start > 0 and lines[start - 1].strip() and len(lines) - start < 25:
                start -= 1
            return "\n".join(lines[start:]).strip()
    return None


def previous_entry(session_id):
    last = None
    for entry in entries():
        if entry["session"] == session_id and entry["kind"] == "EGM session turn":
            last = entry["id"]
    return last


def quote(text):
    return "\n".join("> " + line if line.strip() else ">" for line in text.strip().splitlines())


def write_qa():
    by_session = {}
    for entry in entries():
        if entry["kind"] == "EGM session turn" and entry["session"]:
            by_session.setdefault(entry["session"], []).append(entry)
    out = ["# EGM questions and the answers given", "",
           "Generated from [HISTORY.md](HISTORY.md) by `trial-log/tools/qa.py`. Do not edit by hand. "
           "In each session, the reply of one turn ends with EGM's question, and the input of the "
           "next turn is the answer. The answers were given by the orchestrating Claude session "
           "as the user's delegate.", ""]
    for session, turns in by_session.items():
        out += [f"## Session `{session[:8]}`: {turns[0]['label']}", ""]
        for n, (asked, answered) in enumerate(zip(turns, turns[1:] + [None]), start=1):
            question = question_of(asked["output"])
            status = (f" · answered in {answered['id']}" if answered
                      else " · **waiting for an answer**" if question else " · no question asked")
            out += [f"### Q{n} · asked in {asked['id']} ({asked['time']}){status}", "",
                    "**EGM asked:**", "", quote(question or "(this reply asks no question)"), ""]
            if answered:
                out += ["**Answer given:**", "", quote(answered["input"]), ""]
    QA.write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    write_qa()
    print(f"Wrote {QA.relative_to(ROOT)}")
