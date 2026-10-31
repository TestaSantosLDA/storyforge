# Source texts

Public-domain works that a series is grounded in and quotes verbatim (docs/series/lusiadas.md "Accuracy and
rights"). One folder per work:

- `source.yaml`: title, author, year, edition and provenance, the stanza count of each canto, names a topic may
  allow (C9), and the translations a Verse Moment may use (our own, or pre-1929 public-domain ones, credited).
- `canto-01.txt` … `canto-10.txt`: each stanza as its number on a line, its verse lines, and a blank line.

The canto files for *Os Lusíadas* are created once, on a machine that can reach gutenberg.org:

```
python sources/tools/import_gutenberg.py
```

The importer refuses a text whose stanza counts or stanza lengths don't match `source.yaml`. Its tests run with
`python -m unittest discover -s sources/tools`.
