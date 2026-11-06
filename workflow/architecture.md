# Architecture

Seven drawings, from the outside in. GitHub renders the Mermaid blocks; rendered SVGs are in
[`workflow/diagrams/`](diagrams/) for anywhere else.

1. [The system: who talks to what](#1-the-system-who-talks-to-what)
2. [From push to production](#2-from-push-to-production)
3. [Inside the service](#3-inside-the-service)
4. [The data model](#4-the-data-model)
5. [A talk's life](#5-a-talks-life)
6. [Concurrency: how a rush stays correct](#6-concurrency-how-a-rush-stays-correct)
7. [How we work: agents, ledger, gates](#7-how-we-work-agents-ledger-gates)

## 1. The system: who talks to what

```mermaid
flowchart LR
    subgraph team["👥 Team laptops"]
        dev["Person + Claude Code<br/><i>feature loop, house conventions</i>"]
    end

    subgraph gh["GitHub"]
        main[("main<br/><i>push = deploy</i>")]
        ledger[("agent-allocations<br/><i>who builds what</i>")]
        actions["Actions: deploy.yml"]
    end

    subgraph scw["Scaleway"]
        registry[("Container registry")]
        app["CFP service<br/>Spring Boot 4 · Java 25<br/>:8080 · 1 instance"]
        db[("PostgreSQL<br/><i>pool of 5</i>")]
        logs[("Cockpit logs")]
    end

    harness["🧪 Harness<br/><i>checks every tick</i>"]

    dev -- "git push" --> main
    dev -- "grab / done" --> ledger
    main --> actions -- "image app:sha" --> registry --> app
    app <--> db
    app -- stdout --> logs
    harness -- "HTTPS checks" --> app
    dev -- "feedback API" --> harness
    dev -- "logs.sh" --> logs

    classDef ext fill:#fff4e5,stroke:#e69500,color:#5c3b00
    classDef ours fill:#e8f1ff,stroke:#2f6fdb,color:#0b2a5b
    classDef store fill:#eefaf0,stroke:#2e9d4b,color:#0f3d1c
    class harness ext
    class app,actions,dev ours
    class main,ledger,registry,db,logs store
```

## 2. From push to production

```mermaid
flowchart LR
    push(["push to main"]) --> docs{"only docs, workflow/,<br/>.claude/ or examples/?"}
    docs -- yes --> skip(["no deploy"])
    docs -- no --> build["docker build<br/>GIT_SHA baked in<br/>(compiles + packages)"]
    build --> wait["wait for the harness's<br/>quiet window"]
    wait --> roll["roll out the new<br/>container"]
    roll --> health{"GET /health<br/>200 + new version?"}
    health -- yes --> live(["live ✔"])
    health -- no --> failed(["deploy fails,<br/>old version keeps running"])

    classDef good fill:#eefaf0,stroke:#2e9d4b,color:#0f3d1c
    classDef bad fill:#fdecec,stroke:#d33,color:#5b0b0b
    classDef step fill:#e8f1ff,stroke:#2f6fdb,color:#0b2a5b
    class live,skip good
    class failed bad
    class build,wait,roll step
```

Every code deploy used to cost about one tick of FAULTY checks; rolling out in the harness's quiet window
removes most of that. Docs-only pushes never deploy. The test suite runs in Actions *after* the deploy and is
not a gate there: our gate is `./mvnw package` (Testcontainers) before a person merges, see drawing 7.

## 3. Inside the service

```mermaid
flowchart LR
    req(["HTTP<br/>request"]) --> filter["RequestLoggingFilter<br/><i>method · path · status · bodies</i>"]
    filter --> ctrl

    subgraph ctrl["① Controllers — thin"]
        direction TB
        sc["SpeakerController<br/>/speakers"]
        tc["TalkController<br/>/talks · ratings · co-speakers · status"]
        wc["WorkshopController<br/>/workshops/{id}/registrations"]
        rc["ReportController<br/>/admin/stats · talks.csv"]
        pc["ProgrammeController<br/>/programme · .csv · {room}/{slot}"]
    end

    subgraph svc["② Services — business rules, @Transactional"]
        direction TB
        cs["ConferenceService<br/><i>speakers · talks · ratings · co-speakers<br/>acceptance · submission cap · workshops</i>"]
        ps["ProgrammeService<br/><i>scheduling · programme export</i>"]
    end

    subgraph dao["③ DAOs — plain SQL"]
        direction TB
        d1["TalkDao · <b>lockForUpdate</b>"]
        d2["SpeakerDao · RatingDao"]
        d3["CoSpeakerDao · RegistrationDao"]
        d4["ProgrammeDao · <i>slot lock rows</i>"]
    end

    sc & tc & wc & rc --> cs
    pc --> ps
    cs --> dao
    ps --> dao
    dao --> pool[("Hikari pool<br/>max 5")] --> pg[("PostgreSQL")]

    hc["HealthController<br/>/health"] -- "own connection<br/>per probe" --> pg

    subgraph cross["Cross-cutting"]
        direction TB
        tx["TransactionConfig<br/><i>SET TRANSACTION READ COMMITTED<br/>first in every transaction</i>"]
        compat["CompatibilityConfig<br/><i>Boot 2.7 JSON and 404s</i>"]
        err["ApiErrorHandler<br/><i>error bodies</i>"]
    end
    cross -.- svc

    classDef web fill:#e8f1ff,stroke:#2f6fdb,color:#0b2a5b
    classDef biz fill:#f3ecff,stroke:#7a4fd6,color:#2a1259
    classDef data fill:#eefaf0,stroke:#2e9d4b,color:#0f3d1c
    classDef xc fill:#fff4e5,stroke:#e69500,color:#5c3b00
    class sc,tc,wc,pc,rc,hc web
    class cs,ps biz
    class d1,d2,d3,d4,pool,pg data
    class tx,compat,err,filter xc
```

## 4. The data model

```mermaid
erDiagram
    SPEAKER ||--o{ TALK : "main speaker of"
    SPEAKER ||--o{ CO_SPEAKER : "co-speaks"
    TALK ||--o{ CO_SPEAKER : "has up to 2"
    TALK ||--o{ RATING : "rated by reviewers"
    TALK ||--o| PROGRAMME : "placed once in"
    PROGRAMME_SLOT ||--o{ PROGRAMME : "locks per slot"
    TALK ||--o{ REGISTRATION : "workshop takes up to 20"

    SPEAKER {
        int id PK
        text name
        text email "unique, any case"
        text bio
    }
    TALK {
        int id PK
        text title
        text format "LIGHTNING_10/20, PRESENTATION, WORKSHOP"
        text status "SUBMITTED → … (state machine)"
        int speaker_id FK
        bigint submitted_at "nullable for legacy rows"
    }
    CO_SPEAKER {
        int id PK "order added"
        int talk_id FK
        int speaker_id FK
    }
    RATING {
        int talk_id PK
        int reviewer_id PK "just a number"
        int score "1-5"
    }
    PROGRAMME {
        text room PK "R1-R3"
        text slot PK "S1-S5"
        int talk_id "unique"
    }
    PROGRAMME_SLOT {
        text slot PK
    }
    REGISTRATION {
        int id PK "order registered"
        int workshop_id FK
        text name
        text email "unique per workshop, any case"
    }
```

## 5. A talk's life

```mermaid
stateDiagram-v2
    direction LR
    [*] --> SUBMITTED: POST /talks<br/>(cap 3 per speaker,<br/>co-speaking counts)
    SUBMITTED --> ACCEPTED: committee<br/>(≥ 2 reviewers)
    SUBMITTED --> WAITLISTED: committee
    SUBMITTED --> REJECTED: committee
    WAITLISTED --> ACCEPTED: committee
    WAITLISTED --> REJECTED: committee
    SUBMITTED --> WITHDRAWN: speaker
    WAITLISTED --> WITHDRAWN: speaker
    ACCEPTED --> WITHDRAWN: speaker
    ACCEPTED --> Programme: PUT /programme/{room}/{slot}
    ACCEPTED --> Workshop: POST registrations<br/>(workshops only)
    REJECTED --> [*]
    WITHDRAWN --> [*]

    state Programme {
        [*] --> Placed
    }
    state Workshop {
        [*] --> Registered
    }
```

Anything not drawn is a **409** (including a repeat: WAITLISTED → WAITLISTED). Rejected and withdrawn talks
stop counting towards the speaker's cap.

## 6. Concurrency: how a rush stays correct

Twenty seats, forty attendees at the same instant. The row lock serialises them; READ COMMITTED makes the
count after the lock see every seat taken while we waited.

```mermaid
sequenceDiagram
    autonumber
    participant A as Attendee A
    participant B as Attendee B
    participant S as ConferenceService
    participant DB as PostgreSQL

    par at the same moment
        A->>S: POST /workshops/42/registrations
    and
        B->>S: POST /workshops/42/registrations
    end
    S->>DB: SET TRANSACTION … READ COMMITTED (A)
    S->>DB: SELECT … FROM talk WHERE id=42 FOR UPDATE (A)
    Note over DB: A holds the talk's row lock
    S->>DB: SELECT … FOR UPDATE (B)
    Note over DB: B waits
    S->>DB: COUNT(*) = 19 → INSERT seat 20 (A)
    DB-->>S: COMMIT, lock released (A)
    S-->>A: 201 Created
    Note over DB: B gets the lock, and its COUNT sees A's row
    S->>DB: COUNT(*) = 20 (B)
    S-->>B: 409 the workshop is full
```

The same pattern guards co-speakers (max 2), the submission cap (max 3 per speaker; talk lock, then speaker
lock, always in that order), committee decisions, and the programme (a lock row per slot). The 14:07 incident
was this drawing breaking: a leaked REPEATABLE READ made B's count blind to A's row, and 22 attendees got 20
seats.

## 7. How we work: agents, ledger, gates

```mermaid
flowchart LR
    subgraph find["① Find work"]
        direction TB
        watch["👀 Watcher<br/><i>new spec · FAULTY check<br/>free feature · service down</i>"]
        pull["Pull main"]
        grab{"allocate.sh grab<br/><i>compare-and-swap<br/>on the ledger</i>"}
        watch --> pull --> grab
        grab -- "taken" --> watch
    end

    subgraph build["② Build it right"]
        direction TB
        branch["Branch<br/>feature/&lt;slug&gt;"]
        table["Requirements table<br/><i>spec rows + convention rows</i>"]
        impl["Implement + tests<br/><i>concurrency · a test that<br/>fails before the fix</i>"]
        green{"./mvnw package<br/>green?"}
        review{"Independent reviewer<br/><i>sees only spec + diff</i>"}
        branch --> table --> impl --> green
        green -- "no" --> impl
        green -- "yes" --> review
        review -- "gaps" --> impl
    end

    subgraph ship["③ Ship it"]
        direction TB
        report["Test report<br/>+ REVIEW.md log"]
        merge{{"Person: merge<br/><i>deploys</i>"}}
        gate{"Pre-claim gate<br/><i>run.sh + edge cases<br/>on the deployed URL</i>"}
        claim{{"Person: claim<br/>on the harness"}}
        report --> merge --> gate --> claim
    end

    grab -- "ours" --> branch
    review -- "clean" --> report
    claim -. "next" .-> watch

    classDef agent fill:#e8f1ff,stroke:#2f6fdb,color:#0b2a5b
    classDef gatec fill:#fff4e5,stroke:#e69500,color:#5c3b00
    classDef person fill:#f3ecff,stroke:#7a4fd6,color:#2a1259
    class watch,pull,branch,table,impl,report agent
    class grab,green,review,gate gatec
    class merge,claim person
```

Agents never merge, deploy or claim on their own; a person does (the purple steps). Every gate exists
because the deployed service broke without it: see [`house-conventions.md`](../.claude/rules/house-conventions.md)
and REVIEW.md §4.
