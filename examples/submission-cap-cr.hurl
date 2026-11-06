# Submission cap change request: our own examples (no published ones for change requests).
# Run: hurl --test --variable base_url=http://localhost:8080 submission-cap-cr.hurl

# Setup: speaker S, and another speaker's talk that S co-speaks on
POST {{base_url}}/speakers
{ "name": "Capped Speaker", "email": "capped-{{newUuid}}@example.com" }
HTTP 201
[Captures]
s: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Other Speaker", "email": "other-{{newUuid}}@example.com" }
HTTP 201
[Captures]
o: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Other's talk", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o}} }
HTTP 201
[Captures]
other_talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{other_talk}}/co-speakers/{{s}}
HTTP 200

# Co-speaking counts: S has room for only two talks of their own
POST {{base_url}}/talks
{ "title": "Mine 1", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201
[Captures]
mine1: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Mine 2", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Too many", "format": "LIGHTNING_10", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 409

# At the cap S can't co-speak on another talk still in the running...
POST {{base_url}}/talks
{ "title": "Second other", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{o}} }
HTTP 201
[Captures]
other2: jsonpath "$.id"

PUT {{base_url}}/talks/{{other2}}/co-speakers/{{s}}
HTTP 409

# ...but can on a rejected one, since that doesn't count
PUT {{base_url}}/talks/{{other2}}/status
{ "status": "REJECTED" }
HTTP 200

PUT {{base_url}}/talks/{{other2}}/co-speakers/{{s}}
HTTP 200

# A rejected talk of S's own frees a place straight away
PUT {{base_url}}/talks/{{mine1}}/status
{ "status": "REJECTED" }
HTTP 200

POST {{base_url}}/talks
{ "title": "Now it fits", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

POST {{base_url}}/talks
{ "title": "Full again", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 409

# Taking S off the other talk frees that place too
DELETE {{base_url}}/talks/{{other_talk}}/co-speakers/{{s}}
HTTP 200

POST {{base_url}}/talks
{ "title": "Freed by leaving", "format": "LIGHTNING_20", "level": "BEGINNER", "speakerId": {{s}} }
HTTP 201

# S's own list still shows only the talks S is main speaker on (all four, the rejected one included)
GET {{base_url}}/speakers/{{s}}/talks
HTTP 200
[Asserts]
jsonpath "$" count == 4
