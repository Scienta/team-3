# Ratings: published examples (happy path only).
# Run: hurl --test --variable base_url=http://localhost:8080 examples.hurl

# Setup (Speakers R1, Talks R1): a speaker with two talks
POST {{base_url}}/speakers
{
  "name": "Edsger Dijkstra",
  "email": "edsger-{{newUuid}}@example.com"
}
HTTP 201
[Captures]
speaker_id: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "Goto considered harmful",
  "format": "PRESENTATION",
  "level": "INTERMEDIATE",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Captures]
first_talk: jsonpath "$.id"

POST {{base_url}}/talks
{
  "title": "On the cruelty of really teaching computing science",
  "format": "LIGHTNING_20",
  "level": "ADVANCED",
  "speakerId": {{speaker_id}}
}
HTTP 201
[Captures]
second_talk: jsonpath "$.id"

# R1, R8: reviewer 1 rates the first talk
PUT {{base_url}}/talks/{{first_talk}}/ratings/1
{
  "score": 3
}
HTTP 200
[Asserts]
jsonpath "$.talkId" == {{first_talk}}
jsonpath "$.reviewerId" == 1
jsonpath "$.score" == 3
jsonpath "$.ratedAtDisplay" matches /^\d{1,2}\/\d{1,2}\/\d{2}, \d{1,2}:\d{2} [AP]M$/

# R3: reviewer 1 changes their mind; still one rating
PUT {{base_url}}/talks/{{first_talk}}/ratings/1
{
  "score": 4
}
HTTP 200
[Asserts]
jsonpath "$.score" == 4

# R1: reviewer 2 rates the first talk too
PUT {{base_url}}/talks/{{first_talk}}/ratings/2
{
  "score": 5
}
HTTP 200

# R4: average of 4 and 5, rounded to 1 decimal
GET {{base_url}}/talks/{{first_talk}}
HTTP 200
[Asserts]
jsonpath "$.averageRating" == 4.5
jsonpath "$.ratingCount" == 2

# R6, R7: ranked, with the unrated talk last
GET {{base_url}}/talks
[Query]
sort: rating
speakerId: {{speaker_id}}
HTTP 200
[Asserts]
jsonpath "$" count == 2
jsonpath "$[0].id" == {{first_talk}}
jsonpath "$[1].id" == {{second_talk}}
jsonpath "$[1].averageRating" == null
jsonpath "$[1].ratingCount" == 0
