# Source-clip time remaps: (src_start, src_end, speed). Local scene time = sum of (end-start)/speed.
# search.mp4: home 0-6, typing 6-21.5, results 22, tap 24.3, viewer 25-31 (slide 10 at ~28.5, back ~31)
SEARCH = [(4.8, 6.0, 1.0), (6.0, 21.6, 8.0), (21.6, 24.2, 1.0)]          # 1.2 + 3.1 + 2.6 = 6.9 s
SEARCH_RESULTS = 3.3
VIEWER = [(24.3, 27.8, 1.0), (27.8, 31.4, 1.6)]                           # 3.5 + 2.3 = 5.8 s
# recording.mp4: typing 6-15, results 15, tap ~19.5, transcript 20-21.4
RECORDING = [(5.2, 6.0, 1.0), (6.0, 15.0, 6.5), (15.0, 19.4, 1.6), (19.4, 21.3, 1.0), (21.3, 21.4, 0.07)]
RECORDING_RESULTS = 2.3
# query.mp4: typing 6-15, results 15-22
QUERY = [(5.2, 6.0, 1.0), (6.0, 15.0, 6.5), (15.0, 18.6, 1.0)]            # 0.8 + 2.0 + 3.6 = 6.4 s
QUERY_RESULTS = 2.3
# ask.mp4: typing 1-11.5, sent 12, key sentences 14.5, model loading 15-56, writing 56-76, done 76
ASK = [(1.2, 11.6, 6.5), (11.6, 15.6, 1.0), (15.6, 56.0, 25.0), (56.0, 76.4, 7.0), (76.4, 79.4, 1.0)]
ASK_FAST = (5.6, 10.1)          # local window of the 20x / 7x parts
ASK_FAST_LABEL = "sped up · model loading and writing"
ASK_DONE = 10.2
