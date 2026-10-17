# Team summaries

Canonical data (loaded by the catalog API):
[`backend/src/main/resources/catalog/team-summaries.json`](../backend/src/main/resources/catalog/team-summaries.json).

Ids match [`TeamCatalog`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/TeamCatalog.kt) / [`GridironTeamCatalog`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/GridironTeamCatalog.kt) slugs (e.g. `ironhide`, `gilded-crown`).

## Data generation & methodology

Drafted with an LLM from the team catalog, then reviewed by hand.

- **Ratings:** Speed, Bash, Agility and Armor are 1–5 ratings derived from the roster stats in `GridironTeamCatalog` — Move, Block dice, Throw and Armour targets, position counts — plus each team's abilities (e.g. Relentless, Anchored, Thirst).
- **Playstyle:** each team sits on a bash-vs-dash spectrum read off those stats. `summary`, `keyStrengths`, `weaknesses`, `recommendedStrategy` and `complexityLabel` (Beginner, Intermediate, Advanced) follow from the same stats and abilities.

## Regenerating the dataset

Paste the prompt below into Gemini (or similar), then drop the JSON into `team-summaries.json`. Ids must be the catalog kebab-case slugs ([`TeamSummaryCatalog`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/TeamSummaryCatalog.kt) does not remap); display names come from the roster catalog. Confirm all 23 roster teams are present (and no extras) against [`GridironTeamCatalog`](../backend/src/main/kotlin/com/mhaefele/gridiron/model/catalog/GridironTeamCatalog.kt).

### Prompt

```
Generate a JSON array with one entry for each of the 23 teams in the attached team catalog (roster stats, positions and abilities): Amazons, Ironhides, Doomsworn, Dark Elves, Dwarves, Silverleaf, Gnomes, Goblins, Halflings, Humans, Gilded Crown, Bloodsworn, Scalekin, Graveborn Horror, Norse, Rotsworn, Ogres, Orcs, Ratfolk, Gremlins, Restless Dead, Vampires, Wood Elves.

Derive the 1–5 ratings from the attached stats and abilities only; describe playstyle along a bash-vs-dash spectrum.

Use the following TypeScript interface structure for each team:

export interface TeamSelectionData {
  id: string; // Kebab-case singular slug (e.g. "gilded-crown", "ironhide", "dwarves", "orcs")
  bashVsDash: number; // 1.0 (Pure Heavy Bash) to 5.0 (Pure Heavy Dash); decimals allowed
  bashVsDashLabel: string; // e.g. "Heavy Bash", "Balanced Hybrid", "Control Hybrid"
  ratings: {
    speed: number;   // 1 to 5 scale
    bash: number;    // 1 to 5 scale
    agility: number; // 1 to 5 scale
    armor: number;   // 1 to 5 scale
  };
  complexityLabel: 'Beginner' | 'Intermediate' | 'Advanced';
  summary: string; // 1-sentence playstyle overview
  keyStrengths: string[]; // 2 distinct bullet points
  weaknesses: string[]; // 1-2 distinct bullet points
  recommendedStrategy: string; // 1-2 sentences on how to win with the team
}

Ensure output is strictly valid JSON without missing teams, missing fields, or numeric complexity scores.
```
