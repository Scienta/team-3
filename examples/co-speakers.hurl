# Co-speakers: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 co-speakers.hurl

# Setup: a main speaker and two others to co-speak
POST {{base_url}}/speakers
{
  "name": "Main Speaker",
  "email": "main-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
main_id: jsonpath "$.id"

POST {{base_url}}/speakers
{
  "name": "Co One",
  "email": "co1-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
co1_id: jsonpath "$.id"

POST {{base_url}}/speakers
{
  "name": "Co Two",
  "email": "co2-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
co2_id: jsonpath "$.id"

POST {{base_url}}/speakers
{
  "name": "Co Three",
  "email": "co3-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
co3_id: jsonpath "$.id"

# A talk owned by the main speaker; new talks have no co-speakers
POST {{base_url}}/talks
{
  "title": "Pairing in prod",
  "format": "PRESENTATION",
  "level": "INTERMEDIATE",
  "speakerId": {{main_id}}
}
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.coSpeakerIds" count == 0

# Add two co-speakers; they come back in the order added
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{talk_id}}
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co1_id}}

PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co2_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co1_id}}
jsonpath "$.coSpeakerIds[1]" == {{co2_id}}

# Adding an existing co-speaker changes nothing (idempotent)
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co1_id}}
jsonpath "$.coSpeakerIds[1]" == {{co2_id}}

# A third co-speaker is rejected: at most two
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{co3_id}}
HTTP 409

# The main speaker cannot be a co-speaker of their own talk
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 400

# A co-speaker who isn't a registered speaker: not found
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/99999999
HTTP 404

# Adding a co-speaker to a talk that doesn't exist: not found
PUT {{base_url}}/talks/99999999/co-speakers/{{co1_id}}
HTTP 404

# The talk shows its co-speakers on read
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 2
jsonpath "$.coSpeakerIds[0]" == {{co1_id}}
jsonpath "$.coSpeakerIds[1]" == {{co2_id}}

# The main speaker's own talk list shows the talk (they are the main speaker) with its co-speakers
GET {{base_url}}/speakers/{{main_id}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 1
jsonpath "$[0].id" == {{talk_id}}
jsonpath "$[0].coSpeakerIds" count == 2

# Co-speaking does not add to a co-speaker's own talk list (only talks they main-speak)
GET {{base_url}}/speakers/{{co1_id}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 0

# Remove a co-speaker; the other remains
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co2_id}}

# Removing someone who isn't a co-speaker (any more) is not found, and changes nothing
DELETE {{base_url}}/talks/{{talk_id}}/co-speakers/{{co1_id}}
HTTP 404

GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.coSpeakerIds" count == 1
jsonpath "$.coSpeakerIds[0]" == {{co2_id}}

# Now the main speaker can add themselves? No: still rejected, main speaker is never a co-speaker
PUT {{base_url}}/talks/{{talk_id}}/co-speakers/{{main_id}}
HTTP 400
