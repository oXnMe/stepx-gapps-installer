lib.setfsuid(0)
class Regs(ctypes.Structure):
 _fields_=[('x',ctypes.c_uint64*31),('sp',ctypes.c_uint64),('pc',ctypes.c_uint64),('pstate',ctypes.c_uint64)]
class IOV(ctypes.Structure):
 _fields_=[('base',ctypes.c_void_p),('length',ctypes.c_size_t)]
lib.ptrace.restype=ctypes.c_long
lib.ptrace.argtypes=[ctypes.c_uint,ctypes.c_int,ctypes.c_void_p,ctypes.c_void_p]
def pt(cmd,pid,addr=0,data=0):
 ctypes.set_errno(0);r=lib.ptrace(cmd,pid,addr,data)
 if r==-1 and ctypes.get_errno():raise OSError(ctypes.get_errno(),'ptrace '+hex(cmd))
 return r
def regs(pid,new=None):
 r=Regs() if new is None else new;iov=IOV(ctypes.addressof(r),ctypes.sizeof(r));pt(0x4204 if new is None else 0x4205,pid,1,ctypes.addressof(iov));return r
pid=int(os.environ["STEPX_SYSTEM_SERVER_PID"])
assert open('/proc/'+str(pid)+'/comm').read().strip()=='system_server'
maps=open('/proc/'+str(pid)+'/maps').read().splitlines()
def image_base(name):
 lines=[l.split() for l in maps if l.endswith('/'+name)];assert lines
 return min(int(l[0].split('-')[0],16)-int(l[2],16) for l in lines)
for soname,expected in {'libc.so':'76a754389a8242328cec7c1fed868289a27eb843d49175e1e0ada83f9b60f6da','libart.so':'5d6a675fc9931595266623b7b682ec5c513128739f8194b64db289b3fddb0836'}.items():
 paths={l.split()[-1] for l in maps if l.endswith('/'+soname)}
 assert len(paths)==1, 'Ambiguous native image'
 assert hashlib.sha256(open(paths.pop(),'rb').read()).hexdigest()==expected, 'Unsupported Android native image: '+soname
cb=image_base('libc.so');ab=image_base('libart.so')
tids=[]
for n in os.listdir('/proc/'+str(pid)+'/task'):
 p='/proc/'+str(pid)+'/task/'+n
 if open(p+'/comm').read().strip()=='android.ui':tids.append(int(n))
assert len(tids)==1;t=tids[0];out['target_pid']=pid;out['target_thread']=t
memfd=os.open('/proc/'+str(pid)+'/mem',os.O_RDWR)
def read(addr,n):
 b=os.pread(memfd,n,addr&0x00ffffffffffffff);assert len(b)==n;return b
def write(addr,b):assert os.pwrite(memfd,b,addr&0x00ffffffffffffff)==len(b)
def u64(addr):return int.from_bytes(read(addr,8),'little')
def wait_stop(timeout=5):
 until=time.monotonic()+timeout
 while time.monotonic()<until:
  r,status=os.waitpid(t,os.WNOHANG|0x40000000)
  if r:
   if not os.WIFSTOPPED(status):raise RuntimeError('target exited '+str(status))
   return status
  time.sleep(.001)
 pt(0x4207,t);_,st=os.waitpid(t,0x40000000);raise TimeoutError('remote call timed out')
attached=False;saved=None;arena=0;env=0;local_frame=False;calls=[]
try:
 pt(0x4206,t);attached=True;pt(0x4207,t);wait_stop();saved=regs(t)
 def call(fn,args,label):
  r=Regs.from_buffer_copy(saved)
  for i,a in enumerate(args):r.x[i]=a&0xffffffffffffffff
  r.pc=fn;r.x[30]=0;regs(t,r);pt(7,t);calls.append(label)
  for signal_attempt in range(8):
   st=wait_stop();ret=regs(t)
   if ret.pc==0 and os.WSTOPSIG(st)==11:return ret.x[0]
   # ART uses handled SIGSEGV for managed implicit checks in its boot code.
   mapping=next((l for l in open('/proc/'+str(pid)+'/maps') if int(l.split('-')[0],16)<=ret.pc<int(l.split()[0].split('-')[1],16)), '')
   siginfo=ctypes.create_string_buffer(128);pt(0x4202,t,0,ctypes.addressof(siginfo));fault=int.from_bytes(siginfo.raw[16:24],'little')
   if os.WSTOPSIG(st)==11 and fault<4096 and 'x' in mapping.split()[1] and ('boot' in mapping or 'dalvik' in mapping):
    out.setdefault('art_handled_signals',[]).append({'pc':hex(ret.pc),'fault':hex(fault),'mapping':mapping.strip()});pt(7,t,0,11);continue
   raise RuntimeError('unexpected stop '+label+' pc='+hex(ret.pc)+' sig='+str(os.WSTOPSIG(st))+' fault='+hex(fault)+' mapping='+mapping.strip())
  raise RuntimeError('Too many ART handled signals')
 assert call(cb+0x79170,[],'getpid')==pid
 arena=call(cb+0xd77c0,[0,1024*1024,3,0x22,-1,0],'mmap');assert 0x10000<arena<0xffffffffffff0000
 cursor=arena+0x1000
 def alloc(b):
  global cursor
  a=cursor;write(a,b);cursor=(cursor+len(b)+15)&~15;assert cursor<arena+1024*1024;return a
 def cstr(s):return alloc(s.encode()+b'\x00')
 def argsv(*args):return alloc(b''.join((a&0xffffffffffffffff).to_bytes(8,'little') for a in args))
 assert call(ab+0x669a4c,[arena,1,arena+8],'get_java_vm')==0 and int.from_bytes(read(arena+8,4),'little')==1
 vm=u64(arena);vmt=u64(vm)
 assert call(u64(vmt+6*8),[vm,arena+16,0x10006],'get_env')==0
 env=u64(arena+16);table=u64(env)
 def jni(index,*args):return call(u64(table+index*8),[env,*args],'jni_'+str(index))
 def check(label):
  if jni(228):jni(17);raise RuntimeError('Java exception at '+label)
 def find(name):
  x=jni(6,cstr(name));check(name);assert x;return x
 def method(c,name,sig,static=False):
  x=jni(113 if static else 33,c,cstr(name),cstr(sig));check(name);assert x;return x
 assert jni(19,64)==0;local_frame=True
 tc=find('java/lang/Thread');current=method(tc,'currentThread','()Ljava/lang/Thread;',True)
 thread=jni(116,tc,current,0);check('currentThread')
 cm=method(tc,'getContextClassLoader','()Ljava/lang/ClassLoader;');parent=jni(36,thread,cm,0);check('context_loader');assert parent
 dc=find('dalvik/system/InMemoryDexClassLoader');ctor=method(dc,'<init>','(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V')
 dex=base64.b64decode('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GgA0ABoBMwAaAjUAJDAVAAIBDABpAAIADgAAAAEAAQABAAAAhQcAAAQAAABwEAkAAAAOACoCAAAOh0sAMAAOe//wLT1ujAJ7HR6lpYfELT1MHh6maR5LPFuWLT0uhwEUD+YCex0eHuEBEg8Ca+8CGFkBEw9aHlqHABwADgAmAA48AAAAAQAAAAsAAAABAAAACgAAAAIAAAAKAAsAAgAAAAoAFAABAAAAAAAAAAEAAAABAAAAAgAAAAsAEwABAAAABQAAAAEAAAAHAAAAAwAAAAsAAQABAAAAAQAAABIAAAAHIEZBSUxFRAAHIGVycm9yIAAGIGZhaWw9AAItPgACOiAACDxjbGluaXQ+AAY8aW5pdD4AAUMAB0RFRkFVTFQAB0VOQUJMRUQAEUVOQUJMRV9SRVNVTFQgb2s9ABpFTkFCTEVfUkVTVUxUIG9rPTAgZmFpbD0zCgAGRVJST1IgADhFUlJPUiBwYWNrYWdlIHNlcnZpY2UgbWlzc2luZwpFTkFCTEVfUkVTVUxUIG9rPTAgZmFpbD0zCgA3RVJST1Igc3lzdGVtIGNvbnRleHQgbWlzc2luZwpFTkFCTEVfUkVTVUxUIG9rPTAgZmFpbD0zCgAQRW5hYmxlUHJvYmUuamF2YQABSQACSUwAAUwAAkxDAAJMSQACTEwAA0xMTAAZTGFuZHJvaWQvY29udGVudC9Db250ZXh0OwAjTGFuZHJvaWQvY29udGVudC9wbS9QYWNrYWdlTWFuYWdlcjsAGkxkYWx2aWsvYW5ub3RhdGlvbi9UaHJvd3M7ABVMamF2YS9pby9QcmludFdyaXRlcjsAFkxqYXZhL2lvL1N0cmluZ1dyaXRlcjsAEExqYXZhL2lvL1dyaXRlcjsAEUxqYXZhL2xhbmcvQ2xhc3M7ABVMamF2YS9sYW5nL0V4Y2VwdGlvbjsAEkxqYXZhL2xhbmcvT2JqZWN0OwASTGphdmEvbGFuZy9TdHJpbmc7ABlMamF2YS9sYW5nL1N0cmluZ0J1aWxkZXI7ABVMamF2YS9sYW5nL1Rocm93YWJsZTsAGUxqYXZhL2xhbmcvcmVmbGVjdC9GaWVsZDsAGkxqYXZhL2xhbmcvcmVmbGVjdC9NZXRob2Q7ABNMc3RlcHgvRW5hYmxlUHJvYmU7AAhQQUNLQUdFUwABVgACVkwABFZMSUkAAlZaAAFaABJbTGphdmEvbGFuZy9DbGFzczsAE1tMamF2YS9sYW5nL09iamVjdDsAE1tMamF2YS9sYW5nL1N0cmluZzsAGmFuZHJvaWQuYXBwLkFjdGl2aXR5VGhyZWFkABlhbmRyb2lkLm9zLlNlcnZpY2VNYW5hZ2VyAAZhcHBlbmQAE2NvbS5hbmRyb2lkLnZlbmRpbmcAFmNvbS5nb29nbGUuYW5kcm9pZC5nbXMAFmNvbS5nb29nbGUuYW5kcm9pZC5nc2YAFWN1cnJlbnRBY3Rpdml0eVRocmVhZAAHZW5hYmxlIAAFZmllbGQAB2Zvck5hbWUAA2dldAAcZ2V0QXBwbGljYXRpb25FbmFibGVkU2V0dGluZwAIZ2V0Q2xhc3MAEGdldERlY2xhcmVkRmllbGQACmdldE1lc3NhZ2UACWdldE1ldGhvZAARZ2V0UGFja2FnZU1hbmFnZXIACmdldFNlcnZpY2UADWdldFNpbXBsZU5hbWUAEGdldFN5c3RlbUNvbnRleHQABmludm9rZQAIbUNvbnRleHQAB3BhY2thZ2UAD3ByaW50U3RhY2tUcmFjZQADcnVuAA1zZXRBY2Nlc3NpYmxlABxzZXRBcHBsaWNhdGlvbkVuYWJsZWRTZXR0aW5nAAZ0aGlzJDAACHRvU3RyaW5nAAV2YWx1ZQAHdmFsdWVPZgCcAX5+RDh7ImJhY2tlbmQiOiJkZXgiLCJjb21waWxhdGlvbi1tb2RlIjoiZGVidWciLCJoYXMtY2hlY2tzdW1zIjpmYWxzZSwibWluLWFwaSI6MzQsInNoYS0xIjoiZmFjZWRmNDFiYmQyOGI1NjNkMWU5ZTA5YzVmNzJkN2M1Y2E1OThkNSIsInZlcnNpb24iOiI4LjIuMi1kZXYifQACBAFNHAEYCQMABAAAGgEaARoXiIAE+A0BgoAEpA4BCogIAQm8CAIEAAQBAAAAAQAAADoNAAAAAAAAAAAAAAEAAAAAAAAAGQAAAGgNAAARAAAAAAAAAAEAAAAAAAAAAQAAAFAAAABwAAAAAgAAABYAAACwAQAAAwAAABQAAAAIAgAABAAAAAMAAAD4AgAABQAAABsAAAAQAwAABgAAAAEAAADoAwAAASAAAAQAAAAIBAAAAyAAAAQAAAA8BwAAARAAAAsAAACMBwAAAiAAAFAAAADmBwAABCAAAAEAAAA6DQAAACAAAAEAAABCDQAABSAAAAEAAABgDQAAAxAAAAEAAABoDQAABiAAAAEAAABwDQAAABAAAAEAAACIDQAA');dexp=alloc(dex);bb=jni(229,dexp,len(dex));check('dex_buffer')
 loader=jni(30,dc,ctor,argsv(bb,parent));check('new_loader');assert loader
 load=method(dc,'loadClass','(Ljava/lang/String;)Ljava/lang/Class;');name=jni(167,cstr('stepx.EnableProbe'));clazz=jni(36,loader,load,argsv(name));check('load_probe');assert clazz
 run=method(clazz,'run','()Ljava/lang/String;',True);answer=jni(116,clazz,run,0);check('run');assert answer
 length=jni(168,answer);chars=jni(169,answer,0);check('get_answer');out['java_result']=read(chars,length).decode();jni(170,answer,chars)
 out['calls']=len(calls)
finally:
 out['last_call']=calls[-1] if calls else None;out['calls']=len(calls)
 if attached:
  if env:
   try:
    jni(17)
    if local_frame:jni(20,0)
   except Exception as e:out['jni_cleanup_error']=repr(e)
  # DexClassLoader may retain the backing ByteBuffer, so its 1 MiB arena lives until reboot.
  if saved is not None:regs(t,saved)
  pt(17,t);out['detached']=True
 os.close(memfd)
