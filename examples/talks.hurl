# Talk submissions: published examples (happy path only).
# Run: hurl --test --variable base_url=http://localhost:8080 examples.hurl

# Setup (Speakers R1): register a speaker
POST {{base_url}}/speakers
{
  "name": "Barbara Liskov",
  "email": "barbara-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"

# R1, R4, R6, R9, R10: submit a workshop
POST {{base_url}}/talks
{
  "title": "Characterise first",
  "format": "WORKSHOP",
  "level": "BEGINNER",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
submitted_at: jsonpath "$.submittedAt"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.title" == "Characterise first"
jsonpath "$.format" == "WORKSHOP"
jsonpath "$.durationMinutes" == 120
jsonpath "$.level" == "BEGINNER"
jsonpath "$.speakerId" == {{speaker_id}}
jsonpath "$.status" == "SUBMITTED"
jsonpath "$.submittedAt" matches /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+00:00$/
jsonpath "$.submittedOn" matches /^\d{4}-\d{2}-\d{2}$/
jsonpath "$.averageRating" == null
jsonpath "$.ratingCount" == 0

# R2: read it back
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{talk_id}}
jsonpath "$.durationMinutes" == 120
jsonpath "$.status" == "SUBMITTED"
jsonpath "$.submittedAt" == "{{submitted_at}}"

# R1, R4: a lightning talk; the client can't choose the duration
POST {{base_url}}/talks
{
  "title": "Ten minutes of legacy",
  "format": "LIGHTNING_10",
  "level": "ADVANCED",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Asserts]
jsonpath "$.durationMinutes" == 10

# R8: the speaker's talks, ordered by id
GET {{base_url}}/speakers/{{speaker_id}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].id" == {{talk_id}}
jsonpath "$[1].title" == "Ten minutes of legacy"
