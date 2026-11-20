"""Create the owner's private ready-to-run jar. Never print or archive the FRP credential in source."""
from pathlib import Path
import argparse,hashlib,json,re,tomllib,zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--frpc-config',type=Path,required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
version=re.search(r'^mod_version=(.+)$',(root/'gradle.properties').read_text(),re.M)[1].strip()
config=tomllib.loads(args.frpc_config.read_text(encoding='utf-8-sig'))
private={'enabled':True,'serverAddr':config['serverAddr'],'serverPort':config.get('serverPort',7000),
         'authToken':config.get('auth',{}).get('token',''),'minPort':20000,'maxPort':59999,'maxAttempts':12,'startupSeconds':15}
if not private['authToken']:
    raise SystemExit('Source FRP configuration has no token')
source=root/'build/libs'/f'superwy-fabric-{version}.jar'
target=root/'dist'/f'superwy-fabric-{version}-private.jar'
with zipfile.ZipFile(source) as original,zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as private_jar:
    for item in original.infolist():
        if item.filename!='superwy-private.json':
            private_jar.writestr(item,original.read(item.filename))
    private_jar.writestr('superwy-private.json',json.dumps(private,ensure_ascii=False))
manifest_path=root/'dist/release-manifest.json'
manifest=json.loads(manifest_path.read_text(encoding='utf-8'))
manifest['files'][target.name]={'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'privateCredentials':True}
manifest_path.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print('Private jar:',target)
print('SHA256:',manifest['files'][target.name]['sha256'])
