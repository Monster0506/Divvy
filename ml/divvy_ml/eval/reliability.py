"""Reliability diagram as a dependency-free SVG, for embedding in the evaluation report."""

from collections.abc import Sequence
from xml.sax.saxutils import escape

from divvy_ml.eval.metrics import ReliabilityBin

SIZE = 360
MARGIN = 44
INK = "#16191d"
DIM = "#666d75"
ACCENT = "#1f6f4a"


def reliability_svg(bins: Sequence[ReliabilityBin], title: str = "Reliability") -> str:
    """Observed hit rate per confidence bin against the diagonal of perfect calibration."""
    plot = SIZE - 2 * MARGIN

    def x(value: float) -> float:
        return MARGIN + value * plot

    def y(value: float) -> float:
        return SIZE - MARGIN - value * plot

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {SIZE} {SIZE}" role="img" '
        f'aria-label="{escape(title)}">',
        f'<text x="{SIZE / 2}" y="22" text-anchor="middle" font-size="14" fill="{INK}">'
        f"{escape(title)}</text>",
        f'<rect x="{MARGIN}" y="{MARGIN}" width="{plot}" height="{plot}" fill="none" '
        f'stroke="{DIM}" stroke-width="1"/>',
    ]
    for b in bins:
        top = y(b.observed_rate)
        parts.append(
            f'<rect x="{x(b.lower):.1f}" y="{top:.1f}" width="{plot * (b.upper - b.lower):.1f}" '
            f'height="{y(0) - top:.1f}" fill="{ACCENT}" fill-opacity="0.35" stroke="{ACCENT}">'
            f"<title>{b.count} items, confidence {b.mean_confidence:.2f}, "
            f"observed {b.observed_rate:.2f}</title></rect>"
        )

    parts.append(
        f'<line x1="{x(0)}" y1="{y(0)}" x2="{x(1)}" y2="{y(1)}" stroke="{INK}" '
        f'stroke-dasharray="4 4"/>'
    )
    parts.append(
        f'<text x="{SIZE / 2}" y="{SIZE - 10}" text-anchor="middle" font-size="12" fill="{DIM}">'
        f"Confidence</text>"
    )
    parts.append(
        f'<text x="14" y="{SIZE / 2}" text-anchor="middle" font-size="12" fill="{DIM}" '
        f'transform="rotate(-90 14 {SIZE / 2})">Observed accuracy</text>'
    )
    parts.append("</svg>")
    return "\n".join(parts)
