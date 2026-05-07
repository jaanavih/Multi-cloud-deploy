#!/usr/bin/env python3
"""
AI-assisted cost comparison for AWS EKS vs GCP GKE.

Baseline USD estimates are computed deterministically from k8s manifests
(the same model as lib/costComparison.groovy). The LLM only produces
narrative, caveats, and recommendations — final numeric totals always come
from the baseline to avoid hallucinated prices.
"""

from __future__ import annotations

import argparse
import html as html_module
import json
import math
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
import yaml

OPENAI_URL = "https://api.openai.com/v1/chat/completions"
ANTHROPIC_URL = "https://api.anthropic.com/v1/messages"


def parse_cpu_to_cores(raw: str | None) -> float:
    if not raw:
        return 0.1
    s = str(raw).strip()
    if s.endswith("m"):
        return max(float(s[:-1]) / 1000.0, 0.001)
    return max(float(s), 0.001)


def parse_memory_to_gib(raw: str | None) -> float:
    if not raw:
        return 0.125
    s = str(raw).strip().lower()
    mult = 1.0
    for suf, factor in (("gi", 1.0), ("g", 1.0), ("mi", 1.0 / 1024), ("m", 1.0 / 1024)):
        if s.endswith(suf):
            s = s[: -len(suf)]
            mult = factor
            break
    return max(float(s) * mult, 0.001)


def load_specs(k8s_dir: Path) -> dict[str, Any]:
    dep_path = k8s_dir / "deployment.yaml"
    svc_path = k8s_dir / "service.yaml"
    replicas = 1
    service_type = "LoadBalancer"
    containers_summary: list[dict[str, Any]] = []

    if dep_path.exists():
        doc = yaml.safe_load(dep_path.read_text()) or {}
        spec = (doc.get("spec") or {})
        replicas = int(spec.get("replicas") or 1)
        tpl = ((spec.get("template") or {}).get("spec") or {})
        for c in tpl.get("containers") or []:
            req = (c.get("resources") or {}).get("requests") or {}
            containers_summary.append(
                {
                    "name": c.get("name"),
                    "image": c.get("image"),
                    "cpu_request": req.get("cpu"),
                    "memory_request": req.get("memory"),
                    "cpu_cores_est": parse_cpu_to_cores(req.get("cpu")),
                    "memory_gib_est": parse_memory_to_gib(req.get("memory")),
                }
            )
    if not containers_summary:
        containers_summary = [
            {
                "name": "app",
                "image": "unknown",
                "cpu_request": None,
                "memory_request": None,
                "cpu_cores_est": 0.1,
                "memory_gib_est": 0.125,
            }
        ]

    if svc_path.exists():
        doc = yaml.safe_load(svc_path.read_text()) or {}
        service_type = (doc.get("spec") or {}).get("type") or "LoadBalancer"

    total_cpu = sum(x["cpu_cores_est"] for x in containers_summary)
    total_mem = sum(x["memory_gib_est"] for x in containers_summary)
    return {
        "replicas": replicas,
        "service_type": service_type,
        "containers": containers_summary,
        "workload_cpu_cores_per_pod": round(total_cpu, 4),
        "workload_memory_gib_per_pod": round(total_mem, 4),
    }


def pick_instance_guess(specs: dict[str, Any]) -> tuple[str, str]:
    """Return (aws_instance, gcp_machine_type) heuristics from per-pod demand."""
    cpu = specs["workload_cpu_cores_per_pod"]
    mem = specs["workload_memory_gib_per_pod"]
    if cpu <= 0.25 and mem <= 0.5:
        return "t3.micro", "e2-micro"
    if cpu <= 0.5 and mem <= 1.0:
        return "t3.small", "e2-small"
    if cpu <= 1.0 and mem <= 2.0:
        return "t3.medium", "e2-medium"
    if cpu <= 2.0 and mem <= 4.0:
        return "t3.large", "e2-standard-2"
    return "t3.xlarge", "e2-standard-4"


def baseline_estimate(
    specs: dict[str, Any],
    aws_region: str,
    gcp_region: str,
    hours_per_month: int,
) -> dict[str, Any]:
    aws_hourly = {
        "t3.micro": 0.0104,
        "t3.small": 0.0208,
        "t3.medium": 0.0416,
        "t3.large": 0.0832,
        "t3.xlarge": 0.1664,
    }
    gcp_hourly = {
        "e2-micro": 0.006,
        "e2-small": 0.020,
        "e2-medium": 0.040,
        "e2-standard-2": 0.080,
        "e2-standard-4": 0.160,
    }
    aws_inst, gcp_machine = pick_instance_guess(specs)
    replicas = specs["replicas"]
    pods_per_node = 4
    instances_needed = max(1, math.ceil(replicas / pods_per_node))

    aws_lb_rate = 0.0225
    gcp_lb_rate = 0.025
    aws_dt = 5.0
    gcp_dt = 4.0
    if specs["service_type"] != "LoadBalancer":
        aws_lb_rate = gcp_lb_rate = 0.0
        aws_dt = gcp_dt = 0.0

    aws_compute = aws_hourly[aws_inst] * instances_needed * hours_per_month
    gcp_compute = gcp_hourly[gcp_machine] * instances_needed * hours_per_month

    aws_costs = {
        "clusterManagement": 0.10 * hours_per_month,
        "compute": aws_compute,
        "loadBalancer": aws_lb_rate * hours_per_month,
        "dataTransfer": aws_dt,
        "storage": 0.10 * 20 * instances_needed,
        "networking": 2.0,
        "currency": "USD",
        "region": aws_region,
        "assumed_worker_instance": aws_inst,
        "instances_needed": instances_needed,
    }
    aws_costs["total"] = sum(
        aws_costs[k]
        for k in (
            "clusterManagement",
            "compute",
            "loadBalancer",
            "dataTransfer",
            "storage",
            "networking",
        )
    )

    gcp_costs = {
        "clusterManagement": 0.10 * hours_per_month,
        "compute": gcp_compute,
        "loadBalancer": gcp_lb_rate * hours_per_month,
        "dataTransfer": gcp_dt,
        "storage": 0.04 * 20 * instances_needed,
        "networking": 1.5,
        "currency": "USD",
        "region": gcp_region,
        "assumed_worker_machine_type": gcp_machine,
        "instances_needed": instances_needed,
    }
    gcp_costs["total"] = sum(
        gcp_costs[k]
        for k in (
            "clusterManagement",
            "compute",
            "loadBalancer",
            "dataTransfer",
            "storage",
            "networking",
        )
    )

    return {
        "aws": aws_costs,
        "gcp": gcp_costs,
        "specs": specs,
        "hours_per_month": hours_per_month,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
    }


def build_prompt_payload(baseline: dict[str, Any]) -> str:
    return json.dumps(
        {
            "instruction": (
                "You are a cloud FinOps assistant. Baseline numbers are authoritative for totals. "
                "Do not invent different monthly totals. Explain drivers, exclusions, and trade-offs."
            ),
            "baseline_monthly_usd": {
                "aws": round(baseline["aws"]["total"], 2),
                "gcp": round(baseline["gcp"]["total"], 2),
            },
            "baseline_breakdown": {
                "aws": {k: round(v, 2) if isinstance(v, float) else v for k, v in baseline["aws"].items()},
                "gcp": {k: round(v, 2) if isinstance(v, float) else v for k, v in baseline["gcp"].items()},
            },
            "kubernetes_workload": baseline["specs"],
            "notes": (
                "Costs approximate shared cluster overhead + incremental workload. "
                "Real bills depend on discounts, committed use, egress, logging, and existing spare capacity."
            ),
        },
        indent=2,
    )


def call_openai(model: str, user_payload: str) -> dict[str, Any]:
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        raise RuntimeError("OPENAI_API_KEY is not set")

    system = (
        "Return a single JSON object only (no markdown). "
        "Fields: executive_summary (string), aws_narrative (string), gcp_narrative (string), "
        "excluded_or_uncertain_costs (array of strings), optimization_recommendations (array of strings), "
        "which_looks_cheaper_for_this_snapshot (string: aws|gcp|similar), "
        "confidence_notes (string). "
        "Never contradict baseline_monthly_usd totals given in the user message; treat them as ground truth."
    )
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user_payload},
        ],
    }
    r = requests.post(
        OPENAI_URL,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json=body,
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    text = data["choices"][0]["message"]["content"]
    return json.loads(text)


def call_anthropic(model: str, user_payload: str) -> dict[str, Any]:
    key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not key:
        raise RuntimeError("ANTHROPIC_API_KEY is not set")

    system = (
        "Return only valid JSON with keys: executive_summary, aws_narrative, gcp_narrative, "
        "excluded_or_uncertain_costs, optimization_recommendations, "
        "which_looks_cheaper_for_this_snapshot (aws|gcp|similar), confidence_notes. "
        "Do not change the baseline totals implied in the user message."
    )
    body = {
        "model": model,
        "max_tokens": 2048,
        "temperature": 0.2,
        "system": system,
        "messages": [{"role": "user", "content": user_payload}],
    }
    r = requests.post(
        ANTHROPIC_URL,
        headers={
            "x-api-key": key,
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
        },
        json=body,
        timeout=120,
    )
    r.raise_for_status()
    data = r.json()
    text = next((b["text"] for b in data.get("content", []) if b.get("type") == "text"), "")
    return json.loads(text)


def fallback_ai_text(baseline: dict[str, Any]) -> dict[str, Any]:
    cheaper = (
        "gcp"
        if baseline["gcp"]["total"] < baseline["aws"]["total"]
        else "aws"
        if baseline["aws"]["total"] < baseline["gcp"]["total"]
        else "similar"
    )
    return {
        "executive_summary": (
            "AI narrative skipped (no API key). Baseline estimates are shown from the deterministic model."
        ),
        "aws_narrative": "See baseline breakdown for AWS.",
        "gcp_narrative": "See baseline breakdown for GCP.",
        "excluded_or_uncertain_costs": [
            "Committed use / Savings Plans / sustained use discounts",
            "Data egress beyond rough placeholder",
            "Logging, monitoring, backups, support plans",
            "Idle cluster capacity and bin-packing efficiency",
        ],
        "optimization_recommendations": [
            "Add CPU/memory requests to manifests for tighter estimates",
            "Right-size nodes and enable cluster autoscaling",
            "Use spot/preemptible where appropriate",
        ],
        "which_looks_cheaper_for_this_snapshot": cheaper,
        "confidence_notes": "Baseline-only mode.",
    }


def render_html(baseline: dict[str, Any], ai: dict[str, Any]) -> str:
    def esc(x: str) -> str:
        return html_module.escape(x, quote=True)

    def ul(items: list[str]) -> str:
        lis = "".join(f"<li>{esc(str(i))}</li>" for i in items)
        return f"<ul>{lis}</ul>"

    aws = baseline["aws"]
    gcp = baseline["gcp"]
    diff = abs(aws["total"] - gcp["total"])
    cheaper = "GCP" if gcp["total"] < aws["total"] else "AWS" if aws["total"] < gcp["total"] else "Similar"

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>AI-assisted cost comparison</title>
  <style>
    body {{ font-family: system-ui, sans-serif; margin: 24px; color: #1a1a1a; }}
    h1 {{ font-size: 1.4rem; }}
    .grid {{ display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }}
    .card {{ border: 1px solid #ddd; border-radius: 8px; padding: 16px; }}
    .muted {{ color: #555; font-size: 0.9rem; }}
    table {{ width: 100%; border-collapse: collapse; margin-top: 8px; }}
    td, th {{ border-bottom: 1px solid #eee; padding: 6px 4px; text-align: left; }}
    .banner {{ background: #f4f6fb; padding: 12px 16px; border-radius: 8px; margin: 16px 0; }}
  </style>
</head>
<body>
  <h1>AI-assisted multi-cloud cost snapshot</h1>
  <p class="muted">
    Numeric totals are from the deterministic baseline (manifest-driven). The model adds narrative and caveats only.
    Generated {esc(baseline.get("generated_at_utc", ""))}.
  </p>
  <div class="banner">
    <strong>Baseline comparison:</strong>
    AWS ~ ${aws["total"]:.2f}/mo vs GCP ~ ${gcp["total"]:.2f}/mo
    — <strong>{esc(cheaper)}</strong> lower by ~ ${diff:.2f}/mo on this snapshot.
  </div>
  <div class="grid">
    <div class="card">
      <h2>AWS (EKS)</h2>
      <p class="muted">Region: {esc(str(aws["region"]))} · Workers assumed: {esc(str(aws["assumed_worker_instance"]))}</p>
      <table>
        <tr><th>Line item</th><th>USD/mo</th></tr>
        <tr><td>Cluster management</td><td>${aws["clusterManagement"]:.2f}</td></tr>
        <tr><td>Compute</td><td>${aws["compute"]:.2f}</td></tr>
        <tr><td>Load balancer</td><td>${aws["loadBalancer"]:.2f}</td></tr>
        <tr><td>Data transfer (placeholder)</td><td>${aws["dataTransfer"]:.2f}</td></tr>
        <tr><td>Storage (placeholder)</td><td>${aws["storage"]:.2f}</td></tr>
        <tr><td>Networking (placeholder)</td><td>${aws["networking"]:.2f}</td></tr>
        <tr><th>Total</th><th>${aws["total"]:.2f}</th></tr>
      </table>
    </div>
    <div class="card">
      <h2>GCP (GKE)</h2>
      <p class="muted">Region: {esc(str(gcp["region"]))} · Workers assumed: {esc(str(gcp["assumed_worker_machine_type"]))}</p>
      <table>
        <tr><th>Line item</th><th>USD/mo</th></tr>
        <tr><td>Cluster management</td><td>${gcp["clusterManagement"]:.2f}</td></tr>
        <tr><td>Compute</td><td>${gcp["compute"]:.2f}</td></tr>
        <tr><td>Load balancer</td><td>${gcp["loadBalancer"]:.2f}</td></tr>
        <tr><td>Data transfer (placeholder)</td><td>${gcp["dataTransfer"]:.2f}</td></tr>
        <tr><td>Storage (placeholder)</td><td>${gcp["storage"]:.2f}</td></tr>
        <tr><td>Networking (placeholder)</td><td>${gcp["networking"]:.2f}</td></tr>
        <tr><th>Total</th><th>${gcp["total"]:.2f}</th></tr>
      </table>
    </div>
  </div>
  <div class="card" style="margin-top:16px;">
    <h2>AI narrative</h2>
    <p>{esc(str(ai.get("executive_summary", "")))}</p>
    <h3>AWS</h3>
    <p>{esc(str(ai.get("aws_narrative", "")))}</p>
    <h3>GCP</h3>
    <p>{esc(str(ai.get("gcp_narrative", "")))}</p>
    <h3>Excluded or uncertain costs</h3>
    {ul(list(ai.get("excluded_or_uncertain_costs") or []))}
    <h3>Optimization ideas</h3>
    {ul(list(ai.get("optimization_recommendations") or []))}
    <p class="muted"><strong>Model view on cheaper option (for this snapshot):</strong>
      {esc(str(ai.get("which_looks_cheaper_for_this_snapshot", "")))}</p>
    <p class="muted"><strong>Confidence:</strong> {esc(str(ai.get("confidence_notes", "")))}</p>
  </div>
</body>
</html>
"""


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--k8s-dir", type=Path, default=Path("k8s"))
    p.add_argument("--aws-region", default=os.environ.get("AWS_COST_REGION", "ap-southeast-1"))
    p.add_argument("--gcp-region", default=os.environ.get("GCP_COST_REGION", "asia-southeast1"))
    p.add_argument("--hours-per-month", type=int, default=730)
    p.add_argument(
        "--provider",
        choices=("auto", "openai", "anthropic", "none"),
        default=os.environ.get("AI_COST_PROVIDER", "auto"),
    )
    p.add_argument("--openai-model", default=os.environ.get("OPENAI_MODEL", "gpt-4o-mini"))
    p.add_argument(
        "--anthropic-model",
        default=os.environ.get("ANTHROPIC_MODEL", "claude-3-5-haiku-20241022"),
    )
    p.add_argument("--out-dir", type=Path, default=Path("."))
    args = p.parse_args()

    specs = load_specs(args.k8s_dir)
    baseline = baseline_estimate(specs, args.aws_region, args.gcp_region, args.hours_per_month)
    payload = build_prompt_payload(baseline)

    ai: dict[str, Any]
    ai_meta: dict[str, Any] = {"mode": "unknown"}

    prov = args.provider
    if prov == "auto":
        if os.environ.get("OPENAI_API_KEY"):
            prov = "openai"
        elif os.environ.get("ANTHROPIC_API_KEY"):
            prov = "anthropic"
        else:
            prov = "none"

    try:
        if prov == "openai":
            ai = call_openai(args.openai_model, payload)
            ai_meta = {"mode": "openai", "model": args.openai_model}
        elif prov == "anthropic":
            ai = call_anthropic(args.anthropic_model, payload)
            ai_meta = {"mode": "anthropic", "model": args.anthropic_model}
        else:
            ai = fallback_ai_text(baseline)
            ai_meta = {"mode": "baseline_only"}
    except Exception as e:
        ai = fallback_ai_text(baseline)
        ai_meta = {"mode": "error_fallback", "error": str(e)}

    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    combined = {
        "ai_meta": ai_meta,
        "baseline": baseline,
        "ai_narrative": ai,
    }
    (out_dir / "cost-comparison-ai.json").write_text(json.dumps(combined, indent=2))
    (out_dir / "cost-comparison-ai-report.html").write_text(render_html(baseline, ai))

    print(json.dumps({"wrote": ["cost-comparison-ai.json", "cost-comparison-ai-report.html"], **ai_meta}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
