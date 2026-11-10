"""Remove credentials from text before it is written to the trial record.

Generic patterns only, so this file never contains a real secret:
  KEY_PASSWORD=value, password: value, secret = value, token: value  -> value redacted
  ${DATABASE_PASSWORD:default}                                       -> default redacted
  scheme://user:password@host                                        -> password redacted
"""
import re

PATTERNS = [
    (re.compile(r"(\$\{[A-Za-z0-9_]*(?:PASSWORD|SECRET|TOKEN)[A-Za-z0-9_]*:)([^}]+)(\})"), r"\1<redacted>\3"),
    (re.compile(r"(\b[a-z][a-z0-9+.-]*://[^:/\s@]+:)([^@\s/]+)(@)"), r"\1<redacted>\3"),
    (re.compile(r"(?i)(\b[a-z0-9_]*(?:password|passwd|secret|token)[a-z0-9_]*\s*[=:]\s*)"
                r"(?![<${\s'\"`])([^\s'\"`,;)]+)"), r"\1<redacted>"),
]


def scrub(text):
    for pattern, replacement in PATTERNS:
        text = pattern.sub(replacement, text)
    return text
