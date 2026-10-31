# Bazaar Flipper (Fabric, Minecraft 26.2)

A client-side Fabric mod for Hypixel SkyBlock. It finds the Bazaar flips that make the most **coins per hour
right now**, and shows them in an in-game window, on a HUD, or in chat.

- **Modes:** Bazaar Flip (buy order → sell offer), Craft, Forge, Bazaar → NPC, NPC → Bazaar
- **Limits you set:** budget, max items per hour, minimum weekly volume, your expected market share, Bazaar tax tier,
  Forge slots, order vs instant buying/selling
- **Manipulation protection:** see [How it stays manipulation-resistant](#how-it-stays-manipulation-resistant)
- Uses **only the official Hypixel API** (`api.hypixel.net`, no API key needed). It does not click, buy or sell
  anything for you. It only reads public data and shows you numbers.

> Hypixel's rules on allowed modifications change over time. This mod only displays information from the
> public API, but check the current Hypixel rules yourself before using any mod.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) ≥ 0.20.1 for Minecraft **26.2**, and Java **25**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) (`0.161.0+26.2` or newer) and `bazaar-flipper-1.0.0.jar` into `.minecraft/mods`.
3. Start the game, join Hypixel, and press **B** (or type `/bzflip`).

## Build

Requires JDK 25.

```bash
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew test` runs the unit tests for the calculation core.

## Using it

| Control | What it does |
|---|---|
| Mode tabs | Choose Bazaar Flip, Craft, Forge, Bazaar → NPC or NPC → Bazaar |
| Budget | Max coins tied up at once (`10m`, `1.5b`, `750k` all work) |
| Max items/h | How many items you can realistically handle per hour (`0` = no limit) |
| Min volume/wk | Skip items traded less than this per week |
| Market share % | How much of an item's hourly volume you expect to get against other flippers (5% is a sane default) |
| Search | Filter by item name or id |
| Buy / Sell | Order (place an order and wait) or Instant (fill existing orders now) |
| Tax | 1.25% default, 1.125% / 1% with the Bazaar Flipper account upgrades |
| Sort | Profit/h, profit per item, ROI, or safest first |
| Guard | Strict / Normal / Loose hide flips above risk 30 / 50 / 70; Off shows raw prices |
| Slots / NPC ingr. | Forge slots you use (Forge), or allow buying ingredients from NPC shops (Craft) |
| History | How many hours of price history the guard uses for its medians |
| HUD / Alerts | Top flips on screen; chat alert when a new best flip appears |

Click a row to see the full breakdown (each leg's price, tax, volume, what limits the rate, warnings);
**To chat** prints it to chat.

Commands: `/bzflip`, `/bzflip top [n]`, `/bzflip mode <bazaar_flip|craft|forge|bazaar_to_npc|npc_to_bazaar>`,
`/bzflip refresh`, `/bzflip hud`, `/bzflip help`. The HUD toggle key is unbound by default (Controls → Bazaar Flipper).

### How profit per hour is calculated

Every mode is modelled as *buy the inputs → (craft / forge) → sell the output*:

- **Profit per item** = what you receive after tax − what you pay (including coin costs for Forge recipes).
- **Items per hour** is the smallest of:
  - market volume: `weekly volume / 168 × your market share`, for every leg (buy orders fill at the instasell rate,
    sell offers at the instabuy rate, and so on);
  - your **max items/h**;
  - **budget**: `budget / (cost per item × hold time)`, where hold time is 1 h, or the Forge duration;
  - **Forge slots**: `slots / forge duration`.
- **Profit per hour** = profit per item × items per hour. The breakdown shows which limit applied.

Instant buys and sells walk the real order book for one hour's quantity, so the average price includes slippage.
Order prices are the best order ± 0.1 coins (outbid or undercut).

## How it stays manipulation-resistant

Bazaar manipulation usually means making a spread look profitable when it isn't: buying out the sell offers and
relisting them high, dumping into buy orders, or placing tiny "bait" orders at silly prices. The mod defends against
this in layers (`MarketGuard`, `BookMath`, `PriceHistory` in `src/main/java/com/bazaarflipper/core`):

1. **Prices you receive are capped.** A sell price is never trusted above its recent **median + 15%**. The
   median comes from price history the mod records itself, every 2 minutes, kept for up to 16 hours and saved between
   sessions. A pump therefore can't create a fake profit. The flip is flagged **Spike** and its revenue is capped.
2. **Buy-order prices are floored** at the recent **median − 15%**. A dump can't make inputs look artificially
   cheap. These flips are flagged **Dump**.
3. **Bait orders are ignored.** A top-of-book order is skipped if it is tiny (under 2% of an hour's volume) *and*
   more than 5% away from the next level. Flagged **Bait**.
4. **Instant prices walk the whole book** for the amount you would actually trade, so one cheap 1-item offer can't
   make a flip look good.
5. **Medians instead of averages.** A few outlier samples can't move the reference price.
6. **Warm-up protection.** Until enough history exists (5 samples by default), the Hypixel `quick_status` averages
   (top 2% of volume) are the reference. An unusually wide spread is then treated as a likely pump (**Spike** + **Spread**)
   and hidden in safe mode.
7. **Liquidity filters and risk score.** Items under the minimum weekly volume are excluded. **Few orders**,
   **Thin** book, **Volatile** price, **Crowded** top of book and **New** (no history) all add to a 0–100 risk
   score. The guard hides anything above your risk limit.
8. **Data validation.** API responses are checked (`success`, finite and positive numbers, re-sorted books, crossed
   books repaired). Snapshots older than the current one, or with less than half the products, are rejected. Data
   older than 5 minutes shows a warning. Downloads are size-limited, and a corrupted history or config file is
   ignored or clamped instead of trusted.

No third-party price site is used, so there is no external source that could be manipulated or go down.

## Files

- `config/bazaarflipper/config.json`: all settings, including advanced ones not in the UI:
  `guardTolerancePercent`, `minHistorySamples`, `alertMinProfitPerHour`, `refreshSeconds` (minimum 20),
  `hudX`, `hudY`, `hudLines`
- `config/bazaarflipper/history.bin.gz`: recorded price history
- `config/bazaarflipper/items_cache.json`: cached item names and NPC sell prices
- `config/bazaarflipper/skyblock_data.json` (optional): overrides the bundled recipe data

## Recipe data

Crafting recipes, Forge recipes and NPC shop prices come from the
[NotEnoughUpdates item repository](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO) (MIT, © Moulberry).
They are bundled in `src/main/resources/assets/bazaarflipper/skyblock_data.json`. To refresh them:

```bash
git clone --depth 1 https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO.git neu
```

```bash
python3 -I tools/gen_skyblock_data.py neu src/main/resources/assets/bazaarflipper/skyblock_data.json
```

The calculator ignores any recipe whose output is not on the Bazaar, or whose ingredients can't be bought on the
Bazaar or from an NPC.

## Limitations

- Recipe unlocks (collections, HotM tier for the Forge) are shown in the breakdown but not checked against your profile.
- NPC shops may limit daily purchases, so NPC → Bazaar flips are flagged **NPC limit**.
- The market-share model is an estimate. Real fill speed depends on how actively you re-list.
- The Hypixel API returns only the top 30 levels of each order book.
