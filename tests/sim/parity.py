"""말뭉치(한 줄에 하나) → '원문\t라벨' (라벨 없으면 —). Kotlin LabelerParityTest 대조용."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
from labeler import Dict, label
d = Dict()
for ln in open(sys.argv[1], encoding='utf-8'):
    ln = ln.rstrip('\n').replace('\t', ' ')
    if not ln.strip():
        continue
    r = label(d, ln)
    print(f"{ln}\t{r[0] if r else '—'}")
