# Speakers: published examples (happy path only).
# Run: hurl --test --variable base_url=http://localhost:8080 examples.hurl

# R1: register a speaker
POST {{base_url}}/speakers
{
  "name": "Ada Lovelace",
  "email": "ada-{{newUuid}}@example.com",
  "bio": "Wrote the first program."
}
HTTP 201
[Captures]
id: jsonpath "$.id"
email: jsonpath "$.email"
[Asserts]
jsonpath "$.id" isInteger
jsonpath "$.name" == "Ada Lovelace"
jsonpath "$.bio" == "Wrote the first program."

# R2: read it back
GET {{base_url}}/speakers/{{id}}
HTTP 200
[Asserts]
jsonpath "$.id" == {{id}}
jsonpath "$.name" == "Ada Lovelace"
jsonpath "$.email" == "{{email}}"
jsonpath "$.bio" == "Wrote the first program."

# R1: bio is optional and comes back as null
POST {{base_url}}/speakers
{
  "name": "Grace Hopper",
  "email": "grace-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
grace_id: jsonpath "$.id"
[Asserts]
jsonpath "$.bio" == null

# R7: the same speaker with a trailing slash
GET {{base_url}}/speakers/{{grace_id}}/
HTTP 200
[Asserts]
jsonpath "$.id" == {{grace_id}}
jsonpath "$.name" == "Grace Hopper"
jsonpath "$.bio" == null
