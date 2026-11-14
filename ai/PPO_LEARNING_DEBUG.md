# PPO learning debug — LOOSE_BALL_RUN (plain English)

What we *thought* was happening, what the `--verbose` evidence actually shows, and what to try next.

Related code: rewards in `gridiron_ai/reward.py`, soft-mask in `ACTION_SAMPLING.md`, verbose evidence sidecar in `gridiron_ai/ppo_update_evidence.py` (only runs with `--verbose`).

---

## The mental model (what we assumed)

1. **Soft-mask is only a sampling tilt.** It makes the trainer *try* pickup and empty-EZ distractors more often. It is not itself a reward. After a try, the return should update the network.
2. **Big env rewards are loud.** A touchdown pays **+4 on the scoreboard** (always — not a profile bonus). Plus curriculum bits you can get ~**+4.75** on the scoring step. A drill loss pays **−0.5** when the coach window closes as THEM.
3. **Two-step lessons should credit the opening.** Pickup then score: GAE should push a lot of that +4.75 back onto the pickup step so the opening gets a strong positive advantage.
4. **Fail openings should get negative credit.** Empty-handed endzone dash → no +4, then −0.5 at window end → opening advantage should go down.
5. **PPO then moves plain logits.** Soft-mask is off at eval. If train saw good/bad returns, unaided opening ranks / probs should move.
6. **Ball is in the observation.** Per-square `hasBall` / `distToBall` / pickup affordances are packed like everything else. PPO’s job is to use them; we do not need a special “8 bits light up” feature.
7. **scenario_a / scenario_b use baseline shaping.** Curriculum ±0.5 / +0.25 still apply. Profile extras (empty-EZ slap, approach-ball, TD amp) do **not** — those are shaped profiles after the split.

---

## What the logs actually showed

From `LOOSE_BALL_RUN` `--verbose` blocks (Passed / Failed + buffer fields):

| Assumption | Reality in the logs |
|---|---|
| Soft-mask samples both pass and fail openings | **True.** We see pickup→score *and* empty-EZ / junk fail paths. |
| Env rewards are correct | **True.** `env=+0.25` on pickup, `env=+4.75` on score, `env=-0.50` on fail window close. |
| Those env numbers are what PPO trains on | **False under normalize** (scoring `+4.75`→~`0.12`). Raw-scenario trial matched `buf`≈`env` but did not unstick openings — **reverted**. |
| Opening gets loud advantage after a TD | **Unreliable in early verbose logs.** Evidence used `buf.pos-1` before SB3 `add`, so “scoring” often showed the **pickup** slot and “opening” showed the **previous episode’s** terminal. Re-check after the index fix. |
| Fail openings get negative advantage | **Unreliable for the same off-by-one.** Re-measure with fixed `buf.pos`. |
| Policy mass / KL move after those returns | **False.** Mass flat (`0.030→0.030`), `approx_kl≈5e-5`, almost no `‖θ‖` change. |
| Soft-mask explains eval stuck while train “works” | **Still plausible.** Train can collect +4.75 under bias while **plain** logits barely move — eval (bias off) stays stuck. |

So the story is not “we forgot to reward it.” It is: **raw rewards fire; the learning signal that reaches openings is tiny; the PPO step barely moves the plain policy.**

---

## Assumptions that were wrong (or oversold)

### 1. “+4.75 means the opening learns”

**Assumed:** big TD reward → GAE → opening update.  
**Actually:** VecNormalize compresses +4.75; the residual mostly lands on the **scoring** step (`adv≈+2.5`). Opening stays ~advantage-neutral. Two-step credit is *not* automatically loud.

### 2. “−0.5 on fail teaches don’t dash empty into the EZ”

**Assumed:** THEM terminal −0.5 punishes the empty-EZ opening.  
**Actually:** that −0.5 is on the **last** step of the episode (window close), not on the empty-EZ submit. After VecNormalize it often looks like **`buf(rew≈0)`**. Opening fail advantages were not negative. (We verified env still pays −0.5; the buffer scale is the issue, not a missing print.)

### 3. “−0.5 empty-EZ slap”

**Assumed (in conversation):** we penalize empty EZ by −0.5.  
**Actually:** scenario board phase is **baseline** — `empty_scoring_endzone_penalty` is **0**. The −0.5 you see is **scenario terminal loss**, delayed. Shaped profiles use a **0.2** empty-EZ slap, not 0.5.

### 4. “Soft-masking empty-EZ teaches that it’s bad”

**Assumed (briefly, then walked back):** soft-mask somehow labels the move.  
**Actually:** soft-mask only raises **try rate**. Same bias on pickup and empty-EZ means train *sees* both; only returns can separate them. Soft-mask does **not** write “this is good” into the weights by itself.

### 5. “Ball visibility is the bug”

**Assumed (overstated earlier):** model can’t see the ball.  
**Actually:** ball channels are packed and tested. Same shape as other square facts. The failure mode is **learning signal / priors (TD_RUN endzone geometry)**, not a missing obs bit.

### 6. “Printing PPO ‖θ‖/KL on every drill means each drill updated”

**Assumed (UI confusion):** each block is its own update.  
**Actually:** one PPO update covers many drills in the rollout. The same `approx_kl` / `‖θ‖` was repeated on every block. Sidecar now prints **one** `PPO update` line per update.

---

## Why this can stay stuck (short)

1. **VecNormalize on drills (fixed for scenarios)** — was crushing +4.75/−0.5 into mush. Scenario train is raw now; full-game still normalizes.
2. **Credit assignment** — loud `adv` on score, not on pickup; fail −0.5 not on the empty-EZ click. Normalize off does not guarantee openings light up — only that GAE sees the real ±.
3. **Soft-mask crutch** — successes still collectable under +2.5 bias while plain ranks stay cold at eval.
4. **Shared TD_RUN prior** — “run toward scoring endzone_y” was paid in cash elsewhere; empty-handed EZ still looks hot when unaided.

### Same rules in games — why this isn’t a contradiction

Games and drills share the **objective** (score / don’t lose the window). They do not share the **optimization noise floor**. Drills are short, sparse, and were getting their ± erased before PPO could discover which opening correlated with the return. Games are long and mixed — normalize keeps updates stable there. The bet is: discover the link where the signal is hearable; keep using it where the signal is quieter. That is not “games use different rewards.”

---

## Trial: scenario `norm_reward=False` — **reverted**

Tried raw scenario rewards so GAE would “hear” ±4.75 / −0.5. Hard openings
(LOOSE_BALL_RUN / TD_RUN) stayed flat; easier drills (e.g. THROW_CLOSER_RANGE)
regressed. **Normalize is back on** for scenario and full-game segments (same as
pre-trial). Scale was not the missing piece for the stuck openings.

### Overnight while raw was on (kept for the record)

~27 cycles. LOOSE_BALL_RUN stayed at **d0**, gate fails accumulating.

| Meter | Result |
|---|---|
| Eval winrate | **0** (last_eval history all zeros) |
| Opening `E[p]` (lesson row) | stuck ~**0.013–0.015** |
| Opening rank of lesson | stuck ~**17–19** (of ~145 legals) |
| Train (soft-mask on), last 50MB draws | ~**5.9% US** (5286 US vs 84489 THEM at d0) |

**Conclusion:** raw rewards ≠ representation/bind fix. Reverted.

### Why soft-mask doesn’t break the EZ habit

LOOSE_BALL_RUN used to soft-mask **pickup and every empty-EZ with the same +2.5**. Softmax relative order between those two was unchanged. TD_RUN already made empty-EZ hotter → train ~6% US.

**Landed:** empty-EZ soft-mask only with probability **1/4** per ask (`LooseBallRunTrainingScenario`). Pickup still always soft-masked. This is an experiment to raise US rate — **not** a promise that `5×` more `+5`s will move printed mass (overnight already had thousands of US and eval `E[p]` stayed ~0.015).

With fixed buffer indexing + raw rewards, a Pass opening can show `adv≈+5` and still `mass Δ≈0` on that update (1/3840 dilution). Overnight flat `E[p]` means the deeper worry is **plain logits not absorbing train wins**, not only “too few +5s.”

---

## Next steps

1. Train with empty-EZ soft-mask at 1/4; watch train US rate and eval `opening_expected_p` / rank.
2. If US rises but plain `E[p]` still flat: train wins aren’t sticking (soft-mask crutch / opposing EZ gradient) — not fixed by more of the same.
3. **Landed:** scenario TRAIN opening mispick **−1.0** when a `passingLegal` row was available and they took something else (`SCENARIO_OPENING_NON_PASSING_PENALTY`). Verbose fail openings should show `env=-1.00` on the first click.
4. Soft-mask anneal only after plain mass actually moves.

---

## How to read a verbose block

```text
[gridiron] PASS-edge buffer …          ← mean buf rew/adv on score + prior (pickup)
[gridiron] Passed the drill. …
  steps: run(…) [rank= p= reward=…] → …   ← same annotate_move_label as eval turn1
  opening: run(…) [rank= p=] env=… buf=… mass …→… (Δ=±0.000012, rank …→…)
[gridiron] Failed the drill. …
  expected: run(…)* [rank= p=]            ← PASS rows, same format
  opening: …
  last: env=-0.50 buf=…                 ← only if terminal ≠ opening (multi-step / window loss)
[gridiron] PPO update … passed=N failed=M; ‖θ‖ … approx_kl=…
```

`env=` = what `step_reward` paid. `buf=` = rollout buffer after VecNormalize (when on). Mass / rank = **plain** (unbiased) policy; move listings share eval’s `label [rank= p=]` (`annotate_move_label`). Mass Δ prints six decimals so tiny moves are not `0.000`. No separate `scoring:` line (one-shot passes already show the score on `opening` / `steps`).

### Logging bug (fixed): `buf.pos - 1` before `add`

SB3 order is `env.step` → `callback.on_step` → `rollout_buffer.add`. Recording `pos-1` in `on_step` tagged each move with the **previous** transition. Symptom in your paste: pass opening `env=+0.25` but `buf=-0.50`; scoring `env=+4.75` but `buf=+0.25`; every opening sharing the same `adv=-0.1003`. After fix, record `pos` (write slot). **Re-run `--verbose` before trusting buf/adv again.** Mass Δ=0 on those lines was still real (plain policy flat).
