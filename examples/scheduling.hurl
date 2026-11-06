# Programme scheduling: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 scheduling.hurl

# Setup: an accepted presentation and an accepted workshop.
# Accepting needs two different reviewers (see the acceptance + ratings specs).
POST {{base_url}}/speakers
{ "name": "Pres Speaker", "email": "pres-{{newUuid}}@example.com" }
HTTP 201
[Captures]
pres_speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "A presentation", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{pres_speaker}} }
HTTP 201
[Captures]
pres: jsonpath "$.id"

# Not accepted yet -> cannot be scheduled
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{pres}} }
HTTP 409

PUT {{base_url}}/talks/{{pres}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{pres}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{pres}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Now it schedules, and the entry carries the slot's wall-clock times
PUT {{base_url}}/programme/R1/S1
{ "talkId": {{pres}} }
HTTP 200
[Asserts]
jsonpath "$.room" == "R1"
jsonpath "$.slot" == "S1"
jsonpath "$.start" == "09:00"
jsonpath "$.end" == "10:00"
jsonpath "$.talkId" == {{pres}}

# Read it back
GET {{base_url}}/programme/R1/S1
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{pres}}

# One talk per room+slot: a second talk can't take R1/S1
POST {{base_url}}/speakers
{ "name": "Other", "email": "other-{{newUuid}}@example.com" }
HTTP 201
[Captures]
other_speaker: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "Another", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{other_speaker}} }
HTTP 201
[Captures]
other: jsonpath "$.id"
PUT {{base_url}}/talks/{{other}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{other}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{other}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R1/S1
{ "talkId": {{other}} }
HTTP 409

# A talk is on the programme only once: can't also place pres in R2/S2
PUT {{base_url}}/programme/R2/S2
{ "talkId": {{pres}} }
HTTP 409

# Take it out and move it
DELETE {{base_url}}/programme/R1/S1
HTTP 204
PUT {{base_url}}/programme/R2/S2
{ "talkId": {{pres}} }
HTTP 200

# A workshop needs the long slot S4
POST {{base_url}}/speakers
{ "name": "WS Speaker", "email": "ws-{{newUuid}}@example.com" }
HTTP 201
[Captures]
ws_speaker: jsonpath "$.id"
POST {{base_url}}/talks
{ "title": "A workshop", "format": "WORKSHOP", "level": "BEGINNER", "speakerId": {{ws_speaker}} }
HTTP 201
[Captures]
ws: jsonpath "$.id"
PUT {{base_url}}/talks/{{ws}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{ws}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{ws}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R1/S1
{ "talkId": {{ws}} }
HTTP 409
PUT {{base_url}}/programme/R1/S4
{ "talkId": {{ws}} }
HTTP 200
[Asserts]
jsonpath "$.start" == "13:30"
jsonpath "$.end" == "15:30"

# Bad coordinates and bad body
GET {{base_url}}/programme/RX/S1
HTTP 404
GET {{base_url}}/programme/R1/S9
HTTP 404
PUT {{base_url}}/programme/R1/S3
{}
HTTP 400
PUT {{base_url}}/programme/R1/S3
{ "talkId": 99999999 }
HTTP 400

# Empty cell
GET {{base_url}}/programme/R3/S5
HTTP 404

# Clean up: take our talks out again, so the file can run twice against the same database (and leaves the
# deployed programme as it found it)
DELETE {{base_url}}/programme/R2/S2
HTTP 204
DELETE {{base_url}}/programme/R1/S4
HTTP 204
