"""Defaults and reachability checks for the local ``./dev.sh`` stack.

Cursor agent / tests keep using ``8080`` / ``5173``; human + AI training use
``dev.sh`` ports (``18080`` / ``15173``) unless overridden.
"""

from __future__ import annotations

import urllib.error
import urllib.request
from pathlib import Path

from gridiron_ai.paths import AI_ROOT

REPO_ROOT = AI_ROOT.parent
DEFAULT_BASE_URL = "http://localhost:18080"
DEFAULT_FRONTEND_URL = "http://localhost:15173"


def url_reachable(url: str, *, timeout_s: float = 2.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout_s) as resp:
            return 200 <= getattr(resp, "status", 200) < 500
    except (urllib.error.URLError, TimeoutError, OSError):
        return False


def require_backend(backend_url: str = DEFAULT_BASE_URL) -> None:
    """Exit with a ``./dev.sh`` hint if Spring Boot is not reachable."""
    backend = backend_url.rstrip("/")
    if url_reachable(f"{backend}/health"):
        print(f"Backend OK — {backend}", flush=True)
        return
    print(
        f"Cannot reach Spring Boot at {backend}/health\n"
        f"\nStart the local stack from the repo root, then retry:\n"
        f"  cd {REPO_ROOT}\n"
        f"  ./dev.sh\n"
        f"\n(dev.sh default backend :18080. Override with --base-url if you changed BACKEND_PORT.)",
        flush=True,
    )
    raise SystemExit(1)


def require_dev_stack(
    backend_url: str = DEFAULT_BASE_URL,
    frontend_url: str = DEFAULT_FRONTEND_URL,
) -> None:
    """Exit with a ``./dev.sh`` hint if Spring Boot or Vite is not reachable."""
    backend = backend_url.rstrip("/")
    frontend = frontend_url.rstrip("/")
    backend_ok = url_reachable(f"{backend}/health")
    frontend_ok = url_reachable(frontend)

    if backend_ok and frontend_ok:
        print(f"Dev stack OK — API {backend} · UI {frontend}", flush=True)
        return

    missing: list[str] = []
    if not backend_ok:
        missing.append(f"Spring Boot not reachable at {backend}/health")
    if not frontend_ok:
        missing.append(f"GUI not reachable at {frontend}")

    print("Cannot start — local stack not up:", flush=True)
    for line in missing:
        print(f"  • {line}", flush=True)
    print(
        f"\nStart the local stack from the repo root, then retry:\n"
        f"  cd {REPO_ROOT}\n"
        f"  ./dev.sh\n"
        f"\n(dev.sh defaults: backend :18080, frontend :15173. "
        f"Override with --base-url / --frontend-url if you changed BACKEND_PORT / FRONTEND_PORT.)",
        flush=True,
    )
    raise SystemExit(1)
