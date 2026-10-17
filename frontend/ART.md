# Goblins & Gridirons — Art

Every art file in `src/assets/`: what it is, how it was made, and how to make more that match. How files are loaded (`AssetRegistry`, globs, fallbacks) is in [ARCHITECTURE.md § Assets](ARCHITECTURE.md#assets).

**Prompts only — no reference images.** A prompt below plus its table row is the whole recipe. Generation is not deterministic: the committed file is the artifact; the prompt reproduces the intent, not the pixels.

## Inventory

| Set | Path | Count | Made by | Format |
|---|---|---|---|---|
| [Player tokens](#player-tokens-and-portraits) | `tokens/{teamId}/{position}-{standing\|prone}.png` | 86 × 2 | AI | 512² PNG (prone = copy of standing) |
| [Player portraits](#player-tokens-and-portraits) | `cards/players/{teamId}/{position}.png` | 86 | Standing token, upscaled | 640² PNG |
| [Team hero cards](#team-hero-cards) | `cards/teams/{teamId}-hero.webp` | 23 | AI | 3:4 WebP, 600×800 |
| [Team crests](#hand-drawn-svg) | `cards/teams/{teamId}-crest.svg` | 23 | Hand-written SVG | 64² viewBox |
| [Ability badges](#ability-badges) | `ui/abilities/{ability-slug}.png` | 30 | AI | 1024² PNG, transparent |
| [Bonus-play badges](#bonus-play-and-opponent-badges) | `ui/bonuses/{bonus-slug}.png` | 20 | AI | 1024² PNG |
| [Opponent icons](#bonus-play-and-opponent-badges) | `opponents/kinds/*.png`, `opponents/profiles/*.png` | 8 | AI | 1024² PNG |
| [Pitch boards](#pitch) | `pitch/boards/{pitch}.png` | 2 | AI | 1024×1536 PNG |
| [Pitch tiles](#pitch) | `pitch/tiles/{pitch}-{surface\|endzone-home\|endzone-away}.png` | 6 | AI | 256² PNG, tileable |
| [Challenge faces + back](#hand-drawn-svg) | `cards/challenge/faces/{condition-slug}.svg`, `cards/challenge/back.svg` | 16 | Hand-written SVG | 240×336 viewBox |
| [UI icons](#hand-drawn-svg) | `ui/{ball,block-face,d6-face,d8-face}.svg` | 4 | Hand-written SVG | — |

Slugs are kebab-case of the enum display name (`assets/slug.ts`).

## Order of work

1. **[House style](#house-style)** — shared by every AI set.
2. **[Team styles](#team-styles)** — palette, motifs, avoid list per team.
3. **[Position briefs](#position-briefs)** — one look per position, written from the existing tokens.
4. **Player tokens** from the position briefs (portrait = standing token).
5. **Team hero cards** from the hero position's brief plus teammates' briefs.

Changing a position's look means: edit its brief → regenerate its token/portrait → regenerate any hero card that shows it. Heroes never introduce a look the tokens don't have.

Badges, opponent icons, and pitch art depend only on the house style.

## House style

Painterly, gritty fantasy-sports illustration: weathered metal, worn leather, dirt and grass, warm torch or stadium light. Frames are **riveted metal with studs** and small four-point corner spikes. No logos, no product name, no text except a team name plaque on hero cards.

Avoid on every set: eight-pointed stars, radiating-arrow stars, skull-and-X runes, three-circle fly sigils, triangle-and-three-lines sigils.

## Team styles

The ring colour is the token's frame; palette and motifs apply to tokens and hero cards alike.

| Team (`teamId`) | Ring / palette | Motifs | Avoid (beyond house) | Token status |
|---|---|---|---|---|
| Amazons (`amazon`) | Teal, bronze | Feathers, jade discs, stepped-pyramid borders | — | OK |
| Bloodsworn (`bloodsworn`) | Crimson, dark bronze, blackened iron | Blood drop in a cracked circle, crossed axes, iron horns | Skull pauldrons with star backing | Redrawn |
| Dark Elves (`dark-elf`) | Violet, black, silver | Thorned silver filigree, crescent moons | — | OK |
| Doomsworn (`doomsworn`) | Purple, dark bronze | Ram horns, cracked chains, plain shields | Star studs on pauldrons | Redrawn |
| Dwarves (`dwarves`) | Deep blue, bronze | Axes, anvils, braided knots | — | OK |
| Gilded Crown (`gilded-crown`) | Royal purple, bronze | Crowns, invented coats of arms, tassels | — | OK |
| Gnomes (`gnome`) | Moss green, earth brown, bronze | Leaves, mushrooms, acorns | — | OK |
| Goblins (`goblin`) | Dark green, black, yellow-green | Spiked scrap metal, crude stitched flags | — | OK |
| Graveborn (`graveborn`) | Steel blue, bone grey, spectral teal | Stitches, tombstones, lanterns | — | OK |
| Gremlins (`gremlin`) | Bronze, rust orange, sickly green | Mushrooms, scrap, bunting | — | OK |
| Halflings (`halfling`) | Leaf green, brown, bronze | Pies, mugs, hedges | — | OK |
| Humans (`human`) | Crimson, gold | Griffins, laurels | — | OK |
| Ironhides (`ironhide`) | Black iron, rust red, grey | Riveted plates, chains, anvils | — | OK |
| Norse (`norse`) | Ice blue, steel, fur brown; snowy pitch | Wolf pelts, longship prows, invented knotwork | — | OK |
| Ogres (`ogre`) | Brown leather, bronze | Meat hooks, gut plates, chains | — | OK |
| Orcs (`orcs`) | Lime green, black | Crude teeth glyphs, spikes | — | OK |
| Ratfolk (`ratfolk`) | Rust brown, glowing lime green | Cogs, pipes, grates | — | OK |
| Restless Dead (`restless-dead`) | Bone, dark purple, bronze | Sarcophagus gold, scarabs | — | OK |
| Rotsworn (`rotsworn`) | Olive, rust, bronze | Rust, bubbles, cracked bells | — | Redrawn |
| Scalekin (`scalekin`) | Teal, gold | Invented jungle stone glyphs, sun discs | Stepped-temple glyph blocks | OK |
| Silverleaf (`silverleaf`) | Sky blue, silver, white | Leaves, four-point stars, crescents | — | OK |
| Vampires (`vampire`) | Blood red, black | Bats, candelabra, rose thorns | — | OK |
| Wood Elves (`wood-elf`) | Forest green, gold | Leaves, vines, antlers | — | OK |

## Position briefs

Written from the existing tokens (Redrawn teams: from the new tokens). Each line is the `{look}` slot in the token prompt.

| Team | Position | Look |
|---|---|---|
| amazon | blitzer | Athletic warrior woman, dark top-knot, teal-and-bronze scaled plate with spiked pauldrons, fists clenched |
| amazon | blocker | Broad warrior woman, dark braid, heavy teal-and-bronze plate |
| amazon | linewoman | Warrior woman, long dark hair, teal leather with bronze bands |
| amazon | thrower | Lithe warrior woman, teal headband, light teal-and-bronze armour, ball raised to throw |
| bloodsworn | gorehorn | Ram-horned beast-man, dark brown fur, crimson plate with bronze rivets |
| bloodsworn | marauder | Bearded mohawked human, crimson-and-bronze plate with plain riveted pauldrons |
| bloodsworn | skullreaver | Hulking red-skinned brute, bronze skull-faced great-helm with two long curved horns, spiked crimson plate with plain pauldrons, chains |
| dark-elf | blitzer | Pale elf, white hair, spiked black-and-violet plate |
| dark-elf | bloodblade | Pale elf woman, long black hair, twin curved blades, light violet leathers |
| dark-elf | lineman | Pale elf, long white hair, black plate with violet trim |
| dark-elf | runner | Pale elf, white ponytail, light black-and-violet leathers, ball under arm |
| doomsworn | beastkin | Ram-horned beast-man, grey-black fur, purple plate with bronze trim |
| doomsworn | sworn-blocker | Huge ram-horned brute, grey-green skin, tusked snarl, heavy purple-and-bronze plate, broken chain on one wrist |
| dwarves | blitzer | Dwarf, horned helm, braided orange beard, blue-and-bronze plate |
| dwarves | doom-seeker | Dwarf, tall orange crest of hair, bare tattooed chest, twin axes |
| dwarves | lineman | Dwarf, huge braided orange beard, heavy bronze-and-blue plate |
| dwarves | runner | Dwarf, braided beard, light blue leathers, ball tucked under arm |
| gilded-crown | blitzer | Dark-haired human noble, purple-and-bronze plate |
| gilded-crown | bodyguard | Heavy-set dark-haired human, thick purple-and-bronze plate |
| gilded-crown | lineman | Blond human, purple-and-bronze plate |
| gilded-crown | thrower | Blond human, light purple-and-bronze armour, arm outstretched |
| gnome | beastmaster | Grey-bearded gnome, pointed green hat, leather armour, whip |
| gnome | illusionist | Gnome, pointed hat, hands crackling with green magic |
| gnome | lineman | White-bearded gnome, pointed green hat, bronze-and-leather armour |
| gnome | oakwalker | Walking tree giant, mossy bark body, leafy crown, carved face |
| gnome | woodland-fox | Red fox in small green-and-bronze barding |
| goblin | goblin | Small scrawny goblin, ragged dark armour |
| goblin | troll | Hulking green troll, black spiked plate with yellow-green trim, fanged grin |
| graveborn | flesh-golem | Hulking grey stitched golem, spiked iron collar |
| graveborn | ghoul | Gaunt grey ghoul, crouched low |
| graveborn | werewolf | Dark-furred wolf beast in steel-blue plate |
| graveborn | wraith | Glowing spectral blue-green ghost |
| graveborn | zombie | Rotting zombie in tattered steel-blue gear |
| gremlin | bounder | Tiny gremlin riding a bouncing orange fungus |
| gremlin | gremlin | Tiny green gremlin |
| gremlin | rattle-cart | Ramshackle wooden cart with a spiked roller, gremlins aboard |
| gremlin | spore-slinger | Gremlin with a sack of glowing orange spore bombs |
| gremlin | stilt-runner | Gremlin on tall wooden stilts |
| halfling | catcher | Young curly-haired halfling, green padded gear, ball in hands |
| halfling | hefty | Stout halfling, padded bronze-and-green armour |
| halfling | lineman | Cheerful curly-haired halfling, green padded armour |
| halfling | oakwalker | Walking tree giant, mossy bark body, leafy crown, carved face |
| human | blitzer | Square-jawed dark-haired human, crimson-and-gold plate |
| human | catcher | Helmeted human, light crimson armour, reaching for the ball |
| human | lineman | Human, crimson-and-gold plate |
| human | thrower | Blond human, light crimson-and-gold armour, arm outstretched |
| ironhide | goblin | Small goblin in rusted gear |
| ironhide | ironhide | Massive grey-skinned orc in black iron plate with rust-red trim |
| norse | ale-hog | Armoured boar in blue-steel barding |
| norse | berserker | Blond braided human, bare arms, axe |
| norse | lineman | Bearded blond human, blue-steel armour with fur |
| norse | valkyrie | Warrior woman, winged helm, blue-steel armour |
| norse | wolfcoat | Dark wolf-beast in blue-steel plate |
| ogre | grubling | Small scrawny orange-brown creature |
| ogre | ogre | Enormous pot-bellied ogre, spiked bronze shoulder plates |
| orcs | blitzer | Green orc, black spiked plate with lime trim |
| orcs | bruiser | Huge green orc, heavy black spiked plate |
| orcs | lineman | Green orc, black plate |
| orcs | thrower | Green orc, lighter black armour, ball in hand |
| ratfolk | blitzer | Rat-man, rusted brown armour, glowing green accents |
| ratfolk | lineman | Rat-man, brown leathers, glowing green studs |
| ratfolk | sewer-runner | Lean rat-man, long tail, light leathers |
| ratfolk | thrower | Rat-man clutching the ball, brown armour |
| restless-dead | ghoul | Gaunt grey ghoul |
| restless-dead | mummy | Hulking bandaged mummy |
| restless-dead | skeleton | Skeleton in dark purple-and-bronze plate |
| restless-dead | wight | Skeletal warrior, glowing eyes, heavy dark plate |
| restless-dead | zombie | Rotting green zombie |
| rotsworn | lineman | Sickly pale-green human, rusted olive great-helm with one round eye slit, rusted olive armour, cracked bell at the belt |
| rotsworn | plaguehorn | Ram-horned beast-man, mangy brown fur with boils, rusted olive armour, cracked bell at the shoulder |
| rotsworn | pustule-brute | Bloated giant, two stubby horns, pale green-grey skin with sores, rusted olive plates, cracked bell on a neck chain |
| scalekin | darter | Slim teal lizard-folk, long tail, gold bands |
| scalekin | scalebrute | Hulking teal lizard warrior, gold armour, head crest |
| scalekin | shade-darter | Green chameleon-like lizard-folk, curled tail |
| silverleaf | blitzer | White-haired elf, ornate silver-and-blue plate |
| silverleaf | catcher | Blond elf, headband, light blue-and-silver armour, reaching for the ball |
| silverleaf | lineman | Blond elf, silver-and-blue armour |
| silverleaf | thrower | Blond elf, white-and-blue armour, ball raised |
| vampire | blitzer | Pale vampire, dark slicked hair, black-and-red plate, high collar |
| vampire | runner | Pale vampire, white hair, light red-and-black leathers |
| vampire | thrall | Haggard human servant, ragged red-and-black gear |
| vampire | thrower | Pale vampire, black-and-red armour, cape, ball raised |
| wood-elf | catcher | Blond elf, light green leathers, reaching for the ball |
| wood-elf | glade-dancer | Black-haired elf woman, twin curved blades, green leathers |
| wood-elf | lineman | Blond elf, green-and-gold armour |
| wood-elf | thrower | Blond elf, green leathers, ball raised |

## Player tokens and portraits

The board clips every token to the circle inscribed in the image and draws its own home/away ring on top (`render/Token.tsx`). Anything outside that circle is never seen, so the art fills it edge to edge. The board ring covers most of the art's own band, so the player's armour colours carry the team at game size.

> Painterly fantasy football player token, square image, steep high-angle view from above. A circular medallion that touches all four edges of the image, with a riveted, clearly saturated *{ring}* metal band right at its edge (about one twelfth of the width); no spikes. Inside, a muddy green grass pitch with only a few faint straight chalk lines. Player: *{look}*, tight close-up crop filling almost the whole circle, lunging up and forward toward the viewer with strong foreshortening — head, shoulders and fists closest to the camera; dynamic, three-dimensional, not a flat symmetrical pose. Bright, warm key light from above so the figure reads clearly at small size; no murky shadows. The armour is plain riveted metal; its only decorations are *{one or two motifs, each placed: "one small engraved blood drop on the chest"}*. Every other armour surface, including both pauldrons, is smooth and undecorated. No text. Corners outside the circle plain white.

Name only the decorations you want. Listing forbidden symbols (stars, sigils) in the prompt makes the model paint them; check the result against the avoid lists instead.

Export the standing image at 512² as both `-standing` and `-prone` — the board rotates and squashes prone tokens itself (`Token.tsx`), so every team's prone file is a copy. Corners need no cleanup (the board clips them). The portrait is the same image at 640².

Check every token at game size (≈64 px, clipped, with the board ring) beside an existing one — detail that reads at 1024 px disappears on the board.

## Team hero cards

| Team (plaque) | Hero position | Behind |
|---|---|---|
| AMAZONS | blitzer | blocker, linewoman |
| BLOODSWORN | skullreaver | two marauders |
| DARK ELVES | bloodblade | blitzer, runner |
| DOOMSWORN | sworn-blocker | two beastkin |
| DWARVES | doom-seeker | blitzer, lineman |
| GILDED CROWN | bodyguard | thrower, blitzer |
| GNOMES | oakwalker | illusionist, woodland-fox |
| GOBLINS | troll | three goblins |
| GRAVEBORN | flesh-golem | werewolf, wraith |
| GREMLINS | rattle-cart | gremlin, stilt-runner |
| HALFLINGS | oakwalker | hefty, lineman |
| HUMANS | blitzer | lineman, thrower |
| IRONHIDES | ironhide | goblin |
| NORSE | wolfcoat | valkyrie, ale-hog |
| OGRES | ogre | two grublings |
| ORCS | bruiser | blitzer, lineman |
| RATFOLK | sewer-runner | blitzer, lineman |
| RESTLESS DEAD | mummy | skeleton, zombie |
| ROTSWORN | pustule-brute | plaguehorn, lineman |
| SCALEKIN | scalebrute | darter, shade-darter |
| SILVERLEAF | thrower | catcher, blitzer |
| VAMPIRES | blitzer | two thralls |
| WOOD ELVES | glade-dancer | catcher, thrower |

> Painterly gritty fantasy sports team card, 3:4 portrait. A rectangular ornate riveted frame that fills the entire image edge to edge, in *{palette}*, decorated only with *{motifs}*. Arched window at the top showing the hero player in a crouched ready stance holding a stitched spiked leather football, inside a torch-lit stadium arena with blurred crowd and plain unmarked banners. Hero player: *{hero position look}*. Behind, smaller and slightly blurred: *{each teammate's look}*. A large carved name plaque at the bottom reads exactly "*{PLAQUE}*". No other text, letters or logos anywhere; no other symbols on the frame or armour.

As with tokens, name only the decorations you want and check the result against the avoid lists. Reroll when the plaque is misspelled, small or scratched (ask for "big bold raised capital letters, no marks on the plaque"), or when the frame is a shaped shield with background showing. Big non-humanoid heroes (tree giants, the rattle-cart) work better with teammates *in front of* them than behind.

Export 600×800 WebP (quality 85).

## Ability badges

Diamond frame, bronze with blue inlay and studs; dark interior. One badge per `PlayerAbility`.

> Painterly gritty fantasy sports ability badge, square image with a diamond-shaped riveted bronze frame inlaid with deep blue, studs and four-point spikes at the corners. Inside, on a dark background: *{subject}* — a single clear symbol of the ability, gold and bronze tones with one accent colour. Forbidden: house avoid list, no text. Transparent outside the diamond. 1024².

Subject = the ability's effect in one image (e.g. Sure Scoop: gauntleted hands scooping the ball; Anchored: armoured boots braced in cracked earth).

## Bonus-play and opponent badges

Hexagon frame, battered steel with green inlay and studs; dark interior with green energy. One per `BonusPlay`; opponent icons reuse the frame for computer kinds (`heuristic`, `lookahead`, `onnx`) and model profiles (`baseline`, `general`, `spread`, `td`, `defense`).

> Painterly gritty fantasy sports badge, square image with a hexagon-shaped riveted steel frame inlaid with dark green, studs and four-point spikes at the vertices. Inside, a dark stadium scene: *{subject}*, players in dark green armour, glowing green energy accents. Forbidden: house avoid list, no text. 1024².

Subject = the play or opponent style in one moment (e.g. Gang Up: two gauntleted fists bumping in a green burst; `spread`: players fanning out across the pitch).

## Pitch

**Boards** — top-down full pitch at 1024×1536 (2:3), heavy riveted wood-and-iron frame, 11×15 grid, home end zone tinted blue (top), away tinted red (bottom), pitch-specific features (pillars, trapdoor) in the grid. Pixel layout of the playable floor is pinned in `render/boardImageLayout.ts` — a regenerated board must match it.

- `default` — dark flagstone dungeon floor, four 2×2 stone pillars, centre trapdoor.
- `barricade` — dark wooden plank floor, wooden barricades and trapdoors.

**Tiles** — 256² seamless top-down textures: `{pitch}-{surface}` (dungeon stone, barricade wood) and `{pitch}-endzone-home` / `-away` (same surface tinted blue / red).

## Hand-drawn SVG

Not generated — written by hand, edit the file directly.

- **Team crests** — coloured circle in the team palette with one simple shape. No symbols from the house avoid list.
- **Challenge faces** — dark card, light border, one simple icon per `ChallengeClaimCondition`; `back.svg` is the shared card back.
- **UI icons** — ball, block-die face, d6 and d8 faces.
