# Co-speakers

Version: 1
Kind: drip feature (released at tick 0)
V: 1

## Context

As a speaker, I want to add up to two co-speakers to my talk, so that the programme shows everyone who presents it.

## What we want

- A talk keeps its one main speaker (`speakerId`, [Talk submissions](../talks/spec.md)). Co-speakers are other registered speakers ([Speakers](../speakers/spec.md)) added to the talk afterwards, and can be removed again.
- A talk has at most two co-speakers. The main speaker can't also be a co-speaker of their own talk.
- Every talk shows its co-speakers, in the order they were added. Adding someone who is already a co-speaker changes nothing.
- A speaker's own talk list still shows only the talks where they are the main speaker.
- Co-speaking doesn't use up a speaker's own submissions: the limit of 3 talks still counts only the talks where they are the main speaker, and a speaker who has reached it can still co-speak on other talks.

## API

```yaml
paths:
  /talks/{id}/co-speakers/{speakerId}:
    parameters:
      - { name: id, in: path, required: true, schema: { type: integer } }
      - { name: speakerId, in: path, required: true, schema: { type: integer } }
    put:
      description: no request body
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
    delete:
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Talk' } } } }
components:
  schemas:
    Talk:
      description: the Talk of the Talk submissions spec, with one more field
      type: object
      properties:
        coSpeakerIds:
          type: array
          items: { type: integer }
          description: the co-speakers' speaker ids, in the order they were added; [] when none
```
