# Programme export: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 programme-export.hurl
# Works on a non-empty programme: it places one talk in R2/S3 (a slot scheduling.hurl leaves alone; 409 there if R2/S3 is already taken),
# asserts on that talk's row only, and takes it out again at the end.

# Setup: an accepted talk by "Hopper, Grace" with a co-speaker, titled with a comma and quotes.
POST {{base_url}}/speakers
{ "name": "Hopper, Grace", "email": "export-main-{{newUuid}}@example.com" }
HTTP 201
[Captures]
main: jsonpath "$.id"

POST {{base_url}}/speakers
{ "name": "Ada Lovelace", "email": "export-co-{{newUuid}}@example.com" }
HTTP 201
[Captures]
co: jsonpath "$.id"

POST {{base_url}}/talks
{ "title": "Kotlin, \"really\" fast", "format": "PRESENTATION", "level": "BEGINNER", "speakerId": {{main}} }
HTTP 201
[Captures]
talk: jsonpath "$.id"

PUT {{base_url}}/talks/{{talk}}/co-speakers/{{co}}
HTTP 200

PUT {{base_url}}/talks/{{talk}}/ratings/1
{ "score": 4 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/ratings/2
{ "score": 5 }
HTTP 200
PUT {{base_url}}/talks/{{talk}}/status
{ "status": "ACCEPTED" }
HTTP 200

PUT {{base_url}}/programme/R2/S3
{ "talkId": {{talk}} }
HTTP 200

# JSON for the website: the row has slot times, room, talk and speakers (main first, then co-speakers)
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
# (a filter matching exactly one row is unwrapped by Hurl to that row, so compare values directly)
jsonpath "$[?(@.talkId == {{talk}})].slot" == "S3"
jsonpath "$[?(@.talkId == {{talk}})].start" == "11:30"
jsonpath "$[?(@.talkId == {{talk}})].end" == "12:30"
jsonpath "$[?(@.talkId == {{talk}})].room" == "R2"
jsonpath "$[?(@.talkId == {{talk}})].title" == "Kotlin, \"really\" fast"
jsonpath "$[?(@.talkId == {{talk}})].format" == "PRESENTATION"
jsonpath "$[?(@.talkId == {{talk}})].speakers" count == 2
jsonpath "$[?(@.talkId == {{talk}})].speakers[0]" == "Hopper, Grace"
jsonpath "$[?(@.talkId == {{talk}})].speakers[1]" == "Ada Lovelace"

# CSV for the print shop: exact header, RFC 4180 quoting only where needed, "; " between speakers,
# text/csv even when the client asks for JSON, LF only and a final newline
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" contains "text/csv"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body contains "\nS3,11:30,12:30,R2,{{talk}},\"Kotlin, \"\"really\"\" fast\",PRESENTATION,\"Hopper, Grace; Ada Lovelace\"\n"
body not contains "\r"
body endsWith "\n"
body not endsWith "\n\n"

# Clean up: take the talk out; it's gone from both views
DELETE {{base_url}}/programme/R2/S3
HTTP 204

GET {{base_url}}/programme
HTTP 200
[Asserts]
jsonpath "$[?(@.talkId == {{talk}})]" not exists
