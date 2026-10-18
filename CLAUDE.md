# CLAUDE.md

Public portfolio repository. Everything committed here is meant to be read by employers.

- **Language:** English in all committed files (code, docs, commits). Conversation with the
  author is in Russian.
- **Private notes:** `notes/` is gitignored. Start every session by reading `notes/BRIEF.md` —
  it holds the project decisions, the division of work and the working rules.
- **Never commit** personal information, job-search details or anything from `notes/`.
- **Branch:** `main`. Commit only when the author asks.

## Build and run

```bash
export JAVA_HOME=~/Library/Java/JavaVirtualMachines/openjdk-22.0.2/Contents/Home  # Maven defaults to a newer JDK
docker compose up -d
./run.sh                # stop → build → start both services → wait for health
```

Never rebuild while the services are running from their jars: use `./run.sh`, which stops them first.

The experiment stand (`experiment/bugs.sh`) runs from its own copy, `~/services/arcade-wallet`, with the same
container names: stop it there (`docker compose down`) before `docker compose up -d` here.
