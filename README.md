# ironmanantha-training

A self-contained, single-file web app for tracking a full **Ironman** build — from learning to swim through to race day. No build step, no dependencies, no backend. Just open `index.html`.

Live-deployable on Netlify (or GitHub Pages) in one click.

---

## What it does

- **Calendar view** (Monday-start) covering the full ~70-week plan. Each day shows colour-coded discipline ticks and a completion ring that fills as you check sessions off.
- **Click any day** to open its session list. Every item — swim, bike, run, strength, fuel, recovery — has a checkbox. Progress is saved automatically.
- **Distance-based targets** that grow week-on-week and pull back every 4th (recovery) week.
- **Race countdown, on-plan completion %, current streak, and week-of-plan** in the header.
- **Two tabs:** *Schedule* (the training calendar) and *Nutrition* (a millet-forward, vegetarian, no-egg meal framework + in-session fuelling guide).

## The plan it encodes

Built for a beginner swimmer with a usable bike/run base, ~14 hrs/week, vegetarian (no eggs), targeting a first full Ironman roughly 15–16 months out. Phased: **Foundation → Base → Build → Peak → Taper → Race.**

Daily rhythm:

| When | Pattern |
|---|---|
| First few days | Run (AM) + Cycle (PM) |
| From swim coaching | Swim + Run (AM), Cycle 10–20 km (PM) |
| From the July commute switch | Cycle-commute + Swim (AM), Run (PM) |
| Weekends | Longer Cycle + Run in the morning |

At least two sessions a day, running every day, with short strength twice a week for injury-proofing.

## Configuration

Open `index.html` and edit the constants near the top of the `<script>`:

```js
var START   = d(2026,6,15);  // first day of the plan
var RACE    = d(2027,10,18); // your registered race date  <-- change this
var COMMUTE = d(2026,7,1);   // when mornings switch to cycle-commute + swim
```

Everything — phases, taper, countdown, distances — recalculates from these.

## Deploy

**Netlify (recommended):** connect this repo in the Netlify dashboard, or drag the folder onto the deploy zone. `netlify.toml` publishes the root.

**GitHub Pages:** Settings → Pages → deploy from `main` / root.

## Notes

- Progress is stored in the browser's `localStorage` — it's per-device and not synced across phone/laptop.
- This is general training/nutrition guidance, **not medical advice**. Clear high training loads and any supplements with a doctor, and get baseline blood work (ferritin, B12, vitamin D).

---

*Built as a personal training tracker. Adjust freely.*
