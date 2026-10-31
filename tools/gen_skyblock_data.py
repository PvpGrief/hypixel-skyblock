#!/usr/bin/env python3
"""Generate src/main/resources/assets/bazaarflipper/skyblock_data.json from a
checkout of https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO.

Usage:
    git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO.git neu
    python3 -I tools/gen_skyblock_data.py neu src/main/resources/assets/bazaarflipper/skyblock_data.json

Only static data goes into the file (recipes, forge recipes, NPC shop prices,
display names). Live prices always come from the official Hypixel API at
runtime, and the mod drops any recipe whose items are not on the Bazaar.
"""
import glob
import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone

COLOR = re.compile("§.")
SLOTS = [r + c for r in "ABC" for c in "123"]


def load_stock_map(root):
    path = os.path.join(root, "constants", "bazaarstocks.json")
    if not os.path.exists(path):
        return {}
    return {e["id"]: e["stock"] for e in json.load(open(path, encoding="utf-8"))}


def to_bazaar_id(neu_id, stock_map):
    """Convert a NEU internal name to the Hypixel Bazaar product id."""
    if neu_id in stock_map:
        return stock_map[neu_id]
    m = re.fullmatch(r"([A-Z0-9_]+)-(\d+)", neu_id)
    if m:  # INK_SACK-3 -> INK_SACK:3
        return f"{m.group(1)}:{m.group(2)}"
    m = re.fullmatch(r"([A-Z0-9_]+);(\d+)", neu_id)
    if m:  # SHARPNESS;5 -> ENCHANTMENT_SHARPNESS_5 (pets also match, they are simply not on the Bazaar)
        return f"ENCHANTMENT_{m.group(1)}_{m.group(2)}"
    return neu_id


def parse_stack(s, stock_map):
    """'DIAMOND:32' -> ('DIAMOND', 32.0); returns None for empty slots."""
    if not s:
        return None
    if ":" in s:
        item, count = s.rsplit(":", 1)
        try:
            n = float(count)
        except ValueError:
            item, n = s, 1.0
    else:
        item, n = s, 1.0
    if item == "SKYBLOCK_COIN":
        return ("SKYBLOCK_COIN", n)
    return (to_bazaar_id(item, stock_map), n)


def main(root, out_path):
    stock_map = load_stock_map(root)
    names, crafts, forge = {}, [], []
    npc_best = {}

    for f in sorted(glob.glob(os.path.join(root, "items", "*.json"))):
        try:
            d = json.load(open(f, encoding="utf-8"))
        except (ValueError, OSError):
            continue
        neu_id = d.get("internalname")
        if not neu_id:
            continue
        out_id = to_bazaar_id(neu_id, stock_map)
        name = COLOR.sub("", d.get("displayname", "")).strip()
        # Enchanted books all share the display name "Enchanted Book"; the mod builds a better
        # name from the id (ENCHANTMENT_SHARPNESS_5 -> "Sharpness V"), so skip them here.
        if name and not neu_id.endswith("_NPC") and name != "Enchanted Book":
            names[out_id] = name
        req = COLOR.sub("", d.get("crafttext", "") or "").strip()
        npc_name = name if neu_id.endswith("_NPC") else None

        recipes = list(d.get("recipes", []))
        if isinstance(d.get("recipe"), dict):
            r = dict(d["recipe"])
            r.setdefault("type", "crafting")
            recipes.append(r)

        for r in recipes:
            t = r.get("type", "crafting")
            if t == "crafting":
                inputs = defaultdict(float)
                coins = 0.0
                for slot in SLOTS:
                    st = parse_stack(r.get(slot, ""), stock_map)
                    if st is None:
                        continue
                    if st[0] == "SKYBLOCK_COIN":
                        coins += st[1]
                    else:
                        inputs[st[0]] += st[1]
                if not inputs:
                    continue
                target = to_bazaar_id(r.get("overrideOutputId", neu_id), stock_map)
                count = float(r.get("count", 1) or 1)
                if target in inputs and len(inputs) == 1:
                    continue  # degenerate (e.g. same item in and out)
                entry = {"out": target, "n": count, "in": dict(inputs)}
                if coins:
                    entry["coins"] = coins
                if req:
                    entry["req"] = req
                crafts.append(entry)
            elif t == "forge":
                inputs = defaultdict(float)
                coins = 0.0
                for s in r.get("inputs", []):
                    st = parse_stack(s, stock_map)
                    if st is None:
                        continue
                    if st[0] == "SKYBLOCK_COIN":
                        coins += st[1]
                    else:
                        inputs[st[0]] += st[1]
                if not inputs:
                    continue
                target = to_bazaar_id(r.get("overrideOutputId", neu_id), stock_map)
                entry = {"out": target, "n": float(r.get("count", 1) or 1), "in": dict(inputs),
                         "sec": int(r.get("duration", 0) or 0)}
                if coins:
                    entry["coins"] = coins
                forge.append(entry)
            elif t == "npc_shop":
                cost = r.get("cost", [])
                if not cost or not all(c.startswith("SKYBLOCK_COIN") for c in cost):
                    continue  # only pure-coin purchases
                coins = sum(parse_stack(c, stock_map)[1] for c in cost)
                res = parse_stack(r.get("result", ""), stock_map)
                if res is None or res[1] <= 0 or coins <= 0:
                    continue
                unit = coins / res[1]
                prev = npc_best.get(res[0])
                if prev is None or unit < prev["coins"]:
                    npc_best[res[0]] = {"id": res[0], "coins": round(unit, 4), "npc": npc_name or ""}

    # De-duplicate identical crafting recipes.
    seen, unique = set(), []
    for c in crafts:
        key = json.dumps(c, sort_keys=True)
        if key not in seen:
            seen.add(key)
            unique.append(c)

    try:
        sha = subprocess.run(["git", "-C", root, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
    except OSError:
        sha = ""
    data = {
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "NotEnoughUpdates-REPO" + (f"@{sha}" if sha else ""),
        "crafts": unique,
        "forge": forge,
        "npcShop": sorted(npc_best.values(), key=lambda e: e["id"]),
        "names": dict(sorted(names.items())),
    }
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, ensure_ascii=False, separators=(",", ":"))
    print(f"crafts={len(unique)} forge={len(forge)} npcShop={len(npc_best)} names={len(names)} -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
