#!/usr/bin/env python3
"""Collapse/align Kotlin one-liner style afterFileEdit: require/check, soft wraps, braces, when arrows.

Goal: one statement per line. Soft-wrapped single statements get joined. Single-statement
lambda / when-arm `{ … }` bodies stay on the `{` opener line. if/else/try/catch, fun/class
bodies, and constructor arg lists stay multi-line. Refuses any non-whitespace rewrite.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

CALL_START = re.compile(r"\b(error|requireNotNull|checkNotNull|require|check)\s*\(")
ELVIS_ERROR = re.compile(r"\?:\s*error\s*\(")
# Decl line that ends at `=` — soft wrap of an expression RHS onto following indented lines.
SOFT_ASSIGN_LINE = re.compile(
    r"^(\s*)(?:(?:public|private|protected|internal|open|override|actual|expect|final|abstract)\s+)*"
    r"(?:const\s+)?(?:val|var)\b.*=\s*$"
)
# when-arm line ending at `->` (no `{` on the line — skip `{ x ->` lambdas).
SOFT_ARROW_LINE = re.compile(r"^(\s*).*->\s*$")
# Lambda opener `{ x ->` — not a when-arm for soft-join or arrow align.
LAMBDA_ARROW = re.compile(r"\{[^{}]*->")


def _scan_string(src: str, i: int) -> int:
    """Return index just past a Kotlin string starting at src[i] ('\"' or '\"\"\"')."""
    n = len(src)
    if src.startswith('"""', i):
        j = i + 3
        while j < n:
            if src.startswith('"""', j):
                return j + 3
            if src[j] == "\\" and j + 1 < n:
                j += 2
                continue
            j += 1
        return n
    quote = src[i]
    j = i + 1
    while j < n:
        c = src[j]
        if c == "\\":
            j += 2
            continue
        if c == quote:
            return j + 1
        j += 1
    return n


def _skip_comment(src: str, i: int) -> int:
    n = len(src)
    if src.startswith("//", i):
        j = i + 2
        while j < n and src[j] != "\n":
            j += 1
        return j
    if src.startswith("/*", i):
        j = src.find("*/", i + 2)
        return n if j < 0 else j + 2
    return i


def _matching(src: str, open_i: int, open_ch: str, close_ch: str) -> int:
    """Index of matching close_ch for open at open_i. Respects strings/comments."""
    n = len(src)
    depth = 0
    i = open_i
    while i < n:
        if src.startswith(("//", "/*"), i):
            i = _skip_comment(src, i)
            continue
        c = src[i]
        if c in "\"'":
            i = _scan_string(src, i)
            continue
        if c == open_ch:
            depth += 1
            i += 1
            continue
        if c == close_ch:
            depth -= 1
            i += 1
            if depth == 0:
                return i - 1
            continue
        i += 1
    return -1


def _flatten_outside_strings(text: str) -> str:
    """Newlines → space; squeeze runs of space/tab outside strings/comments."""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith(("//", "/*"), i):
            # Drop line comments in the flatten path; keep block comments compacted.
            if text.startswith("//", i):
                i = _skip_comment(text, i)
                if i < n and text[i] == "\n":
                    i += 1
                out.append(" ")
                continue
            end = _skip_comment(text, i)
            out.append(" ")
            i = end
            continue
        c = text[i]
        if c in "\"'":
            end = _scan_string(text, i)
            out.append(text[i:end])
            i = end
            continue
        if c in " \t\r\n":
            while i < n and text[i] in " \t\r\n":
                i += 1
            out.append(" ")
            continue
        out.append(c)
        i += 1
    flat = "".join(out).strip()
    # Tighten common call punctuation without touching strings (already atomic).
    flat = re.sub(r"\s+\(", "(", flat)
    flat = re.sub(r"\(\s+", "(", flat)
    flat = re.sub(r"\s+\)", ")", flat)
    flat = re.sub(r"\)\s+\{", ") {", flat)
    flat = re.sub(r"\{\s+", "{ ", flat)
    flat = re.sub(r"\s+\}", " }", flat)
    # Keep trailing commas — dropping them is not whitespace-only.
    return flat


def _call_end(src: str, open_paren: int) -> int | None:
    """Return exclusive end index of require/check call starting at '('."""
    close_paren = _matching(src, open_paren, "(", ")")
    if close_paren < 0:
        return None
    i = close_paren + 1
    n = len(src)
    while i < n and src[i] in " \t":
        i += 1
    if i < n and src[i] == "\n":
        # allow newline before message lambda
        j = i + 1
        while j < n and src[j] in " \t":
            j += 1
        if j < n and src[j] == "{":
            i = j
    if i < n and src[i] == "{":
        close_brace = _matching(src, i, "{", "}")
        if close_brace < 0:
            return None
        return close_brace + 1
    return close_paren + 1


def collapse_require_check(src: str) -> str:
    """Return source with safe multi-line require/check calls collapsed."""
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith(("//", "/*"), i):
            end = _skip_comment(src, i)
            out.append(src[i:end])
            i = end
            continue
        if src[i] in "\"'":
            end = _scan_string(src, i)
            out.append(src[i:end])
            i = end
            continue

        m = CALL_START.match(src, i)
        if not m:
            out.append(src[i])
            i += 1
            continue

        open_paren = m.end() - 1  # index of '('
        end = _call_end(src, open_paren)
        if end is None:
            out.append(src[i])
            i += 1
            continue

        chunk = src[m.start() : end]
        if "\n" not in chunk:
            out.append(chunk)
            i = end
            continue

        condition = src[open_paren + 1 : _matching(src, open_paren, "(", ")")]
        # Multi-line condition with a lambda/block — leave alone (statement bodies).
        if "\n" in condition and "{" in condition:
            out.append(chunk)
            i = end
            continue

        flat = _flatten_outside_strings(chunk)
        if "\n" in flat:
            out.append(chunk)
            i = end
            continue

        out.append(flat)
        i = end

    return "".join(out)


def _flatten_to_one_line(text: str) -> str:
    """Collapse newlines/indent to spaces (no string parse — safe for nested template quotes)."""
    flat = re.sub(r"[ \t]*\r?\n[ \t]*", " ", text).strip()
    flat = re.sub(r"\(\s+", "(", flat)
    flat = re.sub(r"\s+\)", ")", flat)
    return flat


def _indent_width(line: str) -> int:
    return len(line) - len(line.lstrip(" \t"))


def _contains_brace_outside_strings(text: str) -> bool:
    i = 0
    n = len(text)
    while i < n:
        if text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if text[i] in "\"'":
            i = _scan_string(text, i)
            continue
        if text[i] == "{":
            return True
        i += 1
    return False


def _ends_with_soft_binop(bare: str) -> bool:
    """True if line soft-wraps on a trailing `&&` / `||` / `+` (not `++`)."""
    s = bare.rstrip()
    if s.endswith(("&&", "||")):
        return True
    return s.endswith("+") and not s.endswith("++")


def _count_bool_ops(text: str) -> tuple[int, int]:
    """Count `&&` and `||` outside strings/comments."""
    ands = 0
    ors = 0
    i = 0
    n = len(text)
    while i < n - 1:
        if text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if text[i] in "\"'":
            i = _scan_string(text, i)
            continue
        if text.startswith("&&", i):
            ands += 1
            i += 2
            continue
        if text.startswith("||", i):
            ors += 1
            i += 2
            continue
        i += 1
    return ands, ors


def _collect_indented_continuations(lines: list[str], start: int, base_indent: int) -> tuple[list[str], int]:
    """Return (stripped continuation lines, index after last consumed)."""
    parts: list[str] = []
    j = start
    while j < len(lines):
        nxt_bare = lines[j].rstrip("\r\n")
        if not nxt_bare.strip():
            break
        if _indent_width(nxt_bare) <= base_indent:
            break
        parts.append(nxt_bare.strip())
        j += 1
    return parts, j


def _join_soft_wrap(bare: str, nl: str, parts: list[str]) -> str | None:
    """Flatten continuation parts onto bare; None if unsafe (braces / long bool chains / residual newline)."""
    if not parts:
        return None
    if any(part.lstrip().startswith("//") for part in parts):
        return None
    rhs = " ".join(parts)
    if _contains_brace_outside_strings(rhs):
        return None
    # Leave long boolean chains multi-line (e.g. equals with many fields).
    ands, ors = _count_bool_ops(f"{bare.rstrip()} {rhs}")
    if ands > 3 or ors > 3:
        return None
    flat_rhs = _flatten_outside_strings(rhs)
    if "\n" in flat_rhs or not flat_rhs.strip():
        return None
    return f"{bare.rstrip()} {flat_rhs}{nl}"


def collapse_soft_wrapped_assignments(src: str) -> str:
    """Collapse `val`/`var`/`const val` with a break right after `=` and expression-only RHS."""
    lines = src.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        bare = line.rstrip("\r\n")
        nl = line[len(bare) :]
        if not SOFT_ASSIGN_LINE.match(bare):
            out.append(line)
            i += 1
            continue

        parts, j = _collect_indented_continuations(lines, i + 1, _indent_width(bare))
        joined = _join_soft_wrap(bare, nl, parts)
        if joined is None:
            out.append(line)
            i += 1
            continue
        out.append(joined)
        i = j

    return "".join(out)


def collapse_soft_wrapped_when_arms(src: str) -> str:
    """Collapse `->\\n <expr>` when-arms onto the arrow line (not `{ x ->` lambdas)."""
    lines = src.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        bare = line.rstrip("\r\n")
        nl = line[len(bare) :]
        if not SOFT_ARROW_LINE.match(bare) or "{" in bare:
            out.append(line)
            i += 1
            continue

        parts, j = _collect_indented_continuations(lines, i + 1, _indent_width(bare))
        if not parts:
            out.append(line)
            i += 1
            continue

        if len(parts) == 1:
            call_match = CALL_START.match(parts[0])
            if call_match:
                call = parts[0]
                end = _call_end(call, call_match.end() - 1)
                if end is not None and end == len(call):
                    out.append(f"{bare.rstrip()} {call}{nl}")
                    i = j
                    continue

        joined = _join_soft_wrap(bare, nl, parts)
        if joined is not None:
            out.append(joined)
            i = j
            continue

        out.append(line)
        i += 1

    return "".join(out)


def collapse_soft_wrapped_binops(src: str) -> str:
    """Collapse soft wraps after trailing `&&` / `||` / `+` (expression-only continuations)."""
    lines = src.splitlines(keepends=True)
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        bare = line.rstrip("\r\n")
        nl = line[len(bare) :]
        if not _ends_with_soft_binop(bare):
            out.append(line)
            i += 1
            continue

        parts, j = _collect_indented_continuations(lines, i + 1, _indent_width(bare))
        joined = _join_soft_wrap(bare, "", parts)
        if joined is None:
            out.append(line)
            i += 1
            continue
        # Pull a dangling closer up: `if (a &&\n  b\n) {` → `if (a && b) {`
        if j < len(lines):
            closer = lines[j].rstrip("\r\n").strip()
            if closer in (")", ") {", ");", "),"):
                joined = f"{joined.rstrip()}{closer}"
                j += 1
        out.append(f"{joined}{nl}")
        i = j

    return "".join(out)


def _arrow_index(bare: str) -> int | None:
    """Index of the first `->` outside strings/comments, or None."""
    i = 0
    n = len(bare)
    while i < n - 1:
        if bare.startswith(("//", "/*"), i):
            i = _skip_comment(bare, i)
            continue
        if bare[i] in "\"'":
            i = _scan_string(bare, i)
            continue
        if bare.startswith("->", i):
            return i
        i += 1
    return None


def _is_when_arm_arrow_line(bare: str) -> bool:
    """True for a when-arm line with `->` (not a `{ x ->` lambda opener)."""
    if not bare.strip() or LAMBDA_ARROW.search(bare):
        return False
    return _arrow_index(bare) is not None


def _pad_when_arm(bare: str, arrow_col: int) -> str:
    """Rewrite arm so `->` starts at arrow_col (absolute index in the line)."""
    arrow = _arrow_index(bare)
    if arrow is None:
        return bare
    lhs = bare[:arrow].rstrip()
    rhs = bare[arrow + 2 :].lstrip()
    pad = arrow_col - len(lhs)
    if pad < 1:
        pad = 1
    if rhs:
        return f"{lhs}{' ' * pad}-> {rhs}"
    return f"{lhs}{' ' * pad}->"


def align_when_arrows(src: str) -> str:
    """Pad spaces before `->` so arrows line up within each when-arm group (incl. nested)."""
    lines = src.splitlines(keepends=True)
    groups: list[list[int]] = []

    def collect(start: int, end: int) -> None:
        i = start
        while i < end:
            bare = lines[i].rstrip("\r\n")
            if not _is_when_arm_arrow_line(bare):
                i += 1
                continue
            base_indent = _indent_width(bare)
            arms: list[int] = []
            j = i
            while j < end:
                b = lines[j].rstrip("\r\n")
                if not b.strip():
                    break
                ind = _indent_width(b)
                if ind < base_indent:
                    break
                if ind == base_indent:
                    stripped = b.strip()
                    # Brace-arm closer sits at arm indent — keep scanning for later arms.
                    if stripped in ("}", "},"):
                        j += 1
                        continue
                    if _is_when_arm_arrow_line(b):
                        arms.append(j)
                        j += 1
                        continue
                    break
                j += 1
            if len(arms) >= 2:
                groups.append(arms)
            p = i + 1
            while p < j:
                b = lines[p].rstrip("\r\n")
                if b.strip() and _indent_width(b) > base_indent:
                    q = p
                    while q < j:
                        bq = lines[q].rstrip("\r\n")
                        if bq.strip() and _indent_width(bq) <= base_indent:
                            break
                        q += 1
                    collect(p, q)
                    p = q
                else:
                    p += 1
            i = j

    collect(0, len(lines))

    bares = [ln.rstrip("\r\n") for ln in lines]
    nls = [ln[len(b) :] for ln, b in zip(lines, bares)]
    for arms in groups:
        arrow_col = max(len(bares[k][: _arrow_index(bares[k])].rstrip()) for k in arms) + 1
        for k in arms:
            bares[k] = _pad_when_arm(bares[k], arrow_col)
    return "".join(b + nl for b, nl in zip(bares, nls))


def collapse_elvis_error(src: str) -> str:
    """Collapse `?: error(...)` (including a break before `?:`) to one line."""
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith(("//", "/*"), i):
            end = _skip_comment(src, i)
            out.append(src[i:end])
            i = end
            continue
        if src[i] in "\"'":
            end = _scan_string(src, i)
            out.append(src[i:end])
            i = end
            continue

        m = ELVIS_ERROR.match(src, i)
        if not m:
            out.append(src[i])
            i += 1
            continue

        # Walk back over indent/newlines between the LHS and `?:`.
        ws_start = i
        while ws_start > 0 and src[ws_start - 1] in " \t\r\n":
            ws_start -= 1
        had_break = "\n" in src[ws_start:i]

        open_paren = m.end() - 1
        close_paren = _matching(src, open_paren, "(", ")")
        if close_paren < 0:
            out.append(src[i])
            i += 1
            continue
        end = close_paren + 1
        call = src[i:end]

        if not had_break and "\n" not in call:
            out.append(call)
            i = end
            continue

        if had_break:
            # Don't join onto a line that ends in a // comment (would comment out `?: error`).
            line_start = src.rfind("\n", 0, ws_start) + 1
            j = line_start
            commented = False
            while j < ws_start:
                if src.startswith("//", j):
                    commented = True
                    break
                if src.startswith("/*", j):
                    j = _skip_comment(src, j)
                    continue
                if src[j] in "\"'":
                    j = _scan_string(src, j)
                    continue
                j += 1
            if commented:
                # Still flatten a multi-line error() body; leave the break before `?:`.
                if "\n" in call:
                    out.append(_flatten_to_one_line(call))
                    i = end
                else:
                    out.append(src[i])
                    i += 1
                continue

            drop = i - ws_start
            while drop > 0 and out:
                out.pop()
                drop -= 1
            out.append(" " + _flatten_to_one_line(call))
        else:
            out.append(_flatten_to_one_line(call))
        i = end

    return "".join(out)


def _body_has_line_comment(text: str) -> bool:
    """True if text has a // comment outside strings."""
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("/*", i):
            i = _skip_comment(text, i)
            continue
        if text.startswith("//", i):
            return True
        if text[i] in "\"'":
            i = _scan_string(text, i)
            continue
        i += 1
    return False


def _read_ident_backward(src: str, end_inclusive: int) -> tuple[str, int]:
    """Return (ident, index_before_ident) for a Kotlin ident/backtick ending at end_inclusive."""
    if end_inclusive < 0:
        return "", end_inclusive
    j = end_inclusive
    if src[j] == "`":
        k = j - 1
        while k >= 0 and src[k] != "`":
            k -= 1
        if k < 0:
            return "", end_inclusive
        return src[k : j + 1], k - 1
    if not (src[j].isalnum() or src[j] == "_"):
        return "", end_inclusive
    k = j
    while k >= 0 and (src[k].isalnum() or src[k] == "_"):
        k -= 1
    return src[k + 1 : j + 1], k


def _skip_ws_comments_backward(src: str, j: int) -> int:
    while j >= 0:
        if src[j] in " \t\r\n":
            j -= 1
            continue
        if j >= 1 and src[j] == "/" and src[j - 1] == "*":
            # end of block comment — find opening
            k = src.rfind("/*", 0, j - 1)
            if k < 0:
                return j
            j = k - 1
            continue
        break
    return j


def _skip_optional_return_type_backward(src: str, j: int) -> int:
    """If `) : Type` sits before `{`, skip `: Type` so j lands on `)`."""
    j = _skip_ws_comments_backward(src, j)
    if j < 0 or src[j] == ")":
        return j
    # Rough Type: identifiers, `.`, `?`, `<>`, `()` nesting — stop at `:` then leave `)` finder to caller.
    # Only treat as return type when a `:` appears with a `)` before it.
    colon = -1
    depth_angle = depth_paren = 0
    k = j
    while k >= 0:
        c = src[k]
        if c in "\"'":
            # shouldn't hit strings often in return types; bail
            return j
        if c == ">" and depth_paren == 0:
            depth_angle += 1
            k -= 1
            continue
        if c == "<" and depth_paren == 0:
            depth_angle -= 1
            k -= 1
            continue
        if c == ")" and depth_angle == 0:
            depth_paren += 1
            k -= 1
            continue
        if c == "(" and depth_angle == 0:
            depth_paren -= 1
            k -= 1
            continue
        if depth_angle == 0 and depth_paren == 0 and c == ":":
            # annotation `@Foo` uses no colon-as-type here; return type colon
            colon = k
            break
        if depth_angle == 0 and depth_paren == 0 and c in "{};=":
            return j
        k -= 1
    if colon < 0:
        return j
    before = _skip_ws_comments_backward(src, colon - 1)
    if before >= 0 and src[before] == ")":
        return before
    return j


def _matching_open(src: str, close_i: int, open_ch: str, close_ch: str) -> int:
    """Index of matching open_ch for close at close_i. Respects strings/comments."""
    depth = 0
    i = close_i
    while i >= 0:
        if i >= 1 and src[i - 1 : i + 1] == "*/":
            k = src.rfind("/*", 0, i - 1)
            if k < 0:
                return -1
            i = k - 1
            continue
        c = src[i]
        if c in "\"'":
            q = c
            k = i - 1
            while k >= 0:
                if src[k] == q:
                    bs = 0
                    t = k - 1
                    while t >= 0 and src[t] == "\\":
                        bs += 1
                        t -= 1
                    if bs % 2 == 0:
                        i = k - 1
                        break
                k -= 1
            else:
                return -1
            continue
        if c == close_ch:
            depth += 1
            i -= 1
            continue
        if c == open_ch:
            depth -= 1
            if depth == 0:
                return i
            i -= 1
            continue
        i -= 1
    return -1


_DECL_TYPE_KEYWORDS = frozenset(
    {"class", "interface", "object", "enum", "fun", "constructor", "get", "set", "init", "companion"}
)
_DECL_MODIFIERS = frozenset(
    {
        "data", "value", "annotation", "sealed", "inner", "abstract", "open", "final",
        "private", "public", "internal", "protected", "actual", "expect", "override",
        "inline", "crossinline", "noinline", "tailrec", "operator", "infix", "suspend",
        "const", "lateinit", "external",
    }
)
_CONTROL_FLOW_KEYWORDS = frozenset(
    {"if", "else", "for", "while", "when", "try", "catch", "finally", "do"}
)


def _is_declaration_body_brace(src: str, brace_i: int) -> bool:
    """True for fun/class/object/interface/enum/init/accessor bodies — leave multi-line.

    Handles `data object Foo : Bar { val x = … }` and `class Foo<P : X> { … }` by scanning
    back through supertype / type-parameter clauses for a declaration keyword.
    """
    j = brace_i - 1
    depth_angle = 0
    depth_paren = 0
    while j >= 0:
        j = _skip_ws_comments_backward(src, j)
        if j < 0:
            return False
        c = src[j]
        if c == ">":
            depth_angle += 1
            j -= 1
            continue
        if c == "<":
            depth_angle -= 1
            j -= 1
            continue
        if c == ")":
            depth_paren += 1
            j -= 1
            continue
        if c == "(":
            depth_paren -= 1
            j -= 1
            continue
        if depth_angle > 0 or depth_paren > 0:
            # Inside `<…>` / `(…)` — skip other chars (idents handled below still need depth).
            if c in "\"'":
                # bail on strings in signatures (rare)
                return False
            j -= 1
            continue
        if c in "{}=;":
            return False
        if c in ":,.?":
            j -= 1
            continue
        name, j2 = _read_ident_backward(src, j)
        if not name:
            j -= 1
            continue
        bare = name.strip("`")
        if bare in _DECL_TYPE_KEYWORDS:
            return True
        if bare in _DECL_MODIFIERS or bare == "by":
            j = j2
            continue
        # Type / simple name (DeclineBonus, FollowUpAction, …) — keep scanning.
        j = j2
    return False


def _is_control_flow_brace(src: str, brace_i: int) -> bool:
    """True for if/else/for/while/when/try/catch/finally/do bodies — leave multi-line.

    When-arm blocks (`-> { … }`) are not control-flow openers — those may collapse.
    """
    j = _skip_ws_comments_backward(src, brace_i - 1)
    if j >= 1 and src[j - 1 : j + 1] == "->":
        return False
    j = _skip_ws_comments_backward(src, j)
    if j < 0:
        return False

    if src[j] == ")":
        open_paren = _matching_open(src, j, "(", ")")
        if open_paren < 0:
            return False
        j = _skip_ws_comments_backward(src, open_paren - 1)
        name, _ = _read_ident_backward(src, j)
        return name in _CONTROL_FLOW_KEYWORDS

    name, _ = _read_ident_backward(src, j)
    return name in _CONTROL_FLOW_KEYWORDS


def _leave_brace_multi_line(src: str, brace_i: int) -> bool:
    """True when this `{` must stay multi-line (decls + basic control flow)."""
    return _is_declaration_body_brace(src, brace_i) or _is_control_flow_brace(src, brace_i)


def _single_statement_body(body: str) -> str | None:
    """If body is one statement (possibly soft-wrapped), return flattened stmt; else None."""
    if not body.strip() or _body_has_line_comment(body):
        return None
    if _contains_brace_outside_strings(body):
        return None
    code_lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
    if not code_lines:
        return None
    # One physical line, or soft-wrap continuations of a single expression.
    if len(code_lines) == 1:
        return _flatten_outside_strings(code_lines[0])
    joined = " ".join(code_lines)
    ands, ors = _count_bool_ops(joined)
    if ands > 3 or ors > 3:
        return None
    # Continuations: prior line ends with soft binop, or next starts with `.` / binop / `?:`.
    for idx in range(len(code_lines) - 1):
        cur = code_lines[idx]
        nxt = code_lines[idx + 1]
        if _ends_with_soft_binop(cur):
            continue
        if cur.endswith((".", "?:", ",", "(", "[")):
            continue
        if nxt.startswith((".", "?:", "&&", "||", "+", "-", "*", "/", "%", ")", "]", ",")):
            continue
        return None
    flat = _flatten_outside_strings(joined)
    if "\n" in flat or not flat.strip():
        return None
    return flat


def collapse_single_statement_braces(src: str) -> str:
    """Collapse single-statement lambda / when-arm `{ … }` onto the `{` opener line.

    Leaves fun/class bodies and if/else/try/catch/for/while multi-line.
    """
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith(("//", "/*"), i):
            end = _skip_comment(src, i)
            out.append(src[i:end])
            i = end
            continue
        if src[i] in "\"'":
            end = _scan_string(src, i)
            out.append(src[i:end])
            i = end
            continue

        if src[i] != "{":
            out.append(src[i])
            i += 1
            continue

        close = _matching(src, i, "{", "}")
        if close < 0:
            out.append("{")
            i += 1
            continue

        body = src[i + 1 : close]
        leave_outer = _leave_brace_multi_line(src, i)

        # Always collapse nested braces inside (lambdas in a fun body, etc.).
        if _contains_brace_outside_strings(body):
            collapsed_body = collapse_single_statement_braces(body)
            if leave_outer:
                out.append("{")
                out.append(collapsed_body)
                out.append("}")
                i = close + 1
                continue
            stmt = _single_statement_body(collapsed_body)
            if stmt is None:
                out.append("{")
                out.append(collapsed_body)
                out.append("}")
                i = close + 1
                continue
            out.append(f"{{ {stmt} }}")
            i = close + 1
            continue

        if leave_outer or "\n" not in body:
            out.append(src[i : close + 1])
            i = close + 1
            continue

        stmt = _single_statement_body(body)
        if stmt is None:
            out.append(src[i : close + 1])
            i = close + 1
            continue

        out.append(f"{{ {stmt} }}")
        i = close + 1

    return "".join(out)


def collapse_kotlin_style(src: str) -> str:
    # Brace collapse after soft-wrap joins so `a &&\n b` inside `{ }` becomes one statement.
    once = align_when_arrows(
        collapse_single_statement_braces(
            collapse_soft_wrapped_binops(
                collapse_soft_wrapped_when_arms(
                    collapse_soft_wrapped_assignments(collapse_elvis_error(collapse_require_check(src)))
                )
            )
        )
    )
    # Nested braces may unlock an outer single-statement fold.
    twice = align_when_arrows(collapse_single_statement_braces(once))
    return twice


def _code_fingerprint(src: str) -> str:
    """Non-whitespace code outside comments; string contents kept exact.

    Used as a safety gate: the styler may only change layout/whitespace.
    """
    out: list[str] = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith(("//", "/*"), i):
            i = _skip_comment(src, i)
            continue
        if src[i] in "\"'":
            end = _scan_string(src, i)
            out.append(src[i:end])
            i = end
            continue
        if src[i].isspace():
            i += 1
            continue
        out.append(src[i])
        i += 1
    return "".join(out)


def _rewrite_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    updated = collapse_kotlin_style(original)
    if updated == original:
        return False
    if _code_fingerprint(updated) != _code_fingerprint(original):
        print(
            f"collapse-require-check: REFUSED non-whitespace change in {path}",
            file=sys.stderr,
        )
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def _run_tests() -> None:
    cases: list[tuple[str, str, str]] = [
        (
            "filter single-stmt",
            "            .filter {\n"
            "                setup.emptyPlayable(it) && !it.isEndZone(TeamSide.HOME) && !it.isEndZone(TeamSide.AWAY)\n"
            "            }\n",
            "            .filter { setup.emptyPlayable(it) && !it.isEndZone(TeamSide.HOME) && !it.isEndZone(TeamSide.AWAY) }\n",
        ),
        (
            "filter soft-wrapped &&",
            ".filter {\n    a &&\n        b\n}\n",
            ".filter { a && b }\n",
        ),
        (
            "multi-stmt brace stays",
            ".map {\n    val x = 1\n    x + 1\n}\n",
            ".map {\n    val x = 1\n    x + 1\n}\n",
        ),
        (
            "ctor args left multi-line",
            "listOf(\n"
            "    LegalAction.AdjustThrowByDoOver(\n"
            "        ongoingThrow.throwerId, target.destination, ongoingThrow.naturalRoll, target.range, target.obstructed,\n"
            "    ),\n"
            ")\n",
            "listOf(\n"
            "    LegalAction.AdjustThrowByDoOver(\n"
            "        ongoingThrow.throwerId, target.destination, ongoingThrow.naturalRoll, target.range, target.obstructed,\n"
            "    ),\n"
            ")\n",
        ),
        (
            "if stays multi-line",
            "if (ok) {\n    return x\n}\n",
            "if (ok) {\n    return x\n}\n",
        ),
        (
            "if/else stays multi-line",
            "if (a) {\n    x = 1\n} else {\n    x = 2\n}\n",
            "if (a) {\n    x = 1\n} else {\n    x = 2\n}\n",
        ),
        (
            "catch/finally stays multi-line",
            "try {\n    run()\n} catch (ex: Exception) {\n    log.error(\"failed\", ex)\n} finally {\n    done()\n}\n",
            "try {\n    run()\n} catch (ex: Exception) {\n    log.error(\"failed\", ex)\n} finally {\n    done()\n}\n",
        ),
        (
            "when-arm brace collapses",
            "            is LegalAction.DeclineBonus -> {\n"
            "                applyArmourOutcome(session.board, ongoing.playerId)\n"
            "            }\n",
            "            is LegalAction.DeclineBonus -> { applyArmourOutcome(session.board, ongoing.playerId) }\n",
        ),
        (
            "when-arm stage brace collapses",
            "            ongoing.nestedRun != null   -> {\n"
            "                ongoing.nestedRun = runResolver.mutate(session, ongoing.nestedRun!!, action)\n"
            "            }\n",
            "            ongoing.nestedRun != null   -> { ongoing.nestedRun = runResolver.mutate(session, ongoing.nestedRun!!, action) }\n",
        ),
        (
            "fun body stays multi-line",
            "fun setMatchResult(result: MatchResult) {\n"
            "        _progress = _progress.copy(result = result)\n"
            "    }\n",
            "fun setMatchResult(result: MatchResult) {\n"
            "        _progress = _progress.copy(result = result)\n"
            "    }\n",
        ),
        (
            "object field stays multi-line",
            "    data object DeclineBonus : FollowUpAction {\n"
            "        override val actionType: ActionType = ActionType.SKIP\n"
            "    }\n",
            "    data object DeclineBonus : FollowUpAction {\n"
            "        override val actionType: ActionType = ActionType.SKIP\n"
            "    }\n",
        ),
        (
            "interface property stays multi-line",
            "interface PlayerLegalAction : LegalAction {\n"
            "    val playerId: PlayerId\n"
            "}\n",
            "interface PlayerLegalAction : LegalAction {\n"
            "    val playerId: PlayerId\n"
            "}\n",
        ),
        (
            "fun with nested filter collapses filter only",
            "fun pick(squares: List<Square>) {\n"
            "    val s = squares\n"
            "        .filter {\n"
            "            setup.emptyPlayable(it)\n"
            "        }\n"
            "}\n",
            "fun pick(squares: List<Square>) {\n"
            "    val s = squares\n"
            "        .filter { setup.emptyPlayable(it) }\n"
            "}\n",
        ),
        (
            "require still one line",
            'require(\n    x > 0\n) {\n    "bad"\n}\n',
            'require(x > 0) { "bad" }\n',
        ),
    ]
    failed = 0
    for name, src, want in cases:
        got = collapse_kotlin_style(src)
        if got != want:
            failed += 1
            print(f"FAIL {name}\n  want: {want!r}\n  got:  {got!r}")
        else:
            print(f"ok   {name}")

    # Safety gate: refuse token changes (simulate via fingerprint).
    ugly = "val x = 1\n"
    sneaky = "val x = 2\n"
    if _code_fingerprint(ugly) == _code_fingerprint(sneaky):
        failed += 1
        print("FAIL fingerprint should distinguish token changes")
    else:
        print("ok   fingerprint distinguishes tokens")
    # Layout-only change keeps fingerprint.
    spaced = "val  x  =  1\n"
    if _code_fingerprint(ugly) != _code_fingerprint(spaced):
        failed += 1
        print("FAIL fingerprint should ignore whitespace")
    else:
        print("ok   fingerprint ignores whitespace")
    if failed:
        raise SystemExit(f"{failed} test(s) failed")
    print("all tests passed")


def main() -> None:
    payload = json.load(sys.stdin)
    file_path = payload.get("file_path") or ""
    if not file_path.endswith((".kt", ".kts")):
        print(f"collapse-require-check: skip non-kt {file_path!r}", file=sys.stderr)
        return

    path = Path(file_path)
    if not path.is_file():
        print(f"collapse-require-check: missing file {file_path!r}", file=sys.stderr)
        return

    if _rewrite_file(path):
        print(f"collapse-require-check: rewrote {path}", file=sys.stderr)
    else:
        print(f"collapse-require-check: no change {path}", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--test":
        _run_tests()
    elif len(sys.argv) > 1 and sys.argv[1] == "--all":
        root = Path(sys.argv[2]) if len(sys.argv) > 2 else Path(".")
        changed = 0
        for path in sorted(root.rglob("*.kt")) + sorted(root.rglob("*.kts")):
            if _rewrite_file(path):
                changed += 1
                print(path)
        print(f"rewrote {changed} file(s)")
    else:
        main()
