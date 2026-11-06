# Acceptance state machine: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 acceptance.hurl

# Setup: a speaker and a talk; a new talk starts SUBMITTED
POST {{base_url}}/speakers
{
  "name": "Owner",
  "email": "owner-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
owner_id: jsonpath "$.id"

POST {{base_url}}/speakers
{
  "name": "Someone Else",
  "email": "else-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
other_id: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "On state machines",
  "format": "PRESENTATION",
  "level": "INTERMEDIATE",
  "speakerId": {{owner_id}}
}
HTTP 201
[Captures]
talk_id: jsonpath "$.id"
[Asserts]
jsonpath "$.status" == "SUBMITTED"

# Accepting needs at least two different reviewers: with none, it's refused and nothing changes
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "SUBMITTED"

# Waitlisting needs no ratings
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "WAITLISTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "WAITLISTED"

# One reviewer rating twice is still one reviewer: accept still refused
PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{talk_id}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 409

# A second, different reviewer: now a waitlisted talk can be accepted
PUT {{base_url}}/talks/{{talk_id}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "ACCEPTED" }
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# ACCEPTED is final: a further decision is refused and changes nothing
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "REJECTED" }
HTTP 409
GET {{base_url}}/talks/{{talk_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "ACCEPTED"

# Bad decisions are rejected as bad requests
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "SUBMITTED" }
HTTP 400
PUT {{base_url}}/talks/{{talk_id}}/status
{ "status": "FOO" }
HTTP 400

# A second talk to exercise withdrawal
POST {{base_url}}/talks
{
  "title": "Second talk",
  "format": "LIGHTNING_10",
  "level": "BEGINNER",
  "speakerId": {{owner_id}}
}
HTTP 201
[Captures]
talk2_id: jsonpath "$.id"

# Only the speaker can withdraw: someone else is forbidden and nothing changes
POST {{base_url}}/talks/{{talk2_id}}/withdraw
{ "speakerId": {{other_id}} }
HTTP 403
GET {{base_url}}/talks/{{talk2_id}}
HTTP 200
[Asserts]
jsonpath "$.status" == "SUBMITTED"

# The owner withdraws their own talk
POST {{base_url}}/talks/{{talk2_id}}/withdraw
{ "speakerId": {{owner_id}} }
HTTP 200
[Asserts]
jsonpath "$.status" == "WITHDRAWN"

# A withdrawn talk is out for good: withdrawing again is refused
POST {{base_url}}/talks/{{talk2_id}}/withdraw
{ "speakerId": {{owner_id}} }
HTTP 409

# withdraw needs a speakerId
POST {{base_url}}/talks/{{talk2_id}}/withdraw
{}
HTTP 400

# Unknown talk is not found on either endpoint
PUT {{base_url}}/talks/99999999/status
{ "status": "REJECTED" }
HTTP 404
POST {{base_url}}/talks/99999999/withdraw
{ "speakerId": {{owner_id}} }
HTTP 404
