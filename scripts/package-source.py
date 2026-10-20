"""Create an allow-listed source archive. Run with Python 3; never include runtime data."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import hashlib
root=Path(__file__).resolve().parent.parent
roots=['backend/src','frontend','docs','scripts','output/imagegen','output/verification']
files={root/p for p in ['README.md','AGENTS.md','.gitignore','.dockerignore','.env.example','compose.yaml','backend/pom.xml','backend/Dockerfile']}
for rel in roots:
    for p in (root/rel).rglob('*'):
        if p.is_file() and not any(x in p.relative_to(root).parts for x in ['node_modules','target','.runtime','__pycache__']): files.add(p)
files={p for p in files if p.exists() and (not p.name.startswith('.env') or p.name=='.env.example') and p.suffix not in ['.key','.pem','.log','.zip']}
destination=root/'output/delivery/jingyue-ticket-v1-source.zip'
destination.parent.mkdir(parents=True,exist_ok=True)
manifest=[]
with ZipFile(destination,'w',ZIP_DEFLATED) as archive:
    for p in sorted(files):
        rel=p.relative_to(root).as_posix(); content=p.read_bytes(); archive.writestr('jingyue-ticket-platform/'+rel,content)
        manifest.append(hashlib.sha256(content).hexdigest()+'  '+rel)
    archive.writestr('jingyue-ticket-platform/SHA256SUMS.txt','\n'.join(manifest)+'\n')
print('Source archive:',destination)
print('Files:',len(files),'Bytes:',destination.stat().st_size,'SHA256:',hashlib.sha256(destination.read_bytes()).hexdigest())
