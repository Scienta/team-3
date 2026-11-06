# Programme export: our own examples (no published ones for drip features).
# Run: hurl --test --variable base_url=http://localhost:8080 programme-export.hurl
# Works on a non-empty programme: it places one talk in R3/S5 (409 there if R3/S5 is already taken),
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

PUT {{base_url}}/programme/R3/S5
{ "talkId": {{talk}} }
HTTP 200

# JSON for the website: the row has slot times, room, talk and speakers (main first, then co-speakers)
GET {{base_url}}/programme
HTTP 200
[Asserts]
header "Content-Type" contains "application/json"
jsonpath "$[?(@.talkId == {{talk}})]" count == 1
jsonpath "$[?(@.talkId == {{talk}})].slot" nth 0 == "S5"
jsonpath "$[?(@.talkId == {{talk}})].start" nth 0 == "15:45"
jsonpath "$[?(@.talkId == {{talk}})].end" nth 0 == "16:45"
jsonpath "$[?(@.talkId == {{talk}})].room" nth 0 == "R3"
jsonpath "$[?(@.talkId == {{talk}})].title" nth 0 == "Kotlin, \"really\" fast"
jsonpath "$[?(@.talkId == {{talk}})].format" nth 0 == "PRESENTATION"
jsonpath "$[?(@.talkId == {{talk}})].speakers[0]" nth 0 == "Hopper, Grace"
jsonpath "$[?(@.talkId == {{talk}})].speakers[1]" nth 0 == "Ada Lovelace"

# CSV for the print shop: exact header, RFC 4180 quoting only where needed, "; " between speakers,
# text/csv even when the client asks for JSON, LF only and a final newline
GET {{base_url}}/programme.csv
Accept: application/json
HTTP 200
[Asserts]
header "Content-Type" contains "text/csv"
body startsWith "slot,start,end,room,talkId,title,format,speakers\n"
body contains "\nS5,15:45,16:45,R3,{{talk}},\"Kotlin, \"\"really\"\" fast\",PRESENTATION,\"Hopper, Grace; Ada Lovelace\"\n"
body not contains "\r"
body endsWith "\n"
body not endsWith "\n\n"

# Clean up: take the talk out; it's gone from both views
DELETE {{base_url}}/programme/R3/S5
HTTP 204

GET {{base_url}}/programme
HTTP 200
[Asserts]
jsonpath "$[?(@.talkId == {{talk}})]" count == 0
