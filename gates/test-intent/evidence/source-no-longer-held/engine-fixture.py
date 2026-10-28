import os, pathlib, subprocess, json, time, signal
base=pathlib.Path('/home/elias/.cache/am/mif8de7q/tmp/engine-live')
base.mkdir(exist_ok=True)
for name in ['home','data','app','xdg-config','xdg-data','xdg-cache','xdg-state','tmp','corpus']:
 (base/name).mkdir(exist_ok=True)
(base/'corpus'/'cited.txt').write_text('WP5.8 source fixture. The cedar archive keeps its records for seven years.\n')
env={k:v for k,v in os.environ.items() if not k.startswith('JUSTSEARCH_') and k not in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','JAVA_OPTS','_JAVA_OPTIONS','DISPLAY','WAYLAND_DISPLAY']}
env.update({'CUDA_VISIBLE_DEVICES':'-1','HOME':str(base/'home'),'JUSTSEARCH_HOME':str(base/'app'),
 'JUSTSEARCH_DATA_DIR':str(base/'data'),'JUSTSEARCH_REPO_ROOT':'/home/elias/dev/js-uiux-source-gone','XDG_CONFIG_HOME':str(base/'xdg-config'),'XDG_DATA_HOME':str(base/'xdg-data'),
 'XDG_CACHE_HOME':str(base/'xdg-cache'),'XDG_STATE_HOME':str(base/'xdg-state'),'TMPDIR':str(base/'tmp'),
 'UI_OPTS':'-XX:-UsePerfData -Djava.io.tmpdir='+str(base/'tmp')+' -Duser.home='+str(base/'home'),'JUSTSEARCH_API_PORT':'0','JUSTSEARCH_DEV_HOTRELOAD':'false',
 'JUSTSEARCH_GPU_LAYERS':'0','JUSTSEARCH_REASONING_BUDGET':'0',
 'JUSTSEARCH_SERVER_EXE':'/home/elias/dev/llama.cpp-b8571/build/bin/llama-server',
 'JUSTSEARCH_LLM_MODEL_PATH':'/home/elias/dev/justsearch/models/compact/Qwen3.5-4B-Q4_K_M.gguf'})
settings=base/'app'/'ui';settings.mkdir(exist_ok=True)
(settings/'settings.json').write_text(json.dumps({'schemaVersion':5,'acceptedRevision':0,'lastCommittedOperationKey':None,'settings':{'gpuLayers':0,'contextLength':16384,
 'embedOnnxModelPath':'','spladeModelPath':'','rerankerModelPath':'','citationScorerModelPath':'','nerModelPath':'',
 'serverExecutablePath':env['JUSTSEARCH_SERVER_EXE'],'llmModelPath':env['JUSTSEARCH_LLM_MODEL_PATH']}}))
log=open(base/'engine.log','w')
p=subprocess.Popen(['/home/elias/dev/js-uiux-source-gone/modules/ui/build/install/ui/bin/ui'],cwd=base,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
(base/'process.json').write_text(json.dumps({'pid':p.pid,'startticks':pathlib.Path(f'/proc/{p.pid}/stat').read_text().split()[21],'startedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())}))
try:
 while p.poll() is None:
  if (base/'stop').exists():
   os.killpg(p.pid,signal.SIGTERM)
   break
  time.sleep(1)
 try: code=p.wait(timeout=40)
 except subprocess.TimeoutExpired:
  os.killpg(p.pid,signal.SIGKILL);code=p.wait()
 (base/'exit.json').write_text(json.dumps({'exit':code}))
finally:
 if p.poll() is None: os.killpg(p.pid,signal.SIGTERM)
 log.close()
