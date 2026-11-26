
import zipfile, io, os
from PIL import Image
src = r"_e2e2/master.zip"
dst = r"_e2e2/png"
os.makedirs(dst, exist_ok=True)
z = zipfile.ZipFile(src)
names = z.namelist()
print("entries", len(names), names[:3], names[-2:])
bad = z.testzip()
print("testzip", bad)
for n in names:
    data = z.read(n)
    open(os.path.join(dst, os.path.basename(n)), "wb").write(data)
ims = sorted(os.listdir(dst))
print("files", len(ims))
im = Image.open(os.path.join(dst, ims[0]))
print("size0", im.size, im.mode)
tot = 0
for f in ims:
    tot += Image.open(os.path.join(dst, f)).size[1]
print("total h", tot)
