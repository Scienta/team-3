# Change request ratings-cr: our own examples (no published ones for change requests).
# Run: examples/run.sh [base-url]   (or hurl --test --variable base_url=http://localhost:8080 ratings-cr.hurl)

POST {{base_url}}/speakers
{ "name": "CR Speaker", "email": "ratings-cr-{{newUuid}}@example.com" }
HTTP 201
[Captures]
speaker: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Trimmed", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{speaker}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

# Four ratings: every score counts, now with 2 decimals (4,4,4,5 -> 4.25; was 4.3 before the change)
PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/3
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/4
{ "score": 5 }
HTTP 200
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.25
jsonpath "$.ratingCount" == 4

# The fifth rating switches trimming on: 4,4,4,5,1 -> drop one 5 and one 1 -> (4+4+4)/3 = 4.0,
# and ratingCount still counts all five reviewers
PUT {{base_url}}/talks/{{talk}}/ratings/5
{ "score": 1 }
HTTP 200
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.0
jsonpath "$.ratingCount" == 5

# Ties at the top: 5,5,5,1,1 drops exactly ONE 5 and ONE 1 -> (5+5+1)/3 = 3.666.. -> 3.67
PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/3
{ "score": 1 }
HTTP 200
GET {{base_url}}/talks/{{talk}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 3.67
jsonpath "$.ratingCount" == 5

# Same value in the ranking view
GET {{base_url}}/talks?sort=rating
HTTP 200
[Asserts]
jsonpath "$[?(@.id == {{talk}})].averageRating" == 3.67
