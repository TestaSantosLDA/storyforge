A reviewer rejected the finished episode. Turn their reason into a correction plan: the smallest set of targeted fixes that addresses everything they said.

## Reviewer's reason
{{reason}}

## The video's timeline (times are in the final video, intro included)
Intro: 0:00–{{intro_end}}

### Shots
{{shots}}

### Narration lines
{{lines}}

### Music
{{music}}

## Target types
- shot: redraw the named shots. instruction = what the picture should show instead, said positively (e.g. "Oswin's feathers are speckled brown and cream, matching his reference"). Never describe the flaw itself; the image model draws whatever is named.
- audio_line: record the named lines again (e.g. a misread word, wrong emphasis). If it's a pronunciation problem, also fill word and say_as (how to spell it so it is read correctly).
- music: change the music for the named scenes (ids are scene ids). instruction = what's wrong with the current choice.
- mix: the music is too loud or too quiet overall. instruction = "quieter" or "louder".
- subtitles: the subtitle file is wrong.
- script: the words or the storytelling need rewriting (e.g. "the ending doesn't land"). instruction = notes for the writer.
- story: the story itself needs changing. instruction = notes for the writer.

## Rules
- Map timestamps ("1:42") to the shot or line playing at that time.
- Use ids exactly as listed. Leave word and say_as empty unless it's a pronunciation fix.
- If the reason is too vague to map to specific fixes, set confident to false and leave targets empty.
