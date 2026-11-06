# Ratings

Version: 1
Kind: base feature (claimed from tick 0)
V: 2

## Context

As a reviewer, I want to give each talk ([Talk submissions](../talks/spec.md)) a score from 1 to 5 and change my mind later, so that the programme committee can rank talks by how well they were received.

The base app has this feature. This is what the business expects of it.

## What we want

- Reviewers aren't registered anywhere: a reviewer is just the number in the URL, and it's a positive whole number.
- A score is a whole number from 1 to 5. Nothing else is a score.
- Each reviewer has at most one rating per talk. Rating the same talk again replaces the old score.
- Every talk shows how many reviewers have rated it (`ratingCount`) and their average score (`averageRating`), wherever a talk is shown. The average is rounded to one decimal, with halves rounded up: `4, 4, 4, 5` averages 4.25, which shows as `4.3`. A talk nobody has rated has no average yet.
- The committee lists all talks, oldest first, or just one speaker's talks.
- The committee also ranks talks: the best average (as shown, rounded) first, and on the same average, the talk more reviewers have rated. Talks still tied stay oldest first, and talks nobody has rated come last.
- Reviewing is busy: many reviewers rate the same talk at once, and a reviewer who double-clicks sends the same rating twice. No rating may get lost, and nobody may end up counted twice.
- The legacy reviewer UI shows `ratedAtDisplay` as it is: the time of the latest rating, in UTC, in the US short format `M/d/yy, h:mm AM`. Month, day and hour have no leading zeros, the year has two digits, the clock is 12-hour, and there's exactly one ordinary space before `AM` or `PM`. For example `11/4/26, 1:05 PM`, and `11/4/26, 12:00 AM` at midnight.

## API

```yaml
paths:
  /talks/{id}/ratings/{reviewerId}:
    put:
      parameters:
        - { name: id, in: path, required: true, schema: { type: integer } }
        - { name: reviewerId, in: path, required: true, schema: { type: integer } }
      requestBody:
        content:
          application/json:
            schema:
              type: object
              properties:
                score: { type: integer }
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Rating' } } } }
  /talks:
    get:
      parameters:
        - { name: speakerId, in: query, required: false, schema: { type: integer }, description: only this speaker's talks }
        - { name: sort, in: query, required: false, schema: { type: string, enum: [ rating ] }, description: the ranking }
      responses:
        '200': { content: { application/json: { schema: { type: array, items: { $ref: '../talks/spec.md#/components/schemas/Talk' } } } } }
components:
  schemas:
    Rating:
      type: object
      properties:
        talkId: { type: integer }
        reviewerId: { type: integer }
        score: { type: integer }
        ratedAtDisplay: { type: string, example: '11/4/26, 1:05 PM' }
    # Fields this feature fills in on Talk (Talk submissions):
    #   averageRating: { type: number, nullable: true }
    #   ratingCount:   { type: integer }
```
