#!/usr/bin/env python3
"""
generate_research_charts.py
Reads _research_timeseries.csv and _research_summary.csv from the latest run
and generates high-resolution figures for thesis reporting.
"""

import os
import sys
import glob
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker

def get_latest_run_dir():
    run_dirs = sorted(glob.glob("PureEdgeSim/output/20*"), reverse=True)
    for d in run_dirs:
        if os.path.exists(os.path.join(d, "_research_summary.csv")) and os.path.exists(os.path.join(d, "_research_timeseries.csv")):
            return d
    return None

def plot_charts():
    run_dir = get_latest_run_dir()
    if not run_dir:
        print("No run directory with research CSVs found.")
        sys.exit(1)

    print(f"Loading data from {run_dir}")
    summary_path = os.path.join(run_dir, "_research_summary.csv")
    timeseries_path = os.path.join(run_dir, "_research_timeseries.csv")

    summary_df = pd.read_csv(summary_path)
    ts_df = pd.read_csv(timeseries_path)

    out_dirs = [
        os.path.join(run_dir, "Final results", "Research"),
        "/home/cotton/.gemini/antigravity-ide/brain/1d0b8ef6-2376-4762-85e9-65deb3a8c2fd/charts"
    ]
    for d in out_dirs:
        os.makedirs(d, exist_ok=True)

    plt.style.use('seaborn-v0_8-whitegrid' if 'seaborn-v0_8-whitegrid' in plt.style.available else 'default')

    # 1. Throughput Over Time
    fig, ax = plt.subplots(figsize=(10, 5), dpi=300)
    ax.plot(ts_df['Window start (s)'] / 60.0, ts_df['Throughput in window (tasks/min)'], color='#1f77b4', linewidth=1.5, label='Throughput (tasks/min)')
    ax.set_title("Throughput Over Simulation Time (60s Windows)", fontsize=14, fontweight='bold', pad=12)
    ax.set_xlabel("Simulation Time (minutes)", fontsize=11)
    ax.set_ylabel("Throughput (tasks/min)", fontsize=11)
    ax.grid(True, linestyle='--', alpha=0.6)
    ax.legend(loc='upper right', frameon=True)
    plt.tight_layout()
    for d in out_dirs:
        fig.savefig(os.path.join(d, "research_throughput_timeseries.png"))
    plt.close(fig)

    # 2. Window Completions & Failures
    fig, ax = plt.subplots(figsize=(10, 5), dpi=300)
    time_min = ts_df['Window start (s)'] / 60.0
    ax.plot(time_min, ts_df['Completions in window'], color='#2ca02c', linewidth=1.5, label='Total Completions')
    ax.plot(time_min, ts_df['Failures in window'], color='#d62728', linewidth=1.5, linestyle='--', label='Failures')
    ax.set_title("Task Completions and Failures Over Time", fontsize=14, fontweight='bold', pad=12)
    ax.set_xlabel("Simulation Time (minutes)", fontsize=11)
    ax.set_ylabel("Tasks per 60s Window", fontsize=11)
    ax.grid(True, linestyle='--', alpha=0.6)
    ax.legend(loc='upper right', frameon=True)
    plt.tight_layout()
    for d in out_dirs:
        fig.savefig(os.path.join(d, "research_completions_failures_timeseries.png"))
    plt.close(fig)

    # 3. Latency Breakdown
    fig, ax = plt.subplots(figsize=(8, 5), dpi=300)
    row = summary_df.iloc[0]
    categories = ['Queue Waiting', 'Network', 'Computation', 'Cold Start']
    values = [
        row['Average latency: queue waiting (s)'],
        row['Average latency: network (s)'],
        row['Average latency: computation (s)'],
        row['Average latency: cold start (s)']
    ]
    colors = ['#ff7f0e', '#1f77b4', '#2ca02c', '#9467bd']
    bars = ax.bar(categories, values, color=colors, width=0.5, edgecolor='black', linewidth=0.8)
    ax.set_title("Average Latency Breakdown", fontsize=14, fontweight='bold', pad=12)
    ax.set_ylabel("Latency (seconds)", fontsize=11)
    ax.grid(axis='y', linestyle='--', alpha=0.6)
    for bar, val in zip(bars, values):
        if val > 0:
            ax.annotate(f"{val:.2f}s", xy=(bar.get_x() + bar.get_width() / 2, val),
                        xytext=(0, 3), textcoords="offset points", ha='center', va='bottom', fontsize=10)
    plt.tight_layout()
    for d in out_dirs:
        fig.savefig(os.path.join(d, "research_latency_breakdown.png"))
    plt.close(fig)

    # 4. Failure Categories Breakdown
    fig, ax = plt.subplots(figsize=(8, 5), dpi=300)
    fail_cats = ['Deadline', 'Battery', 'OOM', 'Network', 'Mobility']
    fail_counts = [
        row['Failures: Deadline'],
        row['Failures: Battery'],
        row['Failures: OOM'],
        row['Failures: Network'],
        row['Failures: Mobility']
    ]
    colors = ['#d62728', '#8c564b', '#e377c2', '#7f7f7f', '#bcbd22']
    bars = ax.bar(fail_cats, fail_counts, color=colors, width=0.5, edgecolor='black', linewidth=0.8)
    ax.set_title("Task Failure Breakdown by Category", fontsize=14, fontweight='bold', pad=12)
    ax.set_ylabel("Failed Tasks Count", fontsize=11)
    ax.grid(axis='y', linestyle='--', alpha=0.6)
    for bar, count in zip(bars, fail_counts):
        if count > 0:
            ax.annotate(f"{int(count)}", xy=(bar.get_x() + bar.get_width() / 2, count),
                        xytext=(0, 3), textcoords="offset points", ha='center', va='bottom', fontsize=10)
    plt.tight_layout()
    for d in out_dirs:
        fig.savefig(os.path.join(d, "research_failure_breakdown.png"))
    plt.close(fig)

    print("All research charts successfully generated!")

if __name__ == '__main__':
    plot_charts()
