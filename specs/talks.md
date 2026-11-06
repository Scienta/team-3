# Talk submissions

Version: 1
Kind: base feature (claimed from tick 0)
V: 1

## Context

As a registered speaker ([Speakers](../speakers/spec.md)), I want to submit talks to the conference, so that the programme committee can review them.

The base app has this feature. This is what the business expects of it.

## What we want

- A speaker submits a talk with a title, a format and a level, and can look it up again later. A speaker can also list all of their own talks, oldest first.
- Every talk belongs to a speaker who is already registered, and has a real title.
- The format decides how long the talk is. Nobody else gets to pick the length:

  | Format | Minutes |
  |---|---|
  | `LIGHTNING_10` | 10 |
  | `LIGHTNING_20` | 20 |
  | `PRESENTATION` | 45 |
  | `WORKSHOP` | 120 |

- The levels are `BEGINNER`, `INTERMEDIATE` and `ADVANCED`. Formats and levels are written exactly like this, in capitals, and nothing else is a format or a level.
- A new talk is `SUBMITTED`.
- A speaker may submit at most **3** talks. We get a lot of submissions in the last hour before the deadline, often several from the same speaker at once, and the limit has to hold then too.
- The legacy reporting tools read two fields as plain strings, so their formats must be exact:
  - `submittedAt` is when the talk was submitted, in UTC, as `yyyy-MM-dd'T'HH:mm:ss.SSS+00:00`: always with milliseconds, and the offset always written `+00:00`, never `Z`. For example `2026-11-04T12:30:05.123+00:00`.
  - `submittedOn` is the UTC date of `submittedAt`, as `yyyy-MM-dd`. For example `2026-11-04`.

## API

```yaml
paths:
  /talks:
    post:
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/NewTalk' }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
  /talks/{id}:
    get:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
  /speakers/{id}/talks:
    get:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      responses:
        '200': { content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/Talk' } } } } }
components:
  schemas:
    Format: { type: string, enum: [ LIGHTNING_10, LIGHTNING_20, PRESENTATION, WORKSHOP ] }
    Level: { type: string, enum: [ BEGINNER, INTERMEDIATE, ADVANCED ] }
    NewTalk:
      type: object
      properties:
        title: { type: string }
        format: { $ref: '#/components/schemas/Format' }
        level: { $ref: '#/components/schemas/Level' }
        speakerId: { type: integer }
    Talk:
      type: object
      properties:
        id: { type: integer }
        title: { type: string }
        format: { $ref: '#/components/schemas/Format' }
        level: { $ref: '#/components/schemas/Level' }
        speakerId: { type: integer }
        durationMinutes: { type: integer }
        status: { type: string, example: SUBMITTED }
        submittedAt: { type: string, example: '2026-11-04T12:30:05.123+00:00' }
        submittedOn: { type: string, example: '2026-11-04' }
        averageRating: { type: number, nullable: true, description: 'see Ratings' }
        ratingCount: { type: integer, description: 'see Ratings' }
```
