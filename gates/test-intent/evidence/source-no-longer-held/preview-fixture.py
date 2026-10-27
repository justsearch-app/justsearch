import json,os,pathlib,subprocess,time,signal,re
base=pathlib.Path('/home/elias/.cache/am/mif8de7q/tmp/engine-live')
m=json.loads((base/'data/runtime/manifest.json').read_text())
env={k:v for k,v in os.environ.items() if not k.startswith('JUSTSEARCH_') and k not in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','_JAVA_OPTIONS','DISPLAY','WAYLAND_DISPLAY']}
env['CUDA_VISIBLE_DEVICES']='-1';env['VITE_JUSTSEARCH_API_PORT']=str(m['head']['apiPort'])
log=open(base/'preview.log','w')
p=subprocess.Popen(['node','node_modules/vite/bin/vite.js','preview','--host','127.0.0.1','--port','0','--strictPort'],cwd='/home/elias/dev/js-uiux-source-gone/modules/ui-web',env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
identity={'pid':p.pid,'startticks':pathlib.Path(f'/proc/{p.pid}/stat').read_text().split()[21]}
(base/'preview-process.json').write_text(json.dumps(identity))
try:
 while p.poll() is None:
  text=(base/'preview.log').read_text()
  port=re.search(r'http://127.0.0.1:(\d+)/',text)
  if port: (base/'preview-port.json').write_text(json.dumps({'port':int(port[1])}))
  if (base/'preview-stop').exists(): os.killpg(p.pid,signal.SIGTERM);break
  time.sleep(.5)
 try:code=p.wait(timeout=20)
 except subprocess.TimeoutExpired: os.killpg(p.pid,signal.SIGKILL);code=p.wait()
 (base/'preview-exit.json').write_text(json.dumps({'exit':code}))
finally:
 if p.poll() is None: os.killpg(p.pid,signal.SIGTERM)
 log.close()
