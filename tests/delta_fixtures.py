import io
from pathlib import Path
import random
import sys
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from apk_delta import generate
rng = random.Random(81)
root = Path(sys.argv[1])
base = rng.randbytes(320000)
def apk(changed):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('classes.dex', changed)
        z.writestr('resources.arsc', base)
        z.writestr('assets/data.bin', base[:90000])
    return out.getvalue()
for name, old, new in [('shifted', base, b'new header'+base[:160000]+b'changed bytes'+base[160100:]+b'end'), ('same',base,base), ('random',base,rng.randbytes(290000)), ('small',b'abc',b'def'), ('reordered',base,base[160000:]+base[:160000]), ('zip',apk(b'old dex'),apk(b'changed dex'*100))]:
    (root / f'{name}.base').write_bytes(old)
    (root / f'{name}.target').write_bytes(new)
    with (root / f'{name}.delta').open('wb') as f: generate(old,new,f)
