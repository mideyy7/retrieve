# Hero video

Tools that made `web/assets/video/hero-{wide,tall}.{mp4,webm}`: real footage of the release app on the
development tablet, composited frame by frame with the site's fonts and colours.

Demo library on the tablet: MIT OpenCourseWare 6.0001 (Fall 2016), CC BY-NC-SA 4.0 — six slide decks
(weeks 3, 5, 6, 8, 10, 12) and the caption files of lectures 6 and 12, imported as module `MIT6.0001`.
The credit is on the video's end card and in the site footer.

1. `python3 rec.py all` drives the app over adb (tap by text, type slowly, `screenrecord --size 1080x1800`)
   and saves `clips/<name>.mp4`. `rec.py search|recording|query|ask` re-records one.
2. `./sheet.sh <clip> 1.5` makes a labelled contact sheet to read timings from; put them in `timings.py`
   (source time ranges and playback speed per segment; waiting is sped up and Ask is labelled "sped up").
3. Extract frames: `for c in search recording query ask; do mkdir -p clips/$c; ffmpeg -i clips/$c.mp4 -vf fps=30 -q:v 2 clips/$c/%05d.jpg; done`
4. `python3 timeline.py preview` (key frames), then `python3 timeline.py render master.mp4` and
   `LAYOUT=tall python3 timeline.py render master_tall.mp4` (1920×960 desktop, 1080×1350 phones).
5. Encode: VP9 `-crf 38` WebM and H.264 `-crf 27 -movflags +faststart` MP4, posters at 8.4 s.

Latency chips quote what the app showed in the recording (121 ms, 124 ms, 2 ms). `clips/` is gitignored.
