# Workshop registration: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 workshop-registration.hurl

# Setup: a speaker, an accepted workshop (two reviewers), and a plain talk
POST {{base_url}}/speakers
{
  "name": "Workshop Host",
  "email": "host-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
host_id: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "Hands-on Postgres",
  "format": "WORKSHOP",
  "level": "BEGINNER",
  "speakerId": {{host_id}}
}
HTTP 201
[Captures]
ws_id: jsonpath "$.id"

# Not accepted yet: no registrations
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": "ada@example.com" }
HTTP 409

PUT {{base_url}}/talks/{{ws_id}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{ws_id}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{ws_id}}/status
{ "status": "ACCEPTED" }
HTTP 200

# Register: 201, the email exactly as typed
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada", "email": "Ada@Example.com" }
HTTP 201
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.workshopId" == {{ws_id}}
jsonpath "$.name" == "Ada"
jsonpath "$.email" == "Ada@Example.com"

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Grace", "email": "grace@example.com" }
HTTP 201

# The same email in another case is the same person: refused
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Ada again", "email": "ADA@example.COM" }
HTTP 409

# Name and email are both required
POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "No Email" }
HTTP 400

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "email": "noname@example.com" }
HTTP 400

# The organisers see who registered, in order
GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].email" == "Ada@Example.com"
jsonpath "$[1].email" == "grace@example.com"

# Withdrawn later: the list stays, new registrations are refused
POST {{base_url}}/talks/{{ws_id}}/withdraw
{ "speakerId": {{host_id}} }
HTTP 200

POST {{base_url}}/workshops/{{ws_id}}/registrations
{ "name": "Linus", "email": "linus@example.com" }
HTTP 409

GET {{base_url}}/workshops/{{ws_id}}/registrations
HTTP 200
[Asserts]
jsonpath "$" count == 2

# A talk that isn't a workshop, or doesn't exist: not found
POST {{base_url}}/talks
{
  "title": "Just a talk",
  "format": "PRESENTATION",
  "level": "BEGINNER",
  "speakerId": {{host_id}}
}
HTTP 201
[Captures]
talk_id: jsonpath "$.id"

POST {{base_url}}/workshops/{{talk_id}}/registrations
{ "name": "Ada", "email": "ada@example.com" }
HTTP 404

GET {{base_url}}/workshops/99999999/registrations
HTTP 404
