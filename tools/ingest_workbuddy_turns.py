#!/usr/bin/env python3
"""WorkBuddy 宿主会话 jsonl -> agent-memory L0 增量导入器。

数据源是宿主已经全量落盘的会话记录，因此不需要改 WorkBuddy、也不依赖模型自觉调用
memory_write。每轮问答按 conversationRequestId 聚合成同一个 trace_id 写入 L0，
服务端按 meta_json.h 做二次判重，本脚本再用 state 文件做本地去重。

本机网络注意：curl 走代理时 127.0.0.1 也会失败，Python 必须显式禁用代理。
若后端配置了 agent-memory.mcp-token，从 AGENT_MEMORY_TOKEN 环境变量读取。
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
import uuid
from collections import OrderedDict
from datetime import datetime
from pathlib import Path

HOME_PROJECTS = Path.home() / ".workbuddy" / "projects"
DEFAULT_PROJECT = "d-codehub-agent_runner"
DEFAULT_URL = "http://127.0.0.1:9900/api/agent-memory/mcp"
USER_QUERY = re.compile(r"<user_query>(.*?)</user_query>", re.S)
STATE_PATH = Path(__file__).resolve().parent / "data" / "ingest-workbuddy-state.json"
# M3 定时任务自身会话不入库，避免每 5 分钟把运行日志再灌回 L0 形成自引用增长。
AUTO_MARKER = "[WB-AUTO-INGEST]"


def parse_args():
    p = argparse.ArgumentParser(description="Incremental WorkBuddy dialogue ingest into agent-memory L0")
    p.add_argument("--project", default=DEFAULT_PROJECT, help="宿主项目目录名（默认白名单内的本项目）")
    p.add_argument("--allow-project", action="append", default=[], help="额外允许的项目目录名，可重复")
    p.add_argument("--session", help="只导入指定 sessionId 的 jsonl")
    p.add_argument("--since", help="只导入该时间之后的消息；ISO 时间或 epoch 毫秒")
    p.add_argument("--until", help="只导入该时间之前的消息；用于固定验证窗口，排除会话进行中新增的行")
    p.add_argument("--full", action="store_true", help="忽略本地 state（服务端仍判重）")
    p.add_argument("--dry-run", action="store_true", help="只统计不写入、不推进 state")
    p.add_argument("--probe", help="额外打印指定 conversationRequestId 的 user/assistant 计数")
    p.add_argument("--test-sensitive", action="store_true", help="注入一条含手机号与邮箱的探针消息")
    p.add_argument("--limit", type=int, default=100, help="每批 turns 条数（默认 100）")
    p.add_argument("--state", default=str(STATE_PATH), help="state 文件路径")
    p.add_argument("--url", default=os.environ.get("AGENT_MEMORY_URL", DEFAULT_URL), help="MCP 端点")
    return p.parse_args()


def window_ms(value, fallback):
    if not value:
        return fallback
    if value.isdigit():
        return int(value)
    return int(datetime.fromisoformat(value).timestamp() * 1000)


def since_ms(value):
    return window_ms(value, 0)


def until_ms(value):
    return window_ms(value, 0)  # 0 = 不限上界


def rpc(name, arguments, url, token, seq):
    body = json.dumps({
        "jsonrpc": "2.0", "id": seq, "method": "tools/call",
        "params": {"name": name, "arguments": arguments},
    }, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=body, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def tool_result(response):
    """MCP tools/call 的业务返回体是 content[0].text 里的 JSON 字符串。"""
    result = response.get("result") or {}
    content = result.get("content") or []
    if not content:
        raise RuntimeError("MCP 返回缺少 result.content: " + json.dumps(response, ensure_ascii=False)[:300])
    return json.loads(content[0]["text"])


def extract_turn(obj, fallback_session):
    """从一条 message 行提取可导入的 turn；返回 None 表示跳过。"""
    if obj.get("type") != "message":
        return None
    role = obj.get("role")
    if role not in ("user", "assistant"):
        return None
    want = "input_text" if role == "user" else "output_text"
    parts = [c.get("text", "") for c in (obj.get("content") or [])
             if isinstance(c, dict) and c.get("type") == want]
    text = "\n".join(t for t in parts if t).strip()
    if not text:
        return None
    if role == "user":
        match = USER_QUERY.search(text)
        if not match:
            return None  # system-reminder、压缩摘要、工具回填都不是用户提问
        text = match.group(1).strip()
        if not text or text.startswith("<conversation_history_summary"):
            return None
    provider = obj.get("providerData") or {}
    msg_id = str(obj.get("id") or "")
    if not msg_id:
        msg_id = hashlib.sha1((text + str(obj.get("timestamp"))).encode()).hexdigest()[:16]
    return {
        "msgId": msg_id,
        "requestId": str(provider.get("conversationRequestId") or ("orphan-" + msg_id)),
        "role": role,
        "content": text,
        "ts": int(obj.get("timestamp") or 0),
        "sessionId": str(obj.get("sessionId") or fallback_session),
    }


def load_state(path):
    state_path = Path(path)
    if not state_path.exists():
        return {"version": 1, "imported": {}}
    with state_path.open(encoding="utf-8") as f:
        return json.load(f)


def save_state(path, state):
    state_path = Path(path)
    state_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = state_path.with_suffix(".json.tmp")
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=1)
    tmp.replace(state_path)


def collect(args, state):
    project_dir = HOME_PROJECTS / args.project
    if not project_dir.is_dir():
        raise SystemExit("项目目录不存在: " + str(project_dir))
    files = ([project_dir / (args.session + ".jsonl")]
             if args.session else sorted(project_dir.glob("*.jsonl")))
    since = since_ms(args.since)
    until = until_ms(args.until)
    scanned = 0
    turns = []
    seen_ids = set()
    for path in files:
        if not path.is_file():
            continue
        key = args.project + "/" + path.stem
        already = set() if args.full else set(state["imported"].get(key, []))
        with path.open(encoding="utf-8", errors="replace") as f:
            for line in f:
                scanned += 1
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                turn = extract_turn(obj, path.stem)
                if turn is None or turn["ts"] < since or (until and turn["ts"] > until):
                    continue
                if turn["msgId"] in already or turn["msgId"] in seen_ids:
                    continue
                seen_ids.add(turn["msgId"])
                turn["_key"] = key
                turns.append(turn)
    turns = drop_automation_turns(turns)
    turns.sort(key=lambda t: (t["sessionId"], t["ts"], t["msgId"]))
    return scanned, turns


def drop_automation_turns(turns):
    """定时任务自身会话：按 requestId 整轮剔除（prompt 里带 AUTO_MARKER）。"""
    excluded = {t["requestId"] for t in turns if AUTO_MARKER in t["content"]}
    return [t for t in turns if t["requestId"] not in excluded]


def by_request(turns):
    grouped = OrderedDict()
    for t in turns:
        stat = grouped.setdefault(t["requestId"], {"user": 0, "assistant": 0})
        stat[t["role"]] += 1
    return grouped


def batches(items, size):
    for i in range(0, len(items), size):
        yield items[i:i + size]


def main():
    args = parse_args()
    allowed = {DEFAULT_PROJECT, *args.allow_project}
    if args.project not in allowed:
        raise SystemExit("项目不在白名单，需显式 --allow-project: " + args.project)
    state = load_state(args.state)
    scanned, turns = collect(args, state)
    grouped = by_request(turns)

    if args.probe:
        stat = grouped.get(args.probe, {"user": 0, "assistant": 0})
        print("PROBE {} user={} assistant={}".format(args.probe, stat["user"], stat["assistant"]))
    if args.dry_run:
        for rid, stat in grouped.items():
            print("TURN {} user={} assistant={}".format(rid, stat["user"], stat["assistant"]))
        print("scanned={} eligible={} inserted=0 skipped=0 failed=0 dry_run=true".format(
            scanned, len(turns)))
        return 0

    if args.test_sensitive:
        marker = "probe-" + uuid.uuid4().hex[:12]
        sid = args.session or "ingest-probe"
        turns.append({
            "msgId": marker, "requestId": marker, "role": "user",
            "content": "脱敏探针 13812345678 a@b.com", "ts": int(datetime.now().timestamp() * 1000),
            "sessionId": sid, "_key": args.project + "/" + sid,
        })

    token = os.environ.get("AGENT_MEMORY_TOKEN", "")
    counters = {"inserted": 0, "skipped": 0, "failed": 0}
    seq = 0
    by_session = OrderedDict()
    for t in turns:
        by_session.setdefault(t["sessionId"], []).append(t)
    for sid, group in by_session.items():
        for chunk in batches(group, max(1, args.limit)):
            seq += 1
            payload = [{k: v for k, v in t.items() if k != "_key"} for t in chunk]
            try:
                response = rpc("memory_ingest_turns", {
                    "target": "session", "sessionId": sid, "userId": "workbuddy",
                    "tenantId": "default", "turns": payload,
                }, args.url, token, seq)
                out = tool_result(response)
            except Exception as exc:  # 网络/协议失败：整批不推进 state
                counters["failed"] += len(chunk)
                print("BATCH_FAILED session={} size={} reason={}".format(sid, len(chunk), exc),
                      file=sys.stderr)
                continue
            if "error" in out:
                counters["failed"] += len(chunk)
                print("BATCH_ERROR session={} {}".format(sid, out["error"]), file=sys.stderr)
                continue
            for k in counters:
                counters[k] += int(out.get(k, 0))
            if int(out.get("failed", 0)) > 0:
                print("BATCH_PARTIAL session={} failed={} (state 未推进)".format(
                    sid, out["failed"]), file=sys.stderr)
                continue
            for t in chunk:
                state["imported"].setdefault(t["_key"], [])
                if t["msgId"] not in state["imported"][t["_key"]]:
                    state["imported"][t["_key"]].append(t["msgId"])
            save_state(args.state, state)

    print("scanned={} eligible={} inserted={} skipped={} failed={}".format(
        scanned, len(turns), counters["inserted"], counters["skipped"], counters["failed"]))
    return 1 if counters["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
