# Leios diagrams

Sources (`*.puml`) are the source of truth; `*.svg` (used by the docs) and `*.png` (for slides and chat)
are generated from them. C4 diagrams use the C4-PlantUML library bundled with PlantUML.

Regenerate after editing a `.puml` file (PlantUML 1.2026.8, no Graphviz needed: diagrams use `!pragma layout smetana`):

```bash
curl -sO https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/1.2026.8/plantuml-1.2026.8.jar
java -jar plantuml-1.2026.8.jar -tsvg docs/leios/diagrams/*.puml
java -jar plantuml-1.2026.8.jar -tpng -Sdpi=150 docs/leios/diagrams/*.puml
```

| File | Shows |
|---|---|
| `0-leios-overview` | announce, certify, discard; ledger order |
| `1-c4-context` | C4 level 1: application, Yaci, node |
| `2-c4-container` | C4 level 2: Yaci modules involved |
| `3-c4-component` | C4 level 3: inside the complete-block assembler |
| `4-complete-block` | a certifying block before and after merging |
| `5-tip-sync` | tip sync, step by step |
| `6-range-sync` | range sync, step by step |
