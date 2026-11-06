# Speakers

Version: 1
Kind: base feature (claimed from tick 0)
V: 1

## Context

As a speaker, I want to register a profile once with my name, email and a short bio, so that I can submit talks under it and the organisers can contact me.

The base app has this feature. This is what the business expects of it.

## What we want

- A speaker registers with a name and an email, and may add a bio. They can look up their profile again later, exactly as they registered it.
- A speaker needs a real name and a real email to register. The bio is optional.
- A bio is at most **500** characters.
- One email is one speaker. Emails are the same whatever the letter case: `ada@example.com` and `ADA@Example.com` are one person.
- Registered speakers stay registered, also after the service is updated or restarted.
- The legacy web client is still out there, and some of its links and forms put a slash at the end of the speaker URLs (`/speakers/` and `/speakers/{id}/`). They must keep working just like the URLs without one.

## API

```yaml
paths:
  /speakers:
    post:
      requestBody:
        content:
          application/json:
            schema: { $ref: '#/components/schemas/NewSpeaker' }
      responses:
        '201': { content: { application/json: { schema: { $ref: '#/components/schemas/Speaker' } } } }
  /speakers/{id}:
    get:
      parameters: [ { name: id, in: path, required: true, schema: { type: integer } } ]
      responses:
        '200': { content: { application/json: { schema: { $ref: '#/components/schemas/Speaker' } } } }
components:
  schemas:
    NewSpeaker:
      type: object
      properties:
        name: { type: string }
        email: { type: string }
        bio: { type: string, nullable: true }
    Speaker:
      type: object
      properties:
        id: { type: integer }
        name: { type: string }
        email: { type: string }
        bio: { type: string, nullable: true }
```
