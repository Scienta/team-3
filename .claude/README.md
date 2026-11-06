# .claude/

Your agent configuration goes here: agents, skills, hooks, commands and settings (Claude Code
reads this folder; keep the equivalents for other tools here too). Process documents, templates
and checklists go in `workflow/`.

Your own project instructions go in `.claude/rules/*.md`: Claude Code loads every file there at
start-up, next to the root `CLAUDE.md`. That root `CLAUDE.md` only imports the organisers'
`AGENTS.md` (the deploy contract, commands and platform gotchas); keep its `@AGENTS.md` line, and
don't create a `.claude/CLAUDE.md` as well.

At 17:00, adding or changing something in `.claude/` or `workflow/` counts towards being eligible
to win; this README on its own doesn't, and neither do the root `AGENTS.md` and `CLAUDE.md`. See
`REVIEW.md`.
